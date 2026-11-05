package io.github.crafteve.idlefactory.factory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import io.github.crafteve.idlefactory.factory.io.DiagEvent;
import io.github.crafteve.idlefactory.factory.io.MachineRunner;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.node.Node;
import io.github.crafteve.idlefactory.factory.node.NodeManager;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;
import io.github.crafteve.idlefactory.factory.probe.Health;
import io.github.crafteve.idlefactory.factory.storage.ContainerAnchor;
import io.github.crafteve.idlefactory.factory.storage.ContainerRef;

import java.util.Optional;

import net.minecraft.server.MinecraftServer;

/**
 * 观测输入与迁移消费：每 tick 把「被引用的位置集合」交给观测服务，再消费它吐出的状态迁移。
 *
 * <p>集合每 tick 重建，因为配置随时可能变（接入、断开、删线）。规模是「机器总数」，
 * 百台级下这点分配可以忽略；真要省再上脏标记。</p>
 *
 * <p>故意从工厂记录拼、不从占用表拼：表键是不可逆的统一 id，反解坐标还得回工厂拿——
 * 绕一圈回到原点。不如直接遍历源头，零重复数据。占用表专职门禁，引用集专职观测，
 * 各干各的。</p>
 *
 * <p>迁移的处理只有三件事：记事件、维护 {@code machineMissing}、唤醒机器；通知计数交给
 * {@link FactoryNoticeHandler}，节点可用性交给 {@link NodeManager}。</p>
 */
final class FactoryObservationRunner {
    private final MinecraftServer server;
    private final FactoryManager manager;
    private final ObservationService observations;
    private final MachineRunner machineRunner;
    private final NodeManager nodeManager;
    private final FactoryNoticeHandler notices;
    /** 每 tick 复用的「被引用位置集合」，避免反复分配。 */
    private final Set<BlockKey> observedKeys = new HashSet<>();

    /**
     * @param server        服务端
     * @param manager       工厂档案柜
     * @param observations  观测服务
     * @param machineRunner 执行器，用于恢复时唤醒机器
     * @param nodeManager   虚拟节点管理器，用于同步可用性
     * @param notices       失联/恢复通知聚合器
     */
    FactoryObservationRunner(MinecraftServer server, FactoryManager manager,
            ObservationService observations, MachineRunner machineRunner,
            NodeManager nodeManager, FactoryNoticeHandler notices) {
        this.server = server;
        this.manager = manager;
        this.observations = observations;
        this.machineRunner = machineRunner;
        this.nodeManager = nodeManager;
        this.notices = notices;
    }

    /** 观测一轮：重建被引用的位置集合，交给观测服务，再消费它吐出的状态迁移。 */
    void tick() {
        this.observedKeys.clear();
        for (Factory factory : this.manager.all()) {
            for (Node node : factory.config().nodes().all()) {
                for (Machine machine : node.machines()) {
                    this.observedKeys.addAll(machine.blocks());
                }
            }
            // 仓库容器和机器共用同一张方块级观测
            if (factory.config().storageAnchor() instanceof ContainerAnchor anchor) {
                for (ContainerRef container : anchor.containers()) {
                    this.observedKeys.add(container.blockKey());
                }
            }
        }
        this.observations.tick(this.observedKeys);
        for (ObservationService.Transition transition : this.observations.drainTransitions()) {
            this.handleTransition(transition);
        }
        this.notices.flush(this.server, this.manager);
    }

    /**
     * 消费一次状态迁移：记事件、维护 machineMissing、必要时唤醒机器。
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
            Node node = nodeFound.get();
            Machine machine = machineFound.get();
            switch (transition.to()) {
                case MISSING, NO_DIMENSION -> {
                    if (factory.live().markMachineMissing(machine.id())) {
                        factory.live().stats().record(DiagEvent.of(now,
                                DiagEvent.Level.ERROR, DiagEvent.Category.MACH, machine.id(),
                                null, transition.to().describe() + "，机器仍留在节点库"));
                        this.notices.missing(factory.id());
                    }
                }
                case PRESENT -> {
                    boolean wasMissing = factory.live().clearMachineMissing(machine.id());
                    if (transition.from() != Health.UNKNOWN) {
                        factory.live().stats().record(DiagEvent.of(now, DiagEvent.Level.OK,
                                DiagEvent.Category.MACH, machine.id(), null, "机器已恢复"));
                        this.wakeMachine(factory, machine.id());
                        if (wasMissing) {
                            this.notices.restored(factory.id());
                        }
                    }
                }
                default -> {
                    // UNLOADED / UNKNOWN 不记
                }
            }
            // 节点跟着观测走：方块不在就下架样板，回来再上架
            this.nodeManager.setAvailable(node.id(), transition.to() == Health.PRESENT);
        }
        this.handleContainerTransition(transition, now);
    }

    /**
     * 容器迁移：记一条仓库事件。
     *
     * <p>容器和机器共用观测表，但它们的消费方式不同：机器标记失联，容器只能被
     * {@code storage prune} 清理。所以这里只负责让「箱子没了」在事件流里看得见。</p>
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

    /**
     * 唤醒一台机器。
     *
     * @param factory   工厂
     * @param machineId 机器 id
     */
    private void wakeMachine(Factory factory, UUID machineId) {
        this.machineRunner.wake(machineId);
    }
}
