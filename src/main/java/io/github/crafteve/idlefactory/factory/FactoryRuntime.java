package io.github.crafteve.idlefactory.factory;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.diag.DiagService;
import io.github.crafteve.idlefactory.factory.io.DiagEvent;
import io.github.crafteve.idlefactory.factory.io.NodeIoRunner;
import io.github.crafteve.idlefactory.factory.links.LinkRegistry;
import io.github.crafteve.idlefactory.factory.node.NodeManager;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;
import io.github.crafteve.idlefactory.factory.storage.ContainerStorageResolver;
import io.github.crafteve.idlefactory.factory.storage.DispatchingStorageResolver;
import io.github.crafteve.idlefactory.factory.storage.StorageResolver;
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
 * <p>它是工厂生命周期的唯一入口：服务器启动时读档案、服务器停止时写档案、每 tick 推进
 * 所有工厂。它不认识终端、玩家会话或 GUI——终端关掉、玩家下线，它照跑。</p>
 *
 * <p>本类只做装配与生命周期：tick 编排在 {@link FactoryTickRunner}，观测与迁移消费在
 * {@link FactoryObservationRunner}，失联通知在 {@link FactoryNoticeHandler}，虚拟节点
 * 对账在 {@link FactoryNodeRunner}。这里保留的 getter 是对外兼容入口，新代码应通过
 * 构造注入拿到需要的协作者。</p>
 *
 * <p>观测必须先于 IO：IO 的门禁读的是观测结论，读路径不许自己探世界。</p>
 */
@EventBusSubscriber(modid = IdleFactory.MODID)
public final class FactoryRuntime {
    /** IO 执行间隔（tick）的下限。 */
    public static final int MIN_IO_INTERVAL = FactoryTickRunner.MIN_IO_INTERVAL;
    /** IO 执行间隔（tick）的上限。 */
    public static final int MAX_IO_INTERVAL = FactoryTickRunner.MAX_IO_INTERVAL;

    private static FactoryRuntime instance;

    private final MinecraftServer server;
    private final FactoryManager manager = new FactoryManager();
    private final NodeIoRunner nodeIoRunner = new NodeIoRunner();
    /**
     * 全服占用索引：统一 id → 认领。
     *
     * <p>它只是真相的索引，不是真相本身——真相永远在各工厂的记录里，索引只负责让门禁
     * 查得快。每次档案变化就全量重建一次（O(总数)，只发生在配置变更时），所以它和档案
     * 不可能打架：要么一起新，要么一起旧。</p>
     */
    private final LinkRegistry links = new LinkRegistry();
    /** 失联/恢复通知的聚合与节流。 */
    private final FactoryNoticeHandler notices = new FactoryNoticeHandler();
    /** 虚拟机器节点：对账、分帧创建、每 tick 推进。 */
    private final NodeManager nodeManager;
    /** 每 tick 编排器；启动完成（load）后非 null。 */
    @Nullable
    private FactoryTickRunner tickRunner;
    private FactoryStore store;
    @Nullable
    private ObservationService observations;
    /** 仓库解析器：启动时创建，供工厂定时刷新与指令即时刷新共用。 */
    @Nullable
    private StorageResolver storageResolver;

    private FactoryRuntime(MinecraftServer server) {
        this.server = server;
        this.nodeManager = new NodeManager(server, this::markDirty, this::recordNodeNotice);
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
        runtime.nodeManager.destroyAll();
        runtime.nodeIoRunner.clear();
        DiagService.clearWatches();
        runtime.storageResolver = null;
        runtime.observations = null;
        runtime.tickRunner = null;
        runtime.notices.clear();
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
    public NodeIoRunner nodeIoRunner() {
        return this.nodeIoRunner;
    }

    /** 观测服务；服务端未就绪时为 {@code null}。 */
    @Nullable
    public ObservationService observations() {
        return this.observations;
    }

    /** 虚拟机器节点管理器。 */
    public NodeManager nodeManager() {
        return this.nodeManager;
    }

    /** 服务端实例。 */
    public MinecraftServer server() {
        return this.server;
    }

    /** 当前 IO 间隔（tick）。 */
    public int ioInterval() {
        return this.tickRunner == null ? 1 : this.tickRunner.ioInterval();
    }

    /**
     * 设置 IO 间隔。
     *
     * @param ticks 间隔，会被夹到 {@link #MIN_IO_INTERVAL}..{@link #MAX_IO_INTERVAL}
     */
    public void setIoInterval(int ticks) {
        if (this.tickRunner != null) {
            this.tickRunner.setIoInterval(ticks);
        }
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
        // 配置变了：让虚拟节点下一 tick 重新对一次账（该建的建、该拆的拆）
        this.nodeManager.requestReconcile();
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
        this.observations = new ObservationService(this.server,
                FactoryLimitsConfig::observationBlockBudgetPerTick);
        this.storageResolver = new DispatchingStorageResolver(
                new Ae2StorageResolver(this.server),
                new ContainerStorageResolver(this.server, this.observations));
        this.manager.loadFrom(this.store.records());
        this.links.rebuild(this.manager);
        for (Factory factory : this.manager.all()) {
            factory.refreshStorage(this.storageResolver);
        }
        FactoryObservationRunner observationRunner = new FactoryObservationRunner(
                this.server, this.manager, this.observations, this.nodeIoRunner,
                this.nodeManager, this.notices, this::markDirty);
        FactoryNodeRunner nodeRunner = new FactoryNodeRunner(
                this.server, this.manager, this.observations, this.nodeManager);
        this.tickRunner = new FactoryTickRunner(this.server, this.manager, this.nodeIoRunner,
                this.observations, this.storageResolver, observationRunner, nodeRunner);
        IdleFactory.LOGGER.info("Idle Factory: {} factory instance(s) loaded", this.manager.all().size());
    }

    /** 每 tick 推进一次；实际编排在 {@link FactoryTickRunner}。 */
    public void tick() {
        if (this.tickRunner != null) {
            this.tickRunner.tick();
        }
    }

    /**
     * 把节点告警写进所属工厂的事件流；工厂已经没了就只记日志。
     *
     * @param notice 节点告警
     */
    private void recordNodeNotice(NodeManager.Notice notice) {
        Factory factory = this.manager.byId(notice.factoryId()).orElse(null);
        if (factory == null) {
            IdleFactory.LOGGER.warn("Idle Factory: 节点告警来自不存在的工厂 [{}]：{}",
                    notice.nodeId(), notice.message());
            return;
        }
        long now = this.server.overworld().getGameTime();
        factory.live().stats().record(DiagEvent.of(now, DiagEvent.Level.WARN,
                DiagEvent.Category.MACH, null, notice.nodeId(), null, notice.message()));
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
            // 写盘之后再拆节点：拆节点不会再改数据，但可能碰到已经被拆掉的网络
            runtime.nodeManager().destroyAll();
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
