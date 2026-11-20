package io.github.crafteve.ae2vf.factory;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.ae2vf.command.CommitSeqStore;
import io.github.crafteve.ae2vf.IdleFactory;

import io.github.crafteve.ae2vf.io.IoForm;

import io.github.crafteve.ae2vf.io.IoRunner;

import io.github.crafteve.ae2vf.io.PortExecutor;

import io.github.crafteve.ae2vf.claim.BlockOccupancyTable;

import io.github.crafteve.ae2vf.runtime.LiveNodeApply;
import io.github.crafteve.ae2vf.runtime.LiveNodeLibrary;
import io.github.crafteve.ae2vf.runtime.LiveNodeRunner;

import io.github.crafteve.ae2vf.observation.ObservationReader;
import io.github.crafteve.ae2vf.observation.ObservationRunner;
import io.github.crafteve.ae2vf.observation.WorldEventListener;

import io.github.crafteve.ae2vf.storage.MeStorageResolver;

import io.github.crafteve.ae2vf.network.ObservationNoticeListener;
import io.github.crafteve.ae2vf.network.ViewPort;
import io.github.crafteve.ae2vf.network.WandLinkPort;

import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 工厂总调度室：绑定在服务器 tick 上的常驻服务。
 *
 * <p>它是工厂生命周期的唯一入口：服务器启动时读档案、服务器停止时写档案、每 tick 推进
 * 所有工厂。它不认识终端、玩家会话或 GUI——终端关掉、玩家下线，它照跑。</p>
 *
 * <p>本类只做装配与生命周期：每 tick 编排在 {@link FactoryRunner}，失联通知在
 * {@link ObservationNoticeListener}。这里保留的 getter 是对外兼容入口，新代码应通过构造注入
 * 拿到需要的协作者。</p>
 *
 * <p>观测必须先于 IO：IO 的门禁读的是观测结论，读路径不许自己探世界。</p>
 */
public final class FactoryLifecycle {
    private static FactoryLifecycle instance;

    private final MinecraftServer server;
    private final FactoryLibrary manager = new FactoryLibrary();
    private final IoRunner nodeIoRunner;
    /**
     * 机器侧端口缓存：全服一份，所有工厂与节点共用。
     *
     * <p>缓存绑在实例上——再 new 一个就是第二份缓存，失效路径会对不上。</p>
     */
    private final PortExecutor ports;
    /**
     * 全服方块占用表：一个位置同一时刻只归一家工厂、一种角色。
     *
     * <p>它只是真相的索引，不是真相本身——真相永远在各工厂的配置里，索引只负责让门禁查得
     * 快。每次配置变化就全量重建一次（O(总数)，只发生在配置变更时），所以它和配置不可能
     * 打架：要么一起新，要么一起旧。</p>
     */
    private final BlockOccupancyTable occupancy = new BlockOccupancyTable();
    /** 失联/恢复通知的聚合与节流。 */
    private final ObservationNoticeListener notices;
    /** 全服机器状态（世界的镜像，不写档）。 */
    /** 每 tick 的 IO 表单生成器。 */
    private final IoFormBuilder formBuilder = new IoFormBuilder();
    /** 虚拟机器节点：对账、分帧创建、每 tick 推进。 */
    private final LiveNodeLibrary nodeLibrary;
    private final LiveNodeApply nodeApply;
    private final LiveNodeRunner nodeRunner;
    /** 启动装配器：读档、建观测、建运行器。 */
    private final FactoryLoader loader;
    /** 每 tick 编排器；启动完成（load）后非 null。 */
    @Nullable
    private FactoryRunner runner;
    private FactoryStore store;
    @Nullable
    private ObservationRunner observationRunner;
    @Nullable
    private ObservationReader observationReader;
    /** 世界事件标脏监听；阶段 2 临时由运行时注册，阶段 4 迁到根装配。 */
    @Nullable
    private WorldEventListener worldEventListener;
    /** 仓库解析器：启动时创建，供工厂定时刷新与指令即时刷新共用。 */
    @Nullable
    private MeStorageResolver storageResolver;
    /** 草稿提交序号窗口：防重放，命令层维护。 */
    private final CommitSeqStore commitSeq = new CommitSeqStore();

