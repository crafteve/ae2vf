package io.github.crafteve.idlefactory;

import io.github.crafteve.idlefactory.factory.FactoryLifecycle;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 服务端根装配：把服务器事件接到工厂生命周期与每 tick 编排上。
 *
 * <p>它只做转发，不写业务判断：启动读档、停止落盘、每 tick 推进、玩家下线清提交序号，
 * 具体实现全在 {@link FactoryLifecycle}。这样事件注册只有一处，生命周期类保持可测试。</p>
 */
@EventBusSubscriber(modid = IdleFactory.MODID)
public final class IdleFactoryServer {
    private IdleFactoryServer() {
        // 纯静态事件处理
    }

    /**
     * 服务器启动：读档并装配。
     *
     * @param event 启动事件
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        FactoryLifecycle.start(event.getServer());
    }

    /**
     * 世界保存之前：整份落盘，然后拆掉虚拟节点。
     *
     * <p>这是唯一可靠的写盘时机：{@code stopServer} 会先 {@code saveEverything()} 再发停止完成
     * 事件，等后者再标脏就没人写盘了。</p>
     *
     * @param event 停止前事件
     */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        FactoryLifecycle.stopAndSave();
    }

    /**
     * 服务器已停止：丢掉内存状态与监听器。
     *
     * @param event 停止完成事件
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        FactoryLifecycle.stop();
    }

    /**
     * 每 tick 推进一次。
     *
     * @param event tick 事件
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        FactoryLifecycle runtime = FactoryLifecycle.get();
        if (runtime != null) {
            runtime.tick();
        }
    }

    /**
     * 玩家下线：清掉他的提交序号，避免换号后旧序号挡住新提交。
     *
     * @param event 下线事件
     */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        FactoryLifecycle runtime = FactoryLifecycle.get();
        if (runtime != null) {
            runtime.forgetCommitSeq(event.getEntity().getUUID());
        }
    }
}
