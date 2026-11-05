package io.github.crafteve.idlefactory.factory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.crafteve.idlefactory.factory.io.DiagEvent;
import io.github.crafteve.idlefactory.factory.io.MachineRunner;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.node.Node;
import io.github.crafteve.idlefactory.factory.node.NodeHealthResolver;
import io.github.crafteve.idlefactory.factory.node.NodeManager;
import io.github.crafteve.idlefactory.factory.node.NodeTypeResolver;
import io.github.crafteve.idlefactory.factory.observation.Observation;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;
import io.github.crafteve.idlefactory.factory.probe.BlockObservation;
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
 * 健康不存副本，每次由 {@link NodeHealthResolver} 从观测快照现算。</p>
 */
final class FactoryObservationRunner {
    private final MinecraftServer server;
    private final FactoryManager manager;
    private final ObservationService observations;
    private final MachineRunner machineRunner;
    private final NodeManager nodeManager;
    private final FactoryNoticeHandler notices;
    /** 档案变更出口：类型迁移时落盘并触发对账。 */
    private final Runnable publish;

    /** 每 tick 复用的三个优先级桶与合成序列。 */
    private final List<BlockKey> dirtyKeys = new ArrayList<>();
    private final List<BlockKey> onlineKeys = new ArrayList<>();
    private final List<BlockKey> restKeys = new ArrayList<>();
    private final List<BlockKey> orderedKeys = new ArrayList<>();
    /** 去重集合：同一方块可能被多台机器 / 多个工厂引用。 */
    private final Set<BlockKey> seen = new HashSet<>();
    /** 每 tick 复用的方块观测投影。 */
    private final Map<BlockKey, BlockObservation> blockObservations = new HashMap<>();

    /**
     * @param server        服务端
     * @param manager       工厂档案柜
     * @param observations  观测服务
     * @param machineRunner 执行器，用于恢复时唤醒机器
     * @param nodeManager   虚拟节点管理器，用于同步可用性
     * @param notices       失联/恢复通知聚合器
     * @param publish       档案变更出口
     */
    FactoryObservationRunner(MinecraftServer server, FactoryManager manager,
            ObservationService observations, MachineRunner machineRunner,
            NodeManager nodeManager, FactoryNoticeHandler notices, Runnable publish) {
        this.server = server;
        this.manager = manager;
        this.observations = observations;
        this.machineRunner = machineRunner;
        this.nodeManager = nodeManager;
        this.notices = notices;
        this.publish = publish;
    }

    /** 观测一轮：排好序列 → 观测 → 解析健康与类型迁移 → 消费状态迁移。 */
    void tick() {
        this.collectKeys();
        this.observations.tick(this.orderedKeys);
        this.snapshotBlocks();
        this.resolveNodes();
        for (ObservationService.Transition transition : this.observations.drainTransitions()) {
            this.handleTransition(transition);
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
                    boolean online = true;
                    for (BlockKey block : machine.blocks()) {
                        if (this.observations.observe(block).health() != Health.PRESENT) {
                            online = false;
                            break;
                        }
                    }
                    for (BlockKey block : machine.blocks()) {
                        if (!this.seen.add(block)) {
                            continue;
                        }
                        if (this.observations.isDirty(block)) {
                            this.dirtyKeys.add(block);
                        } else if (online) {
                            this.onlineKeys.add(block);
                        } else {
                            this.restKeys.add(block);
                        }
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

    /** 把观测服务的结果投影成 {@link BlockObservation}，复用同一张 map。 */
    private void snapshotBlocks() {
        this.blockObservations.clear();
        for (BlockKey key : this.orderedKeys) {
            Observation observation = this.observations.observe(key);
            this.blockObservations.put(key, new BlockObservation(observation.blockId(),
                    observation.health(), observation.health() != Health.UNKNOWN,
                    observation.observedAtTick()));
        }
    }

    /** 解析每个节点的健康与类型迁移。 */
    private void resolveNodes() {
        long now = this.observations.currentTick();
        long staleTicks = FactoryLimitsConfig.staleHealthTicks();
        for (Factory factory : this.manager.all()) {
            for (Node node : factory.config().nodes().all()) {
                Node current = node;
                Optional<ResourceLocation> migration = NodeTypeResolver.migrationTarget(current,
                        this.blockObservations, now, staleTicks);
                if (migration.isPresent()) {
                    Node migrated = current.withTypeBlockId(migration.get());
                    if (factory.config().library().replace(migrated)
                            == io.github.crafteve.idlefactory.factory.node.NodeLibrary.Result.REPLACED) {
                        current = migrated;
                        this.publish.run();
                    }
                }
                NodeHealthResolver.Snapshot snapshot = NodeHealthResolver.resolve(current,
                        this.blockObservations, now, staleTicks);
                this.nodeManager.setAvailable(current.id(),
                        snapshot.health() != NodeHealth.OFFLINE);
            }
        }
    }

    /**
     * 消费一次状态迁移：记事件，恢复时唤醒机器。
     *
     * <p>{@link Health#UNLOADED} 刻意不进事件流——玩家走两步就会触发一片，会把 {@code tail} 淹掉。
     * 未加载只在 {@code diag observe} 里看得到。</p>
     *
     * @param transition 迁移
     */
    private void handleTransition(ObservationService.Transition transition) {
        long now = this.server.overworld().getGameTime();
        for (Factory factory : this.manager.all()) {
            Optional<Node> nodeFound = factory.config().nodes().nodeAt(transition.key());
            Optional<Machine> machineFound = factory.config().nodes().machineAt(transition.key());
            if (nodeFound.isEmpty() || machineFound.isEmpty()) {
                continue;
            }
            Machine machine = machineFound.get();
            switch (transition.to()) {
                case MISSING, NO_DIMENSION -> {
                    factory.live().stats().record(DiagEvent.of(now,
                            DiagEvent.Level.ERROR, DiagEvent.Category.MACH, machine.id(),
                            null, transition.to().describe() + "，机器仍留在节点库"));
                    this.notices.missing(factory.id());
                }
                case PRESENT -> {
                    if (transition.from() != Health.UNKNOWN) {
                        factory.live().stats().record(DiagEvent.of(now, DiagEvent.Level.OK,
                                DiagEvent.Category.MACH, machine.id(), null, "机器已恢复"));
                        this.machineRunner.wake(machine.id());
                        this.notices.restored(factory.id());
                    }
                }
                default -> {
                    // UNLOADED / UNKNOWN 不记
                }
            }
        }
        this.handleContainerTransition(transition, now);
    }

    /**
     * 容器迁移：记一条仓库事件。
     *
     * @param transition 迁移
     * @param now        当前游戏时间
     */
    private void handleContainerTransition(ObservationService.Transition transition, long now) {
        if (transition.to() == Health.UNLOADED || transition.to() == Health.UNKNOWN) {
            return;
        }
        for (Factory factory : this.manager.all()) {
            if (!(factory.config().storageAnchor() instanceof ContainerAnchor anchor)) {
                continue;
            }
            for (ContainerRef ref : anchor.containers()) {
                if (!ref.dimension().equals(transition.key().dimension())
                        || !ref.pos().equals(transition.key().pos())) {
                    continue;
                }
                factory.live().stats().record(DiagEvent.of(now,
                        transition.to() == Health.PRESENT ? DiagEvent.Level.OK : DiagEvent.Level.ERROR,
                        DiagEvent.Category.STOR, null, null,
                        "容器 [" + ref.shortId() + "] " + transition.to().describe()));
            }
        }
    }
}
