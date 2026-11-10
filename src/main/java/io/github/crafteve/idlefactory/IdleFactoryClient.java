package io.github.crafteve.idlefactory;

import io.github.crafteve.idlefactory.client.screen.terminal.FactoryNodeScreen;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryPassiveIoScreen;
import io.github.crafteve.idlefactory.menu.FactoryNodeMenu;
import io.github.crafteve.idlefactory.menu.FactoryPassiveIoMenu;
import io.github.crafteve.idlefactory.registry.ModMenus;

import appeng.client.gui.style.StyleManager;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
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
     * 把两个终端菜单和界面绑起来。
     *
     * @param event 菜单屏幕注册事件
     */
    @SubscribeEvent
    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.<FactoryPassiveIoMenu, FactoryPassiveIoScreen>register(
                ModMenus.PASSIVE_IO_TERMINAL.get(),
                (FactoryPassiveIoMenu menu, Inventory inventory, Component title) ->
                        new FactoryPassiveIoScreen(menu, inventory, title,
                                StyleManager.loadStyleDoc(
                                        "/screens/terminals/base_terminal.json")));
        event.<FactoryNodeMenu, FactoryNodeScreen>register(
                ModMenus.NODE_TERMINAL.get(),
                (FactoryNodeMenu menu, Inventory inventory, Component title) ->
                        new FactoryNodeScreen(menu, inventory, title,
                                StyleManager.loadStyleDoc(
                                        "/screens/terminals/base_terminal.json")));
    }
}
