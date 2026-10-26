package io.github.crafteve.idlefactory.factory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.diag.DiagEvent;
import io.github.crafteve.idlefactory.factory.diag.DiagService;
import io.github.crafteve.idlefactory.factory.io.LineRunner;
import io.github.crafteve.idlefactory.factory.line.PassiveLine;
import io.github.crafteve.idlefactory.factory.machine.MachineRef;
import io.github.crafteve.idlefactory.factory.observation.Health;
import io.github.crafteve.idlefactory.factory.observation.ObservationKey;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;
import io.github.crafteve.idlefactory.factory.storage.ContainerStorageResolver;
import io.github.crafteve.idlefactory.factory.storage.DispatchingStorageResolver;
import io.github.crafteve.idlefactory.factory.storage.StorageLink;
import io.github.crafteve.idlefactory.integration.ae2.Ae2StorageResolver;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 工厂总调度室：绑定在服务器 tick 上的常驻服务。
 *
 * <p>它是工厂生命周期的唯一入口：服务器启动时读档案、服务器停止时写档案、每 tick 推进所有工厂。
 * 它不认识终端、玩家会话或 GUI——终端关掉、玩家下线，它照跑。</p>
 *
 * <p>每 tick 的顺序是固定的：</p>
 * <ol>
 *   <li><b>观测</b>：把「被引用的位置集合」交给 {@link ObservationService}，再消费它吐出的状态迁移；</li>
 *   <li><b>仓库</b>：每 20 tick 重新解析一次地址，把上线/离线记进事件流；</li>
 *   <li><b>IO</b>：按 IO 间隔跑一轮搬运；</li>
 *   <li><b>心跳</b>：每 100 tick 打一条 trace 日志。</li>
 * </ol>
 *
 * <p>观测必须先于 IO：IO 的门禁读的是观测结论，读路径不许自己探世界。</p>
 */
@EventBusSubscriber(modid = IdleFactory.MODID)
public final class FactoryRuntime {
    /** 心跳日志间隔（tick）。 */
    private static final int HEARTBEAT_INTERVAL = 100;
    /** 仓库状态刷新间隔（tick）：每秒重新解析一次，别让 online 变成启动时的快照。 */
    private static final int REFRESH_INTERVAL = 20;
    /** IO 执行间隔（tick）的下限。 */
    public static final int MIN_IO_INTERVAL = 1;
    /** IO 执行间隔（tick）的上限。 */
    public static final int MAX_IO_INTERVAL = 20;

    private static FactoryRuntime instance;

    private final MinecraftServer server;
    private final FactoryManager manager = new FactoryManager();
    private final LineRunner lineRunner = new LineRunner();
    /** 每 tick 复用的「被引用位置集合」，避免反复分配。 */
    private final Set<ObservationKey> observedKeys = new HashSet<>();
    private FactoryStore store;
    @Nullable
    private ObservationService observations;
    private int tickCounter;
    /** IO 执行间隔（tick），默认每 tick 一轮；可由 /idf diag io 调整。 */
    private int ioInterval = 1;

    private FactoryRuntime(MinecraftServer server) {
        this.server = server;
    }

    /**
     * 服务器启动时调一次。
     *
     * @param server 服务端实例
     */
    public static void start(MinecraftServer server) {
        if (instance != null) {
            return;
        }
        FactoryRuntime runtime = new FactoryRuntime(server);
        runtime.load();
        instance = runtime;
    }

    /** 服务器停止时调一次，把档案写回存档并丢掉内存状态。 */
    public static void stop() {
        FactoryRuntime runtime = instance;
        if (runtime == null) {
            return;
        }
        runtime.save();
        runtime.lineRunner.clear();
        DiagService.clearWatches();
        StorageLink.setResolver(null);
        runtime.observations = null;
        runtime.observedKeys.clear();
        instance = null;
    }

    /** @return 当前运行时；服务端未启动时为 {@code null} */
    @Nullable
    public static FactoryRuntime get() {
        return instance;
    }

    /** 档案柜。 */
    public FactoryManager manager() {
        return this.manager;
    }

    /** IO 执行器。 */
    public LineRunner lineRunner() {
        return this.lineRunner;
    }

    /** 观测服务；服务端未就绪时为 {@code null}。 */
    @Nullable
    public ObservationService observations() {
        return this.observations;
    }

    /** 服务端实例。 */
    public MinecraftServer server() {
        return this.server;
    }

    /** 当前 IO 间隔（tick）。 */
    public int ioInterval() {
        return this.ioInterval;
    }

    /**
     * 设置 IO 间隔。
     *
     * @param ticks 间隔，会被夹到 {@link #MIN_IO_INTERVAL}..{@link #MAX_IO_INTERVAL}
     */
    public void setIoInterval(int ticks) {
        this.ioInterval = Math.max(MIN_IO_INTERVAL, Math.min(MAX_IO_INTERVAL, ticks));
    }

    /**
     * 标记档案已变化，立即同步进存档容器。
     *
     * <p>不能只 {@code setDirty()}：{@code FactoryStore.save()} 写的是它自己的 {@code records}，
     * 而 {@code records} 只在 {@code setRecords(...)} 时更新。先同步，再让存档去写盘。</p>
     */
    public void markDirty() {
        save();
    }

    /**
     * 取下一个工厂序号，用于生成确定性 id。
     *
     * @return 严格递增的序号；运行时未就绪时为 {@code 0}
     */
    public long takeNextSeq() {
        return this.store == null ? 0L : this.store.takeNextSeq();
    }

