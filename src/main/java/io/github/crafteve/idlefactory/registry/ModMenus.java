package io.github.crafteve.idlefactory.registry;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.menu.FactoryMenu;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 菜单（容器）类型注册表。
 *
 * <p>M0 只注册工厂工作台本身，没有槽位，也不需要额外数据。</p>
 */
public final class ModMenus {
    /** 菜单注册表。 */
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, IdleFactory.MODID);

    /** 工厂工作台：客户端收到 id 后会构造 {@code FactoryMenu} 并由 {@code FactoryScreen} 渲染。 */
    public static final DeferredHolder<MenuType<?>, MenuType<FactoryMenu>> FACTORY =
            MENUS.register("factory", () -> new MenuType<>(FactoryMenu::new, FeatureFlags.DEFAULT_FLAGS));

    private ModMenus() {
        // 纯注册表持有类，不实例化
    }
}
