package io.github.crafteve.idlefactory.factory;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import io.github.crafteve.idlefactory.config.IdleFactoryConfig;
import io.github.crafteve.idlefactory.io.IoRunner;
import io.github.crafteve.idlefactory.location.BlockKey;
import io.github.crafteve.idlefactory.machine.Machine;
import io.github.crafteve.idlefactory.node.Node;
import io.github.crafteve.idlefactory.node.NodeHealthResolver;
import io.github.crafteve.idlefactory.node.NodeManager;
import io.github.crafteve.idlefactory.node.NodeTypeResolver;
import io.github.crafteve.idlefactory.observation.Health;
import io.github.crafteve.idlefactory.observation.NodeHealth;
import io.github.crafteve.idlefactory.observation.ObservationChange;
import io.github.crafteve.idlefactory.observation.ObservationChangeListener;
import io.github.crafteve.idlefactory.observation.ObservationReader;
import io.github.crafteve.idlefactory.observation.ObservationRunner;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

/**
 * 观测输入与健康消费：每 tick 收集被引用的方块交给观测调度，再用观测结果解析节点健康与类型迁移，
 * 并消费观测变化（失联通知与恢复唤醒）。
 *
 * <p>本类是 {@code withTypeBlockId} 的<b>唯一写入点</b>：只有
 * {@link NodeTypeResolver#migrationTarget} 成立时才替换节点，并通过 {@code publish} 落盘。
 * 类型迁移会改变快照里的节点类型，所以发布回调带工厂，走推送版。健康不存副本，每次由
 * {@link NodeHealthResolver} 从观测事实现算。</p>
 */
final class FactoryObservationRunner implements ObservationChangeListener {
    private final MinecraftServer server;
    private final FactoryManager manager;
    private final ObservationRunner runner;
    private final ObservationReader observations;
    private final IoRunner nodeIoRunner;
    private final NodeManager nodeManager;
    private final FactoryNoticeHandler notices;
    /** 档案变更出口：类型迁移时落盘、触发对账并推链接快照。 */
    private final Consumer<Factory> publish;

    /** 每 tick 复用的被引用集合。 */
    private final Set<BlockKey> referenced = new HashSet<>();

    /**
     * @param server        服务端
     * @param manager       工厂档案柜
     * @param runner        观测调度者
     * @param observations  观测只读视图
     * @param nodeIoRunner  执行编排
     * @param nodeManager   虚拟节点管理器，用于同步可用性
     * @param notices       失联通知聚合
     * @param publish       档案变更出口
     */
    FactoryObservationRunner(MinecraftServer server, FactoryManager manager,
            ObservationRunner runner, ObservationReader observations, IoRunner nodeIoRunner,
            NodeManager nodeManager, FactoryNoticeHandler notices, Consumer<Factory> publish) {
        this.server = server;
        this.manager = manager;
        this.runner = runner;
        this.observations = observations;
        this.nodeIoRunner = nodeIoRunner;
        this.nodeManager = nodeManager;
        this.notices = notices;
        this.publish = publish;
    }

    /** 观测一轮：收集被引用方块 → 推进观测 → 解析健康与类型迁移 → 刷失联通知。 */
    void tick() {
        this.collectKeys();
        this.runner.tick(this.referenced);
        this.resolveNodes();
        this.notices.flush(this.server, this.manager);
    }

    /** 收集被引用的方块；观测层自己按游标轮转，这里不再排序分桶。 */
    private void collectKeys() {
        this.referenced.clear();
        for (Factory factory : this.manager.all()) {
            for (Node node : factory.config().nodes().all()) {
                for (Machine machine : node.machines()) {
                    this.runner.ensure(machine.block());
                    this.referenced.add(machine.block());
                }
            }
        }
    }

    /** 解析每个节点的健康与类型迁移。 */
    private void resolveNodes() {
        long now = this.observations.currentTick();
        long staleTicks = IdleFactoryConfig.staleHealthTicks();
        for (Factory factory : this.manager.all()) {
            for (Node node : factory.config().nodes().all()) {
                Node current = node;
                Optional<ResourceLocation> migration = NodeTypeResolver.migrationTarget(current,
                        this.observations, now, staleTicks);
                if (migration.isPresent()) {
                    Node migrated = current.withTypeBlockId(migration.get());
                    if (factory.config().library().replace(migrated)
                            == io.github.crafteve.idlefactory.node.NodeLibrary.Result.REPLACED) {
                        current = migrated;
                        this.publish.accept(factory);
                    }
                }
                NodeHealthResolver.Snapshot snapshot = NodeHealthResolver.resolve(current,
                        this.observations, now, staleTicks);
                this.nodeManager.setAvailable(current.id(),
                        snapshot.health() != NodeHealth.OFFLINE);
            }
        }
    }

    @Override
    public void onObservationChanged(ObservationChange change) {
        for (ObservationChange.BlockChange blockChange : change.changes()) {
            this.handleChange(blockChange);
        }
    }

    /**
     * 消费一次方块变化：失联通知与恢复唤醒。
     *
     * <p>这里只保留有行为后果的两条路径（聚合通知、唤醒 IO 睡眠）；纯记录的诊断事件流已经
     * 随指令入口一起删除。</p>
     *
     * @param change 变化
     */
    private void handleChange(ObservationChange.BlockChange change) {
        for (Factory factory : this.manager.all()) {
            Optional<Machine> machineFound = factory.config().nodes().machineAt(change.key());
            if (machineFound.isEmpty()) {
                continue;
            }
            Machine machine = machineFound.get();
            switch (change.to()) {
                case MISSING, NO_DIMENSION -> this.notices.missing(factory.id());
                case PRESENT -> {
                    if (change.from() != Health.UNKNOWN) {
                        this.nodeIoRunner.wake(machine.id());
                        this.notices.restored(factory.id());
                    }
                }
                default -> {
                    // UNLOADED / UNKNOWN 不处理
                }
            }
        }
    }
}
