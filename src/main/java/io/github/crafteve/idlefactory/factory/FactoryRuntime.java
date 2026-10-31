package io.github.crafteve.idlefactory.factory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.diag.DiagService;
import io.github.crafteve.idlefactory.factory.io.DiagEvent;
import io.github.crafteve.idlefactory.factory.io.MachineRunner;
import io.github.crafteve.idlefactory.factory.machine.MachineRef;
import io.github.crafteve.idlefactory.factory.probe.Health;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;
import io.github.crafteve.idlefactory.factory.links.LinkRegistry;
import io.github.crafteve.idlefactory.factory.storage.ContainerAnchor;
import io.github.crafteve.idlefactory.factory.storage.ContainerRef;
import io.github.crafteve.idlefactory.factory.storage.ContainerStorageResolver;
import io.github.crafteve.idlefactory.factory.storage.DispatchingStorageResolver;
import io.github.crafteve.idlefactory.factory.storage.StorageResolver;
import io.github.crafteve.idlefactory.integration.ae2.Ae2StorageResolver;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
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
    /** 同一种失联/恢复通知的最短间隔（tick）：10 秒。 */
    private static final int NOTICE_INTERVAL = 200;
    /** 仓库状态刷新间隔（tick）：每秒重新解析一次，别让 online 变成启动时的快照。 */
    private static final int REFRESH_INTERVAL = 20;
    /** IO 执行间隔（tick）的下限。 */
    public static final int MIN_IO_INTERVAL = 1;
    /** IO 执行间隔（tick）的上限。 */
    public static final int MAX_IO_INTERVAL = 20;

    private static FactoryRuntime instance;

    private final MinecraftServer server;
    private final FactoryManager manager = new FactoryManager();
    private final MachineRunner machineRunner = new MachineRunner();
    /**
     * 全服占用索引：统一 id → 认领。
     *
     * <p>它只是真相的索引，不是真相本身——真相永远在各工厂的记录里，索引只负责让门禁
     * 查得快。每次档案变化就全量重建一次（O(总数)，只发生在配置变更时），所以它和档案
     * 不可能打架：要么一起新，要么一起旧。</p>
     */
    private final LinkRegistry links = new LinkRegistry();
    /** 每 tick 复用的「被引用位置集合」，避免反复分配。 */
    private final Set<BlockKey> observedKeys = new HashSet<>();
    /** 本 tick 累计的「失联」与「恢复」数量，按工厂分组；actionbar 用。 */
    private final Map<UUID, Integer> missingNotices = new HashMap<>();
    private final Map<UUID, Integer> restoredNotices = new HashMap<>();
    /** 每个工厂上一次弹 actionbar 的时刻，用来节流。 */
    private final Map<UUID, Long> lastNoticeTick = new HashMap<>();
    private FactoryStore store;
    @Nullable
    private ObservationService observations;
    /** 仓库解析器：启动时创建，供工厂定时刷新与指令即时刷新共用。 */
    @Nullable
    private StorageResolver storageResolver;
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
        runtime.machineRunner.clear();
        DiagService.clearWatches();
        runtime.storageResolver = null;
        runtime.observations = null;
        runtime.observedKeys.clear();
        runtime.missingNotices.clear();
        runtime.restoredNotices.clear();
        runtime.lastNoticeTick.clear();
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

    /** 仓库解析器；启动完成后非 null。 */
    @Nullable
    public StorageResolver storageResolver() {
        return this.storageResolver;
    }

    /**
     * 全服占用索引。
     *
     * <p>门禁查它，对账读它；写它的只有 {@link #markDirty} 和 {@link #load}，都是全量重建，
     * 所以调用方永远不用担心“改了档案忘改索引”。</p>
     */
    public LinkRegistry links() {
        return this.links;
    }

    /** IO 执行器。 */
    public MachineRunner machineRunner() {
        return this.machineRunner;
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
     *
     * <p>顺手把占用索引全量重建一遍：所有改配置的入口（绑定、解绑、删线、删工厂……）最终都
     * 走到这里，所以这里是唯一的同步点——以后新增修改路径，只要它调了 {@code markDirty}，
     * 索引就自动是新的，不存在“改了档案忘改索引”这种漏法。重建是 O(总数)，只发生在人手
     * 频率的配置变更时，tick 里不走这里。</p>
     */
    public void markDirty() {
        save();
        this.links.rebuild(this.manager);
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
        // 观测先于仓库解析：容器仓库要借观测做面窄化与可用性判断
        this.observations = new ObservationService(this.server);
        this.storageResolver = new DispatchingStorageResolver(
                new Ae2StorageResolver(this.server),
                new ContainerStorageResolver(this.server, this.observations));
        this.manager.loadFrom(this.store.records());
        this.links.rebuild(this.manager);
        for (Factory factory : this.manager.all()) {
            factory.refreshStorage(this.storageResolver);
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

    /**
     * 观测一轮：重建被引用的位置集合，交给观测服务，再消费它吐出的状态迁移。
     *
     * <p>集合每 tick 重建，因为配置随时可能变（接入、断开、删线）。规模是「机器总数」，
     * 百台级下这点分配可以忽略；真要省再上脏标记。</p>
     *
     * <p>故意从工厂记录拼、不从占用表拼：表键是不可逆的统一 id，反解坐标还得回工厂拿——
     * 绕一圈回到原点。不如直接遍历源头，零重复数据。占用表专职门禁，引用集专职观测，
     * 各干各的。</p>
     */
    private void observe() {
        if (this.observations == null) {
            return;
        }
        this.observedKeys.clear();
        for (Factory factory : this.manager.all()) {
            for (var machine : factory.config().machines().all()) {
                this.observedKeys.add(machine.ref().blockKey());
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
        this.flushNotices();
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
            var machine = factory.config().machines()
                    .byPosition(transition.key().dimension(), transition.key().pos());
            if (machine == null) {
                continue;
            }
            MachineRef ref = machine.ref();
            switch (transition.to()) {
                case MISSING, NO_DIMENSION -> {
                    if (factory.live().markMachineMissing(ref.id())) {
                        factory.live().stats().record(DiagEvent.of(now,
                                DiagEvent.Level.ERROR, DiagEvent.Category.MACH, ref.id(),
                                null, transition.to().describe() + "，机器仍留在机器库"));
                        this.missingNotices.merge(factory.id(), 1, Integer::sum);
                    }
                }
                case PRESENT -> {
                    boolean wasMissing = factory.live().clearMachineMissing(ref.id());
                    if (transition.from() != Health.UNKNOWN) {
                        factory.live().stats().record(DiagEvent.of(now, DiagEvent.Level.OK,
                                DiagEvent.Category.MACH, ref.id(), null, "机器已恢复"));
                        this.wakeMachine(factory, ref.id());
                        if (wasMissing) {
                            this.restoredNotices.merge(factory.id(), 1, Integer::sum);
                        }
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
     * 把本 tick 累计的失联/恢复数量弹成 actionbar。
     *
     * <p>按工厂聚合，且 10 秒内只弹一次——一次爆炸拆掉 20 台机器也只应该看到一行。</p>
     */
    private void flushNotices() {
        if (this.missingNotices.isEmpty() && this.restoredNotices.isEmpty()) {
            return;
        }
        long now = this.server.overworld().getGameTime();
        Set<UUID> factoryIds = new HashSet<>();
        factoryIds.addAll(this.missingNotices.keySet());
        factoryIds.addAll(this.restoredNotices.keySet());
        for (UUID factoryId : factoryIds) {
            Factory factory = this.manager.byId(factoryId).orElse(null);
            if (factory == null) {
                this.missingNotices.remove(factoryId);
                this.restoredNotices.remove(factoryId);
                continue;
            }
            ServerPlayer player = this.server.getPlayerList().getPlayer(factory.config().owner());
            if (player == null) {
                // 拥有者不在线，通知没有意义，计数直接丢
                this.missingNotices.remove(factoryId);
                this.restoredNotices.remove(factoryId);
                continue;
            }
            Long last = this.lastNoticeTick.get(factoryId);
            if (last != null && now - last < NOTICE_INTERVAL) {
                // 节流：计数留着，攒到下一次
                continue;
            }
            int missing = this.missingNotices.getOrDefault(factoryId, 0);
            int restored = this.restoredNotices.getOrDefault(factoryId, 0);
            if (missing > 0) {
                player.displayClientMessage(FactoryMessages.warn(
                        missing + " 台机器失联 · /idf factory info"), true);
            } else if (restored > 0) {
                player.displayClientMessage(FactoryMessages.ok(
                        restored + " 台机器回来了 · 规则已恢复"), true);
            }
            this.missingNotices.remove(factoryId);
            this.restoredNotices.remove(factoryId);
            this.lastNoticeTick.put(factoryId, now);
        }
    }

    private void wakeMachine(Factory factory, UUID machineId) {
        this.machineRunner.wake(machineId);
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
