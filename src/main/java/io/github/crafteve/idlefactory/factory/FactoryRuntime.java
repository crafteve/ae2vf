package io.github.crafteve.idlefactory.factory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.io.LineRunner;
import io.github.crafteve.idlefactory.factory.line.PassiveLine;
import io.github.crafteve.idlefactory.factory.storage.StorageLink;
import io.github.crafteve.idlefactory.factory.target.MachineChecks;
import io.github.crafteve.idlefactory.factory.target.MachineTarget;
import io.github.crafteve.idlefactory.integration.ae2.Ae2StorageResolver;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 工厂总调度室：绑定在服务器 tick 上的常驻服务。
 *
 * <p>它是工厂生命周期的唯一入口：服务器启动时读档案、服务器停止时写档案、每 tick 推进所有
 * 工厂。它不认识终端、玩家会话或 GUI——终端关掉、玩家下线，它照跑。</p>
 *
 * <p>第一版只做「加载 / 保存 / 每 100 tick 打一条心跳日志」，验证工厂确实活在 tick 上。
 * 搬运逻辑等 {@code factory/io} 重写后再接进来。</p>
 */
@EventBusSubscriber(modid = IdleFactory.MODID)
public final class FactoryRuntime {
    /** 心跳日志间隔（tick）。 */
    private static final int HEARTBEAT_INTERVAL = 100;
    /** 仓库状态刷新间隔（tick）：每秒重新解析一次，别让 online 变成启动时的快照。 */
    private static final int REFRESH_INTERVAL = 20;
    /** 机器库对账间隔（tick）：每 5 秒清一次悬空引用。 */
    private static final int RECONCILE_INTERVAL = 100;
    /** IO 执行间隔（tick）：每 5 tick 一轮，参考 AE2 bus 的 tickRate。 */
    private static final int IO_INTERVAL = 5;

    private static FactoryRuntime instance;

    private final MinecraftServer server;
    private final FactoryManager manager = new FactoryManager();
    private final LineRunner lineRunner = new LineRunner();
    private FactoryStore store;
    private int tickCounter;

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
        StorageLink.setResolver(null);
        instance = null;
    }

    /** @return 当前运行时；服务端未启动时为 {@code null} */
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

    /** 服务端实例。 */
    public MinecraftServer server() {
        return this.server;
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
        StorageLink.setResolver(new Ae2StorageResolver(this.server));
        this.manager.loadFrom(this.store.records());
        for (Factory factory : this.manager.all()) {
            factory.refreshStorage();
        }
        IdleFactory.LOGGER.info("Idle Factory: {} factory instance(s) loaded", this.manager.all().size());
    }

    /**
     * 每 tick 推进一次。第一版只刷新仓库状态、打心跳日志。
     */
    public void tick() {
        this.tickCounter++;
        if (this.tickCounter % REFRESH_INTERVAL == 0) {
            for (Factory factory : this.manager.all()) {
                factory.refreshStorage();
            }
        }
        if (this.tickCounter % RECONCILE_INTERVAL == 0) {
            for (Factory factory : this.manager.all()) {
                reconcileMachines(factory);
            }
        }
        if (this.tickCounter % IO_INTERVAL == 0) {
            this.lineRunner.tick(this.server, this.manager);
        }
        if (this.tickCounter % HEARTBEAT_INTERVAL == 0) {
            IdleFactory.LOGGER.debug("Idle Factory heartbeat: {} factory instance(s), tick {}",
                    this.manager.all().size(), this.tickCounter);
        }
    }

    /**
     * 对账：把「方块已经不在了」的机器从机器库移除。
     *
     * <p>只对已加载区块判断——没加载不代表机器没了，删了会把玩家没到过的产线清空。
     * 玩家破坏、爆炸、活塞、{@code /setblock} 造成的消失都能被这条兜底。</p>
     *
     * @param factory 工厂
     */
    private void reconcileMachines(Factory factory) {
        List<UUID> stale = new ArrayList<>();
        for (MachineTarget target : factory.machines().all()) {
            ServerLevel level = this.server.getLevel(target.dimension());
            if (level == null) {
                stale.add(target.id());
                continue;
            }
            if (!level.isLoaded(target.pos())) {
                continue;
            }
            if (!MachineChecks.isSupported(level, target.pos())) {
                stale.add(target.id());
            }
        }
        if (stale.isEmpty()) {
            return;
        }
        for (UUID id : stale) {
            factory.machines().remove(id);
        }
        for (PassiveLine line : factory.lines().all()) {
            line.removeGroupsFor(stale);
        }
        markDirty();
        IdleFactory.LOGGER.info("Idle Factory: removed {} stale machine(s)", stale.size());
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
