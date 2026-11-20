package io.github.crafteve.ae2vf.registry;

import io.github.crafteve.ae2vf.IdleFactory;
import io.github.crafteve.ae2vf.menu.node.NodeTerminalHost;
import io.github.crafteve.ae2vf.menu.node.NodeTerminalMenu;
import io.github.crafteve.ae2vf.menu.passiveio.PassiveIoTerminalHost;
import io.github.crafteve.ae2vf.menu.passiveio.PassiveIoTerminalMenu;
import io.github.crafteve.ae2vf.menu.wand.WandDraftMenu;
import io.github.crafteve.ae2vf.menu.wand.WandDraftHost;

import appeng.menu.implementations.MenuTypeBuilder;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 菜单（容器）类型注册表。
 *
 * <p>两个 AE2 终端菜单（被动 IO 配置与节点管理）复用 AE2 的边框与背包槽，不接 ME 库存
 * 菜单；草稿确认面板是不带槽位的对话框菜单，只负责打开与关闭。</p>
 */
public final class IdleFactoryMenus {
    /** 菜单注册表。 */
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, IdleFactory.MODID);

    /** 被动 IO 管理终端：AE2 菜单构建器负责按物品定位器还原宿主。 */
    public static final DeferredHolder<MenuType<?>, MenuType<PassiveIoTerminalMenu>>
            PASSIVE_IO_TERMINAL = MENUS.register("passive_io_terminal",
                    () -> MenuTypeBuilder
                            .<PassiveIoTerminalMenu, PassiveIoTerminalHost>create(
                                    (type, containerId, inventory, host) ->
                                            new PassiveIoTerminalMenu(type, containerId,
                                                    inventory, host),
                                    PassiveIoTerminalHost.class)
                            .buildUnregistered(id("passive_io_terminal")));

    /** 节点管理终端：只管节点、成员与节点级动作。 */
    public static final DeferredHolder<MenuType<?>, MenuType<NodeTerminalMenu>>
            NODE_TERMINAL = MENUS.register("node_terminal",
                    () -> MenuTypeBuilder
                            .<NodeTerminalMenu, NodeTerminalHost>create(
                                    (type, containerId, inventory, host) ->
                                            new NodeTerminalMenu(type, containerId, inventory, host),
                                    NodeTerminalHost.class)
                            .buildUnregistered(id("node_terminal")));

    /** 机器绑定杖的草稿确认面板：无槽位，只做登录壳。 */
    public static final DeferredHolder<MenuType<?>, MenuType<WandDraftMenu>> WAND_DRAFT =
            MENUS.register("wand_draft",
                    () -> MenuTypeBuilder
                            .<WandDraftMenu, WandDraftHost>create(
                                    (type, containerId, inventory, host) ->
                                            new WandDraftMenu(type, containerId, inventory, host),
                                    WandDraftHost.class)
                            .buildUnregistered(id("wand_draft")));

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, path);
    }

    private IdleFactoryMenus() {
        // 纯注册表持有类，不实例化
    }
}
