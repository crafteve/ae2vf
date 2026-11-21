package io.github.crafteve.ae2vf.gametest;

import io.github.crafteve.ae2vf.VoidFactory;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * 把数据层测试类注册进 NeoForge 的 GameTest 框架。
 *
 * <p>1.21.1 没有 {@code @GameTestHolder}，测试类必须通过 {@link RegisterGameTestsEvent}
 * 显式登记；新增测试类时在这里加一行。</p>
 */
@EventBusSubscriber(modid = VoidFactory.MODID)
public final class GameTestRegistrar {
    private GameTestRegistrar() {
        // 纯注册入口
    }

    /**
     * 注册全部数据层测试类。
     *
     * @param event 注册事件
     */
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(MultiplierGameTest.class);
        event.register(IoRuleSpecGameTest.class);
        event.register(NodeLibraryGameTest.class);
        event.register(NodeCodecGameTest.class);
    }
}
