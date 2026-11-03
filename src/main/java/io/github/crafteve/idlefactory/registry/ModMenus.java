package io.github.crafteve.idlefactory.registry;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.menu.FactoryMenu;
import io.github.crafteve.idlefactory.menu.FactoryPassiveIoHost;
import io.github.crafteve.idlefactory.menu.FactoryPassiveIoMenu;

import appeng.menu.implementations.MenuTypeBuilder;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 菜单（容器）类型注册表。
 *
 * <p>旧工作台负责前期兜底与 36 个玩家槽位；被动 IO 管理终端是第三版界面，只复用
 * AE2 的边框与背包槽，不接 ME 库存菜单。</p>
 */
public final class ModMenus {
    /** 菜单注册表。 */
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, IdleFactory.MODID);

    /** 工厂工作台：客户端收到 id 后会构造 {@link FactoryMenu} 并由 {@code FactoryScreen} 渲染。 */
    public static final DeferredHolder<MenuType<?>, MenuType<FactoryMenu>> FACTORY =
            MENUS.register("factory", () -> new MenuType<>(FactoryMenu::new, FeatureFlags.DEFAULT_FLAGS));

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

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, path);
    }

    private ModMenus() {
        // 纯注册表持有类，不实例化
    }
}
