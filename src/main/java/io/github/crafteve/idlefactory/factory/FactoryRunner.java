package io.github.crafteve.idlefactory.factory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.command.CommandController;
import io.github.crafteve.idlefactory.command.CommandKind;
import io.github.crafteve.idlefactory.command.CommandResult;
import io.github.crafteve.idlefactory.command.CommandSource;
import io.github.crafteve.idlefactory.command.FactoryCommand;
import io.github.crafteve.idlefactory.config.IdleFactoryConfig;
import io.github.crafteve.idlefactory.integration.ae2.Ae2Binder;
import io.github.crafteve.idlefactory.io.DispatchState;
import io.github.crafteve.idlefactory.io.IoForm;
import io.github.crafteve.idlefactory.io.IoRunner;
import io.github.crafteve.idlefactory.location.BlockKey;
import io.github.crafteve.idlefactory.machine.Machine;
import io.github.crafteve.idlefactory.machine.MachineStateLibrary;
import io.github.crafteve.idlefactory.node.Node;
import io.github.crafteve.idlefactory.node.NodeHealthResolver;
import io.github.crafteve.idlefactory.runtime.LiveNodeApply;
import io.github.crafteve.idlefactory.runtime.LiveNodeRunner;
import io.github.crafteve.idlefactory.runtime.LiveNodeLibrary;
import io.github.crafteve.idlefactory.runtime.LiveNode;
import io.github.crafteve.idlefactory.observation.Health;
import io.github.crafteve.idlefactory.observation.NodeHealth;
import io.github.crafteve.idlefactory.observation.ObservationChange;
import io.github.crafteve.idlefactory.observation.ObservationChangeListener;
import io.github.crafteve.idlefactory.observation.ObservationReader;
import io.github.crafteve.idlefactory.observation.ObservationRunner;
import io.github.crafteve.idlefactory.storage.MeStorageResolver;

import appeng.api.networking.IGridNode;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 每 tick 的唯一编排者：观测 → 机器结论 → 锚点 → 虚拟节点 → 仓库 → 表单 → IO → 落盘。
 *
 * <p>顺序固定，不因工厂数量或状态改变。观测必须先于 IO：IO 门禁读的是观测结论，读路径不许
 * 自己探世界。节点的健康与类型迁移也在这一层收口：{@code withTypeBlockId} 只有这一个写入点。</p>
 *
 * <p>本类只做推进，不认识网络与界面；写命令由控制器在别处处理，两边共享同一份配置与观测。</p>
 */
final class FactoryRunner implements ObservationChangeListener {
    /** 节点对账间隔（tick）。 */
    private static final int NODE_RECONCILE_INTERVAL = 20;
    /** 仓库重解析间隔（tick）。 */
    private static final int STORAGE_REFRESH_INTERVAL = 20;
    /** 心跳日志间隔（tick）。 */
    private static final int HEARTBEAT_INTERVAL = 200;

    private final MinecraftServer server;
    private final FactoryManager manager;
    private final ObservationRunner observationRunner;
    private final ObservationReader observations;
    private final IoRunner nodeIoRunner;
    private final LiveNodeApply nodeApply;
    private final LiveNodeRunner nodeRunner;
    private final MachineStateLibrary machineStates;
    private final IoFormBuilder formBuilder;
    private final MeStorageResolver storageResolver;
    private final FactoryNoticeHandler notices;
    /** 每 tick 复用的被引用集合。 */
    private final Set<BlockKey> referenced = new HashSet<>();
    private int tickCounter;

    /**
     * @param server            服务端
     * @param manager           工厂档案柜
     * @param observationRunner 观测调度者
     * @param observations      观测只读视图
     * @param nodeIoRunner      执行编排
     * @param nodeApply         虚拟节点建拆
     * @param nodeRunner        节点对账与推进
     * @param machineStates     机器状态集合
     * @param formBuilder       表单生成器
     * @param storageResolver   仓库解析器
     * @param notices           失联通知聚合
     */
    FactoryRunner(MinecraftServer server, FactoryManager manager,
            ObservationRunner observationRunner, ObservationReader observations,
            IoRunner nodeIoRunner, LiveNodeApply nodeApply, LiveNodeRunner nodeRunner,
            MachineStateLibrary machineStates,
            IoFormBuilder formBuilder, MeStorageResolver storageResolver,
            FactoryNoticeHandler notices) {
        this.server = server;
        this.manager = manager;
        this.observationRunner = observationRunner;
        this.observations = observations;
        this.nodeIoRunner = nodeIoRunner;
        this.nodeApply = nodeApply;
        this.nodeRunner = nodeRunner;
        this.machineStates = machineStates;
        this.formBuilder = formBuilder;
        this.storageResolver = storageResolver;
        this.notices = notices;
    }

