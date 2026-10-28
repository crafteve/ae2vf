package io.github.crafteve.idlefactory;

import io.github.crafteve.idlefactory.client.ModKeyMappings;
import io.github.crafteve.idlefactory.client.UiConfig;
import io.github.crafteve.idlefactory.client.screen.FactoryScreen;
import io.github.crafteve.idlefactory.item.FactoryTerminalItem;
import io.github.crafteve.idlefactory.network.payload.OpenFactoryGuiC2S;
import io.github.crafteve.idlefactory.registry.ModMenus;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.network.PacketDistributor;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = IdleFactory.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = IdleFactory.MODID, value = Dist.CLIENT)
public class IdleFactoryClient {
    public IdleFactoryClient(ModContainer container) {
        // 排序偏好是纯客户端视图状态，写进 config/idlefactory-client.toml；
        // 服务端不加载这份配置。
        container.registerConfig(ModConfig.Type.CLIENT, UiConfig.SPEC);
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
     * 每 tick 检查快捷键：玩家身上有终端才发请求。
     *
     * <p>界面打开时只清空按键队列，不触发；服务端收到请求后会再检查一次背包，
     * 防止客户端伪造。</p>
     *
     * @param event 客户端 tick 后事件
     */
    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.player == null) {
            // 界面里不触发，但要把排队点击吃掉，避免关界面后补开。
            while (ModKeyMappings.OPEN_TERMINAL.consumeClick()) {
                // 只清队列
            }
            return;
        }
        while (ModKeyMappings.OPEN_TERMINAL.consumeClick()) {
            if (FactoryTerminalItem.isCarriedBy(minecraft.player)) {
                PacketDistributor.sendToServer(new OpenFactoryGuiC2S());
            }
        }
    }

    /**
     * 把工厂工作台的 MenuType 与界面绑起来。
     *
     * <p>M0 只有这一个界面；后续新增界面都往这里加。</p>
     */
    @SubscribeEvent
    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.FACTORY.get(), FactoryScreen::new);
    }
}
