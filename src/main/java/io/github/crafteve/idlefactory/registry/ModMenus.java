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
 * <p>第二版终端删除后只剩旧工作台：它负责 36 个玩家槽位与前期兜底，工厂数据仍走
 * 自己的包，不经过菜单槽位同步。</p>
 */
public final class ModMenus {
    /** 菜单注册表。 */
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, IdleFactory.MODID);

    /** 工厂工作台：客户端收到 id 后会构造 {@link FactoryMenu} 并由 {@code FactoryScreen} 渲染。 */
    public static final DeferredHolder<MenuType<?>, MenuType<FactoryMenu>> FACTORY =
            MENUS.register("factory", () -> new MenuType<>(FactoryMenu::new, FeatureFlags.DEFAULT_FLAGS));

    private ModMenus() {
        // 纯注册表持有类，不实例化
    }
}
