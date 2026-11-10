package io.github.crafteve.idlefactory.registry;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.menu.FactoryNodeHost;
import io.github.crafteve.idlefactory.menu.FactoryNodeMenu;
import io.github.crafteve.idlefactory.menu.FactoryPassiveIoHost;
import io.github.crafteve.idlefactory.menu.FactoryPassiveIoMenu;

import appeng.menu.implementations.MenuTypeBuilder;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 菜单（容器）类型注册表。
 *
 * <p>只剩两个 AE2 终端菜单：被动 IO 配置与节点管理。它们复用 AE2 的边框与背包槽，
 * 不接 ME 库存菜单。</p>
 */
public final class ModMenus {
    /** 菜单注册表。 */
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, IdleFactory.MODID);

    /** 被动 IO 管理终端：AE2 菜单构建器负责按物品定位器还原宿主。 */
    public static final DeferredHolder<MenuType<?>, MenuType<FactoryPassiveIoMenu>>
            PASSIVE_IO_TERMINAL = MENUS.register("passive_io_terminal",
                    () -> MenuTypeBuilder
                            .<FactoryPassiveIoMenu, FactoryPassiveIoHost>create(
                                    (type, containerId, inventory, host) ->
                                            new FactoryPassiveIoMenu(type, containerId,
                                                    inventory, host),
                                    FactoryPassiveIoHost.class)
                            .buildUnregistered(id("passive_io_terminal")));

    /** 节点管理终端：只管节点、成员与节点级动作。 */
    public static final DeferredHolder<MenuType<?>, MenuType<FactoryNodeMenu>>
            NODE_TERMINAL = MENUS.register("node_terminal",
                    () -> MenuTypeBuilder
                            .<FactoryNodeMenu, FactoryNodeHost>create(
                                    (type, containerId, inventory, host) ->
                                            new FactoryNodeMenu(type, containerId, inventory, host),
                                    FactoryNodeHost.class)
                            .buildUnregistered(id("node_terminal")));

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, path);
    }

    private ModMenus() {
        // 纯注册表持有类，不实例化
    }
}
