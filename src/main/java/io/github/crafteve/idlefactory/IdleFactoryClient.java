package io.github.crafteve.idlefactory;

import io.github.crafteve.idlefactory.client.ModKeyMappings;
import io.github.crafteve.idlefactory.client.screen.FactoryUiConfig;
import io.github.crafteve.idlefactory.client.screen.FactoryScreen;
import io.github.crafteve.idlefactory.registry.ModMenus;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = IdleFactory.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = IdleFactory.MODID, bus = EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT)
public class IdleFactoryClient {
    public IdleFactoryClient(ModContainer container) {
        // 排序偏好是纯客户端视图状态，写进 config/idlefactory-client.toml；
        // 服务端不加载这份配置。
        container.registerConfig(ModConfig.Type.CLIENT, FactoryUiConfig.SPEC);
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        IdleFactory.LOGGER.debug("Idle Factory client setup");
    }

    /**
     * 注册「打开工厂终端」按键，默认 R；玩家可在控制里改。
     *
     * @param event 按键注册事件
     */
    @SubscribeEvent
    static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(ModKeyMappings.OPEN_TERMINAL);
    }

    /**
     * 把旧工作台的 MenuType 和界面绑起来；第二版终端删除后只剩这一个屏幕。
     *
     * @param event 菜单屏幕注册事件
     */
    @SubscribeEvent
    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.FACTORY.get(), FactoryScreen::new);
    }
}