    private FactoryLifecycle(MinecraftServer server) {
        this.server = server;
        this.ports = new PortExecutor(server);
        this.notices = new ObservationNoticeListener(server, this.manager);
        this.nodeIoRunner = new IoRunner(this.ports);
        this.nodeLibrary = new LiveNodeLibrary(server, this::markDirty, this::logNodeNotice);
        this.nodeApply = new LiveNodeApply(server, this.ports, this.nodeLibrary);
        this.nodeRunner = new LiveNodeRunner(this.nodeApply, this.nodeLibrary);
        this.loader = new FactoryLoader(server, this.manager, this.occupancy, this.ports,
                this.notices, this.formBuilder, this.nodeIoRunner, this.nodeApply,
                this.nodeRunner);
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
        FactoryLifecycle runtime = new FactoryLifecycle(server);
        runtime.load();
        instance = runtime;
    }

    /** 世界保存之前调一次：整份落盘，再拆虚拟节点（拆节点不会再改数据）。 */
    public static void stopAndSave() {
        FactoryLifecycle runtime = instance;
        if (runtime == null) {
            return;
        }
        runtime.save();
        runtime.nodeApply.destroyAll();
    }

    /** 服务器停止时调一次，把档案写回存档并丢掉内存状态。 */
    public static void stop() {
        FactoryLifecycle runtime = instance;
        if (runtime == null) {
            return;
        }
        runtime.nodeIoRunner.clear();
        runtime.ports.clear();
        runtime.formBuilder.clear();
        runtime.storageResolver = null;
        if (runtime.worldEventListener != null) {
            NeoForge.EVENT_BUS.unregister(runtime.worldEventListener);
            runtime.worldEventListener.clear();
            runtime.worldEventListener = null;
        }
        if (runtime.observationRunner != null) {
            runtime.observationRunner.shutdown();
        }
        runtime.observationRunner = null;
        runtime.observationReader = null;
        runtime.runner = null;
        runtime.notices.clear();
        runtime.commitSeq.clear();
        // 视图出口的会话与差量基线都是静态内存：不跟着实例清掉会跨存档残留。
        ViewPort.clear();
        instance = null;
    }

    /** @return 当前运行时；服务端未启动时为 {@code null} */
    @Nullable
    public static FactoryLifecycle get() {
        return instance;
    }

    /** 档案柜。 */
    public FactoryLibrary manager() {
        return this.manager;
    }

    /**
     * 取一个工厂本 tick 的表单；还没生成过时为 {@code null}。
     *
     * @param factory 工厂
     * @return 表单
     */
    @Nullable
    public IoForm formFor(Factory factory) {
        return this.formBuilder.form(factory.id());
    }

    /** 仓库解析器；启动完成后非 null。 */
    @Nullable
    public MeStorageResolver storageResolver() {
        return this.storageResolver;
    }

    /**
     * 全服占用索引。
     *
     * <p>门禁查它，对账读它；写它的只有 {@link #markDirty} 和 {@link #load}，都是全量重建，
     * 所以调用方永远不用担心“改了档案忘改索引”。</p>
     */
    public BlockOccupancyTable occupancy() {
        return this.occupancy;
    }

    /** IO 编排器。 */
    public IoRunner nodeIoRunner() {
        return this.nodeIoRunner;
    }

    /** 观测只读视图；服务端未就绪时为 {@code null}。 */
    @Nullable
    public ObservationReader observations() {
        return this.observationReader;
    }

    /** 观测调度者；服务端未就绪时为 {@code null}。 */
    @Nullable
    public ObservationRunner observationRunner() {
        return this.observationRunner;
    }

    /** 虚拟机器节点管理器。 */
    public LiveNodeApply nodeManager() {
        return this.nodeApply;
    }

