package io.github.crafteve.idlefactory.factory;

import java.util.Set;
import java.util.UUID;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.io.Form;
import io.github.crafteve.idlefactory.factory.io.PassiveIoRunner;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.machine.MachineStateLibrary;
import io.github.crafteve.idlefactory.factory.node.Node;
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
    private final PassiveIoRunner nodeIoRunner;
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
     * @param nodeIoRunner      执行器
     * @param observations      观测服务
     * @param storageResolver   仓库解析器
     * @param observationRunner 观测输入与迁移消费
     * @param nodeRunner        虚拟节点推进
     * @param machineStates     机器状态集合
     * @param formBuilder       表单生成器
     */
    FactoryTickRunner(MinecraftServer server, FactoryManager manager, PassiveIoRunner nodeIoRunner,
            ObservationService observations, StorageResolver storageResolver,
            FactoryObservationRunner observationRunner, FactoryNodeRunner nodeRunner,
            MachineStateLibrary machineStates, IoFormBuilder formBuilder) {
        this.server = server;
        this.manager = manager;
        this.nodeIoRunner = nodeIoRunner;
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

        // 执行段：回收与队列由节点层驱动，这里只跑被动表单。
        for (Factory factory : this.manager.all()) {
            factory.live().analyzer().retainMachines(liveMachines);
            Form form = this.formBuilder.build(factory, this.machineStates);
            this.nodeIoRunner.runFactory(factory, form, now);
            // 速率结算由账房自己数间隔；P3 接入 IoRunner 后，执行顺序也由它统一编排。
            factory.live().analyzer().tick(now);
        }

        if (this.tickCounter % HEARTBEAT_INTERVAL == 0) {
            IdleFactory.LOGGER.trace("Idle Factory heartbeat: {} factory instance(s), tick {}",
                    this.manager.all().size(), this.tickCounter);
        }
    }
}
