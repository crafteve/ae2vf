package io.github.crafteve.idlefactory.factory;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.diag.DiagService;
import io.github.crafteve.idlefactory.factory.io.DiagEvent;
import io.github.crafteve.idlefactory.factory.io.MachineRunner;
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
 *   <li><b>仓库</b>：每 20 tick 重新解析一次地址，把上线/离线记进事件流；</li>
 *   <li><b>IO</b>：按 IO 间隔跑一轮搬运；</li>
 *   <li><b>心跳</b>：每 100 tick 打一条 trace 日志。</li>
 * </ol>
 *
 * <p>观测必须先于 IO：IO 的门禁读的是观测结论，读路径不许自己探世界。</p>
 */
final class FactoryTickRunner {
    /** 心跳日志间隔（tick）。 */
    private static final int HEARTBEAT_INTERVAL = 100;
    /** 仓库状态刷新间隔（tick）：每秒重新解析一次，别让 online 变成启动时的快照。 */
    private static final int REFRESH_INTERVAL = 20;
    /** IO 执行间隔（tick）的下限。 */
    static final int MIN_IO_INTERVAL = 1;
    /** IO 执行间隔（tick）的上限。 */
    static final int MAX_IO_INTERVAL = 20;

    private final MinecraftServer server;
    private final FactoryManager manager;
    private final MachineRunner machineRunner;
    private final ObservationService observations;
    private final StorageResolver storageResolver;
    private final FactoryObservationRunner observationRunner;
    private final FactoryNodeRunner nodeRunner;
    private int tickCounter;
    /** IO 执行间隔（tick），默认每 tick 一轮；可由 /idf diag io 调整。 */
    private int ioInterval = 1;

    /**
     * @param server            服务端
     * @param manager           工厂档案柜
     * @param machineRunner     执行器
     * @param observations      观测服务
     * @param storageResolver   仓库解析器
     * @param observationRunner 观测输入与迁移消费
     * @param nodeRunner        虚拟节点推进
     */
    FactoryTickRunner(MinecraftServer server, FactoryManager manager, MachineRunner machineRunner,
            ObservationService observations, StorageResolver storageResolver,
            FactoryObservationRunner observationRunner, FactoryNodeRunner nodeRunner) {
        this.server = server;
        this.manager = manager;
        this.machineRunner = machineRunner;
        this.observations = observations;
        this.storageResolver = storageResolver;
        this.observationRunner = observationRunner;
        this.nodeRunner = nodeRunner;
    }

    /** 每 tick 推进一次。 */
    void tick() {
        this.tickCounter++;

        this.observationRunner.tick();

        this.nodeRunner.tick(this.tickCounter);

        if (this.tickCounter % REFRESH_INTERVAL == 0) {
            long now = this.server.overworld().getGameTime();
            for (Factory factory : this.manager.all()) {
                String beforeError = factory.live().storageError();
                if (factory.refreshStorage(this.storageResolver)) {
                    recordStorage(factory, now);
                }
                String afterError = factory.live().storageError();
                if (afterError != null && !afterError.equals(beforeError)) {
                    factory.live().stats().record(DiagEvent.of(now, DiagEvent.Level.WARN,
                            DiagEvent.Category.STOR, null, null, "仓库解析异常：" + afterError));
                }
            }
            DiagService.tick(this.server);
        }

        if (this.tickCounter % this.ioInterval == 0) {
            long start = System.nanoTime();
            this.machineRunner.tick(this.server, this.manager, this.observations);
            DiagService.recordIoCost(System.nanoTime() - start);
        }

        if (this.tickCounter % HEARTBEAT_INTERVAL == 0) {
            IdleFactory.LOGGER.trace("Idle Factory heartbeat: {} factory instance(s), tick {}",
                    this.manager.all().size(), this.tickCounter);
        }
    }

    /** @return 当前 IO 间隔（tick） */
    int ioInterval() {
        return this.ioInterval;
    }

    /**
     * 设置 IO 间隔。
     *
     * @param ticks 间隔，会被夹到 {@link #MIN_IO_INTERVAL}..{@link #MAX_IO_INTERVAL}
     */
    void setIoInterval(int ticks) {
        this.ioInterval = Math.max(MIN_IO_INTERVAL, Math.min(MAX_IO_INTERVAL, ticks));
    }

    /**
     * 记一条仓库状态迁移。
     *
     * @param factory 工厂
     * @param now     当前游戏时间
     */
    private static void recordStorage(Factory factory, long now) {
        boolean online = factory.live().storageOnline();
        factory.live().stats().record(DiagEvent.of(now,
                online ? DiagEvent.Level.OK : DiagEvent.Level.WARN,
                DiagEvent.Category.STOR, null, null,
                online ? "仓库上线" : "仓库离线"));
    }
}
