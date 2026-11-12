package io.github.crafteve.idlefactory.factory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.io.Form;
import io.github.crafteve.idlefactory.factory.io.DispatchState;
import io.github.crafteve.idlefactory.factory.io.IoRunner;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.machine.MachineStateLibrary;
import io.github.crafteve.idlefactory.factory.node.Node;
import io.github.crafteve.idlefactory.factory.node.NodeManager;
import io.github.crafteve.idlefactory.factory.node.NodeRuntime;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;
import io.github.crafteve.idlefactory.factory.storage.StorageResolver;

import net.minecraft.server.MinecraftServer;

/**
 * 工厂的每 tick 编排器：只负责顺序与节奏，不持有档案、不做迁移判定。
 *
 * <p>每 tick 的顺序是固定的：</p>
 * <ol>
 *   <li><b>观测</b>：把「被引用的位置集合」交给观测层，并消费它吐出的状态迁移；</li>
 *   <li><b>虚拟节点</b>：对账一次（该建的建、该拆的拆），再让节点处理余量、回收与样板刷新；</li>
 *   <li><b>仓库</b>：每 20 tick 重新解析一次地址，并顺带采样一次搬运速率；</li>
 *   <li><b>IO</b>：每 tick 跑一轮搬运；</li>
 *   <li><b>心跳</b>：每 100 tick 打一条 trace 日志。</li>
 * </ol>
 *
 * <p>观测必须先于 IO：IO 的门禁读的是观测结论，读路径不许自己探世界。</p>
 *
 * <p>IO 间隔原本由 {@code /idf diag io} 调整，指令删除后固定为每 tick 一轮。</p>
 */
final class FactoryTickRunner {
    /** 心跳日志间隔（tick）。 */
    private static final int HEARTBEAT_INTERVAL = 100;
    /** 仓库状态刷新间隔（tick）：每秒重新解析一次，别让 online 变成启动时的快照。 */
    private static final int REFRESH_INTERVAL = 20;

    private final MinecraftServer server;
    private final FactoryManager manager;
    private final IoRunner nodeIoRunner;
    private final NodeManager nodeManager;
    private final ObservationService observations;
    private final StorageResolver storageResolver;
    private final FactoryObservationRunner observationRunner;
    private final FactoryNodeRunner nodeRunner;
    private final MachineStateLibrary machineStates;
    private final IoFormBuilder formBuilder;
    private int tickCounter;

    /**
     * @param server            服务端
     * @param manager           工厂档案柜
     * @param nodeIoRunner      执行编排
     * @param nodeManager       虚拟节点管理器
     * @param observations      观测服务
     * @param storageResolver   仓库解析器
     * @param observationRunner 观测输入与迁移消费
     * @param nodeRunner        虚拟节点推进
     * @param machineStates     机器状态集合
     * @param formBuilder       表单生成器
     */
    FactoryTickRunner(MinecraftServer server, FactoryManager manager, IoRunner nodeIoRunner,
            NodeManager nodeManager, ObservationService observations, StorageResolver storageResolver,
            FactoryObservationRunner observationRunner, FactoryNodeRunner nodeRunner,
            MachineStateLibrary machineStates, IoFormBuilder formBuilder) {
        this.server = server;
        this.manager = manager;
        this.nodeIoRunner = nodeIoRunner;
        this.nodeManager = nodeManager;
        this.observations = observations;
        this.storageResolver = storageResolver;
        this.observationRunner = observationRunner;
        this.nodeRunner = nodeRunner;
        this.machineStates = machineStates;
        this.formBuilder = formBuilder;
    }

    /** 每 tick 推进一次：先准备（观测 → 仓库 → 机器状态 → 表单），再执行。 */
    void tick() {
        this.tickCounter++;
        long now = this.server.overworld().getGameTime();

        this.observationRunner.tick();
        this.nodeRunner.tick(this.tickCounter);

        if (this.tickCounter % REFRESH_INTERVAL == 0) {
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
            Form form = this.formBuilder.build(factory, this.machineStates);
            this.nodeIoRunner.run(factory, form, this.dispatchStates(factory), liveMachines, now);
            // 速率结算与经验面补扫由账房自己数间隔。
            factory.live().analyzer().tick(now);
        }

        if (this.tickCounter % HEARTBEAT_INTERVAL == 0) {
            IdleFactory.LOGGER.trace("Idle Factory heartbeat: {} factory instance(s), tick {}",
                    this.manager.all().size(), this.tickCounter);
        }
    }

    /**
     * 收集一个工厂各节点的主动侧输入。
     *
     * <p>节点运行时还没建出来（锚点未就绪 / 正在分帧创建）时跳过：这一 tick 没有可投的队列入口，
     * 订单留在节点记录里，等下 tick 再交。</p>
     *
     * @param factory 工厂
     * @return 各节点的输入，顺序与节点库一致
     */
    private List<DispatchState> dispatchStates(Factory factory) {
        List<DispatchState> states = new ArrayList<>(factory.config().nodes().size());
        for (Node node : factory.config().nodes().all()) {
            NodeRuntime runtime = this.nodeManager.node(node.id());
            if (runtime != null) {
                states.add(runtime.dispatchState());
            }
        }
        return states;
    }
}