    /** 推进一 tick。 */
    void tick() {
        this.tickCounter++;
        long now = this.server.overworld().getGameTime();

        this.collectKeys();
        this.observationRunner.tick(this.referenced);
        this.resolveNodes();

        this.reconcileNodes();
        this.nodeRunner.tick();

        if (this.tickCounter % STORAGE_REFRESH_INTERVAL == 0) {
            for (Factory factory : this.manager.all()) {
                factory.refreshStorage(this.storageResolver);
            }
        }

        this.machineStates.beginRefresh();
        for (Factory factory : this.manager.all()) {
            for (Node node : factory.config().nodes().all()) {
                for (Machine machine : node.machines()) {
                    this.machineStates.observed(machine, this.observations);
                }
            }
        }
        this.machineStates.endRefresh();
        Set<UUID> liveMachines = this.machineStates.ids();

        // 执行段：回收 → 主动 → 被动由 IoRunner 统一编排；这里只负责收集输入与收口账房。
        for (Factory factory : this.manager.all()) {
            IoForm form = this.formBuilder.build(factory, this.machineStates);
            this.nodeIoRunner.run(factory, form, this.dispatchStates(factory), liveMachines, now);
            factory.analyzer().tick(now);
        }

        this.notices.flush(this.server, this.manager);

        if (this.tickCounter % HEARTBEAT_INTERVAL == 0) {
            IdleFactory.LOGGER.trace("Idle Factory heartbeat: {} factory instance(s), tick {}",
                    this.manager.all().size(), this.tickCounter);
        }
    }

    /** 收集被引用的方块；没登记过的补一行。 */
    private void collectKeys() {
        this.referenced.clear();
        for (Factory factory : this.manager.all()) {
            for (Node node : factory.config().nodes().all()) {
                for (Machine machine : node.machines()) {
                    this.observationRunner.ensure(machine.block());
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
                Optional<ResourceLocation> migration = CommandController.migrationTarget(current,
                        this.observations, now, staleTicks);
                if (migration.isPresent()) {
                    CommandResult result = CommandController.executeSystem(factory,
                            new FactoryCommand(-1, CommandKind.NODE_TYPE_MIGRATE,
                                    CommandSource.SYSTEM, current.id(), null, 0L,
                                    new FactoryCommand.Migrate(migration.get())));
                    if (result.ok()) {
                        current = factory.config().nodes().byId(current.id()).orElse(current);
                    }
                }
                NodeHealthResolver.Snapshot snapshot = NodeHealthResolver.resolve(current,
                        this.observations, now, staleTicks);
                this.nodeApply.setAvailable(current.id(),
                        snapshot.health() != NodeHealth.OFFLINE);
            }
        }
    }

    /** 虚拟节点对账：到点或收到请求才做。 */
    private void reconcileNodes() {
        if ((this.tickCounter - 1) % NODE_RECONCILE_INTERVAL != 0
                && !this.nodeApply.isReconcileRequested()) {
            return;
        }
        try {
            List<LiveNodeLibrary.Spec> specs = new ArrayList<>();
            Map<UUID, Set<UUID>> onlineMachines = new HashMap<>();
            for (Factory factory : this.manager.all()) {
                BlockKey anchor = factory.config().storageAnchor();
                if (anchor == null) {
                    continue;
                }
                ServerLevel level = this.server.getLevel(anchor.dimension());
                if (level == null || !level.isLoaded(anchor.pos())) {
                    continue;
                }
                IGridNode anchorNode = Ae2Binder.nodeAt(level, anchor.pos());
                if (anchorNode == null) {
                    continue;
                }
                long now = this.observations.currentTick();
                long staleTicks = IdleFactoryConfig.staleHealthTicks();
                for (Node node : factory.config().nodes().all()) {
                    NodeHealthResolver.Snapshot snapshot = NodeHealthResolver.resolve(node,
                            this.observations, now, staleTicks);
                    boolean available = snapshot.health() != NodeHealth.OFFLINE;
                    specs.add(new LiveNodeLibrary.Spec(factory.id(), node, anchorNode, level, available));
                    Set<UUID> online = new HashSet<>();
                    for (NodeHealthResolver.MachineState state : snapshot.machines()) {
                        if (state.health().isOnline()) {
                            online.add(state.machine().id());
                        }
                    }
                    onlineMachines.put(node.id(), online);
                }
            }
            this.nodeRunner.reconcile(specs, onlineMachines);
        } catch (RuntimeException e) {
            // 对账炸了不能让整个服务器 tick 跟着炸；下一轮对账会把状态补齐
            IdleFactory.LOGGER.warn("Idle Factory: 虚拟节点对账失败：{}", e.toString());
        }
    }

    /**
     * 收集一个工厂各节点的主动侧输入。
     *
     * @param factory 工厂
     * @return 各节点的输入，顺序与节点库一致
     */
    private List<DispatchState> dispatchStates(Factory factory) {
        List<DispatchState> states = new ArrayList<>(factory.config().nodes().size());
        for (Node node : factory.config().nodes().all()) {
            LiveNode runtime = this.nodeApply.node(node.id());
            if (runtime != null) {
                states.add(runtime.dispatchState());
            }
        }
        return states;
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
