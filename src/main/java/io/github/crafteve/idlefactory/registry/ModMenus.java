package io.github.crafteve.idlefactory.registry;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.menu.FactoryCraftingTerminalMenu;
import io.github.crafteve.idlefactory.menu.FactoryMenu;
import io.github.crafteve.idlefactory.menu.FactoryPatternTerminalMenu;
import io.github.crafteve.idlefactory.menu.FactoryTerminalHost;

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
 * <p>工厂工作台保留给容器模式与未接入 ME 网络的前期通路；新终端分合成与样板两个类型，
 * 都由 AE2 的 {@link MenuTypeBuilder} 建出来，拿到「按物品定位器还原主机 + 自动登记打开器」
 * 这两项能力，再交给 NeoForge 的注册表注册。</p>
 */
public final class ModMenus {
    /** 菜单注册表。 */
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, IdleFactory.MODID);

    /** 工厂工作台：客户端收到 id 后会构造 {@code FactoryMenu} 并由 {@code FactoryScreen} 渲染。 */
    public static final DeferredHolder<MenuType<?>, MenuType<FactoryMenu>> FACTORY =
            MENUS.register("factory", () -> new MenuType<>(FactoryMenu::new, FeatureFlags.DEFAULT_FLAGS));

    /** 工厂终端 · 合成视图。 */
    public static final DeferredHolder<MenuType<?>, MenuType<FactoryCraftingTerminalMenu>>
            FACTORY_CRAFTING_TERMINAL = MENUS.register("factory_crafting_terminal",
                    () -> MenuTypeBuilder
                            .<FactoryCraftingTerminalMenu, FactoryTerminalHost>create(
                                    (type, containerId, inventory, host) ->
                                            new FactoryCraftingTerminalMenu(type, containerId,
                                                    inventory, host),
                                    FactoryTerminalHost.class)
                            .buildUnregistered(id("factory_crafting_terminal")));

    /** 工厂终端 · 样板视图。 */
    public static final DeferredHolder<MenuType<?>, MenuType<FactoryPatternTerminalMenu>>
            FACTORY_PATTERN_TERMINAL = MENUS.register("factory_pattern_terminal",
                    () -> MenuTypeBuilder
                            .<FactoryPatternTerminalMenu, FactoryTerminalHost>create(
                                    (type, containerId, inventory, host) ->
                                            new FactoryPatternTerminalMenu(type, containerId,
                                                    inventory, host),
                                    FactoryTerminalHost.class)
                            .buildUnregistered(id("factory_pattern_terminal")));

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, path);
    }

    private ModMenus() {
        // 纯注册表持有类，不实例化
    }
}
