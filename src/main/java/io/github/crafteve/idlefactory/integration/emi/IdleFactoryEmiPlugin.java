package io.github.crafteve.idlefactory.integration.emi;

import appeng.integration.modules.emi.EmiEncodePatternHandler;
import appeng.integration.modules.emi.EmiUseCraftingRecipeHandler;

import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;

import io.github.crafteve.idlefactory.menu.FactoryCraftingTerminalMenu;
import io.github.crafteve.idlefactory.menu.FactoryPatternTerminalMenu;
import io.github.crafteve.idlefactory.registry.ModMenus;

/**
 * 把工厂终端的两个菜单类型接进 EMI 的配方转移。
 *
 * <p>AE2 1.21 的配方转移按 {@code MenuType} 注册到 EMI，只认它自己的终端类型；工厂终端
 * 继承的是菜单类、用的是自己的类型，所以要把同样的处理器再注册一份，否则 EMI 会提示
 * 「当前工作方块不支持该配方」。</p>
 */
@EmiEntrypoint
public final class IdleFactoryEmiPlugin implements EmiPlugin {
    @Override
    public void register(EmiRegistry registry) {
        registry.addRecipeHandler(ModMenus.FACTORY_PATTERN_TERMINAL.get(),
                new EmiEncodePatternHandler<>(FactoryPatternTerminalMenu.class));
        registry.addRecipeHandler(ModMenus.FACTORY_CRAFTING_TERMINAL.get(),
                new EmiUseCraftingRecipeHandler<>(FactoryCraftingTerminalMenu.class));
    }
}