    /**
     * 把内存里的工厂导出到存档容器。停止时调一次；频繁调也没关系，{@code SavedData} 只在
     * 存档时真正写盘。
     */
    public void save() {
        if (this.store != null) {
            this.store.setRecords(this.manager.toRecords());
        }
    }

    private void load() {
        this.store = FactoryStore.get(this.server.overworld());
        StorageLink.setResolver(new DispatchingStorageResolver(
                new Ae2StorageResolver(this.server),
                new ContainerStorageResolver(this.server)));
        this.observations = new ObservationService(this.server);
        this.manager.loadFrom(this.store.records());
        for (Factory factory : this.manager.all()) {
            factory.refreshStorage();
        }
        IdleFactory.LOGGER.info("Idle Factory: {} factory instance(s) loaded", this.manager.all().size());
    }

    /**
     * 每 tick 推进一次。
     *
     * <p>观测 → 仓库 → IO → 心跳。观测一定在最前：IO 的门禁读的是观测结论。</p>
     */
    public void tick() {
        this.tickCounter++;

        this.observe();

        if (this.tickCounter % REFRESH_INTERVAL == 0) {
            long now = this.server.overworld().getGameTime();
            for (Factory factory : this.manager.all()) {
                String beforeError = factory.storageLink().lastError();
                if (factory.refreshStorage()) {
                    recordStorage(factory, now);
                }
                String afterError = factory.storageLink().lastError();
                if (afterError != null && !afterError.equals(beforeError)) {
                    factory.stats().record(DiagEvent.of(now, DiagEvent.Level.WARN,
                            DiagEvent.Category.STOR, null, null, null, "仓库解析异常：" + afterError));
                }
            }
            DiagService.tick(this.server);
        }

        if (this.tickCounter % this.ioInterval == 0) {
            long start = System.nanoTime();
            this.lineRunner.tick(this.server, this.manager, this.observations);
            DiagService.recordIoCost(System.nanoTime() - start);
        }

        if (this.tickCounter % HEARTBEAT_INTERVAL == 0) {
            IdleFactory.LOGGER.trace("Idle Factory heartbeat: {} factory instance(s), tick {}",
                    this.manager.all().size(), this.tickCounter);
        }
    }

    /**
     * 观测一轮：重建被引用的位置集合，交给观测服务，再消费它吐出的状态迁移。
     *
     * <p>集合每 tick 重建，因为配置随时可能变（接入、断开、删线）。规模是「机器总数」，
     * 百台级下这点分配可以忽略；真要省再上脏标记。</p>
     */
    private void observe() {
        if (this.observations == null) {
            return;
        }
        this.observedKeys.clear();
        for (Factory factory : this.manager.all()) {
            for (MachineRef ref : factory.machines().all()) {
                this.observedKeys.add(ObservationKey.of(ref));
            }
        }
        this.observations.tick(this.observedKeys);
        for (ObservationService.Transition transition : this.observations.drainTransitions()) {
            this.handleTransition(transition);
        }
    }

    /**
     * 消费一次状态迁移：记事件，必要时唤醒产线。
     *
     * <p>{@link Health#UNLOADED} 刻意不进事件流——玩家走两步就会触发一片，会把 {@code tail} 淹掉。
     * 未加载只在 {@code diag observe} 里看得到。</p>
     *
     * @param transition 迁移
     */
    private void handleTransition(ObservationService.Transition transition) {
        long now = this.server.overworld().getGameTime();
        for (Factory factory : this.manager.all()) {
            MachineRef ref = factory.machines()
                    .byPosition(transition.key().dimension(), transition.key().pos());
            if (ref == null) {
                continue;
            }
            switch (transition.to()) {
                case MISSING, NO_DIMENSION -> factory.stats().record(DiagEvent.of(now,
                        DiagEvent.Level.ERROR, DiagEvent.Category.MACH, null, ref.id(), null,
                        transition.to().describe() + "，已进回收站"));
                case PRESENT -> {
                    if (transition.from() != Health.UNKNOWN) {
                        factory.stats().record(DiagEvent.of(now, DiagEvent.Level.OK,
                                DiagEvent.Category.MACH, null, ref.id(), null, "机器已恢复"));
                        this.wakeLines(factory, ref.id());
                    }
                }
                default -> {
                    // UNLOADED / UNKNOWN 不记
                }
            }
        }
    }

    private void wakeLines(Factory factory, UUID machineId) {
        for (PassiveLine line : factory.lines().all()) {
            if (line.groupByMachine(machineId).isPresent()) {
                this.lineRunner.wake(line.id());
            }
        }
    }

    /**
     * 记一条仓库状态迁移。
     *
     * @param factory 工厂
     * @param now     当前游戏时间
     */
    private static void recordStorage(Factory factory, long now) {
        boolean online = factory.storageLink().online();
        factory.stats().record(DiagEvent.of(now,
                online ? DiagEvent.Level.OK : DiagEvent.Level.WARN,
                DiagEvent.Category.STOR, null, null, null,
                online ? "仓库上线" : "仓库离线"));
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        start(event.getServer());
    }

    /**
     * 世界保存之前调一次。
     *
     * <p>这是<b>唯一可靠的写盘时机</b>：{@code MinecraftServer#stopServer} 会先
     * {@code saveEverything()} 再发 {@link ServerStoppedEvent}，等后者再标脏就没人写盘了。</p>
     */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        FactoryRuntime runtime = instance;
        if (runtime != null) {
            runtime.save();
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        stop();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        FactoryRuntime runtime = instance;
        if (runtime != null) {
            runtime.tick();
        }
    }
}