    /** 服务端实例。 */
    public MinecraftServer server() {
        return this.server;
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
     *
     * <p><b>本方法不推链接快照</b>：规则、provider、名字、搬运余量这些写入不影响「节点 / 机器 /
     * 方块」三层结构，推快照只会制造无意义流量。会改变结构（节点增删改、仓库锚点、类型迁移）
     * 的入口改调 {@link #markDirtyLinks(Factory)}。</p>
     */
    public void markDirty() {
        if (this.store != null) {
            for (Factory factory : this.manager.all()) {
                this.store.put(factory);
            }
        }
        this.loader.rebuildOccupancy();
        // 配置变了：让虚拟节点下一 tick 重新对一次账（该建的建、该拆的拆）
        this.nodeApply.requestReconcile();
    }

    /**
     * 标记档案已变化，并把该工厂的链接快照推给在线拥有者。
     *
     * <p>只在会改变快照结构的写入后调用：节点增删改、仓库锚点变更、节点类型迁移、新建工厂。
     * 先走一遍 {@link #markDirty()}，所以落盘、索引重建与节点对账的语义与原来完全一致。</p>
     *
     * @param factory 发生结构变化的工厂
     */
    public void markDirtyLinks(Factory factory) {
        markDirty();
        WandLinkPort.push(this.server, factory);
    }

    /**
     * 玩家已经接受过的最大草稿提交序号。
     *
     * @param playerId 玩家 id
     * @return 最大序号；从未成功提交过时为 {@code 0}
     */
    public long lastAcceptedSeq(UUID playerId) {
        return this.commitSeq.lastAccepted(playerId);
    }

    /**
     * 记录一次成功提交；只前进、不后退。
     *
     * @param playerId 玩家 id
     * @param seq      本次草稿的版本号
     */
    public void acceptCommitSeq(UUID playerId, long seq) {
        this.commitSeq.accept(playerId, seq);
    }

    /**
     * 清掉一个玩家的提交序号；下线时调，避免换号后旧序号挡住新提交。
     *
     * @param playerId 玩家 id
     */
    public void forgetCommitSeq(UUID playerId) {
        this.commitSeq.forget(playerId);
    }

    /**
     * 把内存里的工厂整份导出到存档容器。停止时调一次。
     */
    public void save() {
        if (this.store != null) {
            this.store.flushAll(this.manager);
        }
    }

    private void load() {
        FactoryLoader.Loaded loaded = this.loader.load();
        this.store = loaded.store();
        this.runner = loaded.runner();
        this.observationRunner = loaded.observations();
        this.observationReader = loaded.reader();
        this.storageResolver = loaded.storage();
        this.worldEventListener = loaded.listener();
        this.observationRunner.register(this.notices);
    }

    /** 每 tick 推进一次；实际编排在 {@link FactoryRunner}。 */
    public void tick() {
        if (this.runner == null) {
            return;
        }
        if (this.store != null) {
            this.store.beginTick();
        }
        this.runner.tick();
        this.notices.tick();
        if (this.store != null) {
            this.store.flushRuntime(this.manager, 8);
        }
    }

    /**
     * 把节点告警写进日志。
     *
     * <p>事件流已随指令入口一起删除，节点告警不再有界面出口；这里保证它不会静默丢失。
     * 工厂已经没了的告警仍然按 warn 记，因为它说明节点表和档案脱节了。</p>
     *
     * @param notice 节点告警
     */
    private void logNodeNotice(LiveNodeLibrary.Notice notice) {
        Factory factory = this.manager.byId(notice.factoryId()).orElse(null);
        if (factory == null) {
            IdleFactory.LOGGER.warn("Idle Factory: 节点告警来自不存在的工厂 [{}]：{}",
                    notice.nodeId(), notice.message());
            return;
        }
        IdleFactory.LOGGER.debug("Idle Factory: 节点 {} 告警：{}", notice.nodeId(), notice.message());
    }

}
