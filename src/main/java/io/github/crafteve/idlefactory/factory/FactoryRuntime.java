package io.github.crafteve.idlefactory.factory;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.storage.StorageLink;
import io.github.crafteve.idlefactory.integration.ae2.Ae2StorageResolver;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
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

    private static FactoryRuntime instance;

    private final MinecraftServer server;
    private final FactoryManager manager = new FactoryManager();
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

    /** 服务端实例。 */
    public MinecraftServer server() {
        return this.server;
    }

    /**
     * 标记档案已变化，下一次停止时写盘。
     */
    public void markDirty() {
        if (this.store != null) {
            this.store.setDirty();
        }
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
     * 每 tick 推进一次。第一版只打心跳日志。
     */
    public void tick() {
        this.tickCounter++;
        if (this.tickCounter % HEARTBEAT_INTERVAL == 0) {
            IdleFactory.LOGGER.debug("Idle Factory heartbeat: {} factory instance(s), tick {}",
                    this.manager.all().size(), this.tickCounter);
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        start(event.getServer());
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
