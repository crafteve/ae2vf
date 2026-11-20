package io.github.crafteve.idlefactory;

import io.github.crafteve.idlefactory.client.screen.terminal.node.NodeScreen;
import io.github.crafteve.idlefactory.client.screen.terminal.passiveio.PassiveIoScreen;
import io.github.crafteve.idlefactory.client.screen.text.WandText;
import io.github.crafteve.idlefactory.client.screen.wand.WandDraftScreen;
import io.github.crafteve.idlefactory.menu.node.NodeTerminalMenu;
import io.github.crafteve.idlefactory.menu.passiveio.PassiveIoTerminalMenu;
import io.github.crafteve.idlefactory.menu.wand.WandDraftMenu;
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
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = IdleFactory.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = IdleFactory.MODID, value = Dist.CLIENT)
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
     * 草稿面板样式路径。
     *
     * <p>{@code StyleManager.loadStyleDoc} 固定按 {@code ae2} 命名空间解析，所以样式文件放在
     * {@code assets/ae2/screens/} 下；面板尺寸与两个按钮的位置都写在那份 json 里，Java 只读。</p>
     */
    private static final String WAND_DRAFT_STYLE = "/screens/idlefactory_wand_draft.json";

    /**
     * 把两个终端菜单、草稿面板和界面绑起来。
     *
     * @param event 菜单屏幕注册事件
     */
    @SubscribeEvent
    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.<PassiveIoTerminalMenu, PassiveIoScreen>register(
                ModMenus.PASSIVE_IO_TERMINAL.get(),
                (PassiveIoTerminalMenu menu, Inventory inventory, Component title) ->
                        new PassiveIoScreen(menu, inventory, title,
                                StyleManager.loadStyleDoc(
                                        "/screens/terminals/base_terminal.json")));
        event.<NodeTerminalMenu, NodeScreen>register(
                ModMenus.NODE_TERMINAL.get(),
                (NodeTerminalMenu menu, Inventory inventory, Component title) ->
                        new NodeScreen(menu, inventory, title,
                                StyleManager.loadStyleDoc(
                                        "/screens/terminals/base_terminal.json")));
        event.<WandDraftMenu, WandDraftScreen>register(
                ModMenus.WAND_DRAFT.get(),
                (WandDraftMenu menu, Inventory inventory, Component title) ->
                        new WandDraftScreen(menu, inventory, WandText.panelTitle(),
                                StyleManager.loadStyleDoc(WAND_DRAFT_STYLE)));
    }
}