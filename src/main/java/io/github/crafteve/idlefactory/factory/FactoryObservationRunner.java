package io.github.crafteve.idlefactory.factory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import io.github.crafteve.idlefactory.factory.io.PassiveIoRunner;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.node.Node;
import io.github.crafteve.idlefactory.factory.node.NodeHealthResolver;
import io.github.crafteve.idlefactory.factory.node.NodeManager;
import io.github.crafteve.idlefactory.factory.node.NodeTypeResolver;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;
import io.github.crafteve.idlefactory.factory.probe.Health;
import io.github.crafteve.idlefactory.factory.probe.NodeHealth;
import io.github.crafteve.idlefactory.factory.storage.ContainerAnchor;
import io.github.crafteve.idlefactory.factory.storage.ContainerRef;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

/**
 * 观测输入与健康消费：每 tick 把「被引用的方块」按优先级排成序列交给观测服务，
 * 再用观测快照解析节点 / 机器健康与类型迁移。
 *
 * <p>优先级：本 tick 标脏的方块 &gt; 在线机器的方块 &gt; 其余。序列、观测 map 与三个分类列表
 * 都是复用字段，不每 tick 为全库重新分配。</p>
 *
 * <p>本类是 {@code withTypeBlockId} 的<b>唯一写入点</b>：只有
 * {@link NodeTypeResolver#migrationTarget} 成立时才替换节点，并通过 {@code publish} 落盘。
 * 类型迁移会改变快照里的节点类型，所以发布回调带工厂，走推送版。健康不存副本，每次由
 * {@link NodeHealthResolver} 从观测快照现算。</p>
 */
final class FactoryObservationRunner {
    private final MinecraftServer server;
    private final FactoryManager manager;
    private final ObservationService observations;
    private final PassiveIoRunner nodeIoRunner;
    private final NodeManager nodeManager;
    private final FactoryNoticeHandler notices;
    /** 档案变更出口：类型迁移时落盘、触发对账并推链接快照。 */
    private final Consumer<Factory> publish;

    /** 每 tick 复用的三个优先级桶与合成序列。 */
    private final List<BlockKey> dirtyKeys = new ArrayList<>();
    private final List<BlockKey> onlineKeys = new ArrayList<>();
    private final List<BlockKey> restKeys = new ArrayList<>();
    private final List<BlockKey> orderedKeys = new ArrayList<>();
    /** 去重集合：同一方块可能被多台机器 / 多个工厂引用。 */
    private final Set<BlockKey> seen = new HashSet<>();

    /**
     * @param server        服务端
     * @param manager       工厂档案柜
     * @param observations  观测服务
     * @param nodeIoRunner 执行器，用于恢复时唤醒机器
     * @param nodeManager   虚拟节点管理器，用于同步可用性
     * @param notices       失联/恢复通知聚合器
     * @param publish       档案变更出口
     */
    FactoryObservationRunner(MinecraftServer server, FactoryManager manager,
            ObservationService observations, PassiveIoRunner nodeIoRunner,
            NodeManager nodeManager, FactoryNoticeHandler notices, Consumer<Factory> publish) {
        this.server = server;
        this.manager = manager;
        this.observations = observations;
        this.nodeIoRunner = nodeIoRunner;
        this.nodeManager = nodeManager;
        this.notices = notices;
        this.publish = publish;
    }

    /** 观测一轮：排好序列 → 观测 → 解析健康与类型迁移 → 消费方块变化。 */
    void tick() {
        this.collectKeys();
        this.observations.tick(this.orderedKeys);
        this.resolveNodes();
        for (ObservationService.BlockChange change : this.observations.drainChanges()) {
            this.handleChange(change);
        }
        this.notices.flush(this.server, this.manager);
    }

    /** 收集被引用的方块并按优先级分桶。 */
    private void collectKeys() {
        this.dirtyKeys.clear();
        this.onlineKeys.clear();
        this.restKeys.clear();
        this.orderedKeys.clear();
        this.seen.clear();
        for (Factory factory : this.manager.all()) {
            for (Node node : factory.config().nodes().all()) {
                for (Machine machine : node.machines()) {
                    BlockKey block = machine.block();
                    if (!this.seen.add(block)) {
                        continue;
                    }
                    boolean online = this.observations.observe(block).health() == Health.PRESENT;
                    if (this.observations.isDirty(block)) {
                        this.dirtyKeys.add(block);
                    } else if (online) {
                        this.onlineKeys.add(block);
                    } else {
                        this.restKeys.add(block);
                    }
                }
            }
            // 仓库容器和机器共用同一张方块级观测
            if (factory.config().storageAnchor() instanceof ContainerAnchor anchor) {
                for (ContainerRef container : anchor.containers()) {
                    if (this.seen.add(container.blockKey())) {
                        this.restKeys.add(container.blockKey());
                    }
                }
            }
        }
        this.orderedKeys.addAll(this.dirtyKeys);
        this.orderedKeys.addAll(this.onlineKeys);
        this.orderedKeys.addAll(this.restKeys);
    }

    /** 解析每个节点的健康与类型迁移。 */
    private void resolveNodes() {
        long now = this.observations.currentTick();
        long staleTicks = FactoryLimitsConfig.staleHealthTicks();
        for (Factory factory : this.manager.all()) {
            for (Node node : factory.config().nodes().all()) {
                Node current = node;
                Optional<ResourceLocation> migration = NodeTypeResolver.migrationTarget(current,
                        this.observations, now, staleTicks);
                if (migration.isPresent()) {
                    Node migrated = current.withTypeBlockId(migration.get());
                    if (factory.config().library().replace(migrated)
                            == io.github.crafteve.idlefactory.factory.node.NodeLibrary.Result.REPLACED) {
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

    /**
     * 消费一次方块变化：失联通知与恢复唤醒。
     *
     * <p>这里只保留有行为后果的两条路径（聚合通知、唤醒 IO 睡眠）；纯记录的诊断事件流已经
     * 随指令入口一起删除。</p>
     *
     * @param change 变化
     */
    private void handleChange(ObservationService.BlockChange change) {
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
