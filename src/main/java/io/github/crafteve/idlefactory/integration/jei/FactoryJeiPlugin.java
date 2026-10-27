package io.github.crafteve.idlefactory.integration.jei;

import java.util.Optional;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.screen.FactoryScreen;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.runtime.IClickableIngredient;
import mezz.jei.api.runtime.IJeiRuntime;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * JEI 的 R/U 查询入口。
 *
 * <p>工厂界面里的机器、输入输出和仓库不是原版 Slot，JEI 看不到这些物品。这里把一个
 * 可点击的 {@link IClickableIngredient} 交给 JEI，让 R/U 和悬停提示都走 JEI 自己的
 * 配方/用途页。</p>
 */
@JeiPlugin
public final class FactoryJeiPlugin implements IModPlugin {
    /** JEI 运行时由 {@link #onRuntimeAvailable(IJeiRuntime)} 注入。 */
    private static IJeiRuntime runtime;

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "factory_jei");
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGuiContainerHandler(FactoryScreen.class, new FactoryGuiHandler());
        // 兜底：终端部分区域被 JEI 当成普通容器处理时，全局 handler 仍然能给出目标。
        registration.addGlobalGuiHandler(new FactoryGlobalGuiHandler());
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }

    @Override
    public void onRuntimeUnavailable() {
        runtime = null;
    }

    private static Optional<IClickableIngredient<?>> clickableAt(
            FactoryScreen screen, double mouseX, double mouseY) {
        if (runtime == null) {
            return Optional.empty();
        }
        FactoryScreen.JeiTarget target = screen.jeiTargetAt(mouseX, mouseY);
        if (target == null || target.stack().isEmpty()) {
            return Optional.empty();
        }
        // JEI 的 R/U 只关心物品身份；去掉数量，避免带数量的栈影响查找/匹配。
        return runtime.getIngredientManager()
                .createClickableIngredient(target.stack().copyWithCount(1),
                        target.area(), false)
                .map(ingredient -> (IClickableIngredient<?>) ingredient);
    }

    private static final class FactoryGuiHandler
            implements IGuiContainerHandler<FactoryScreen> {
        @Override
        public Optional<IClickableIngredient<?>> getClickableIngredientUnderMouse(
                FactoryScreen screen, double mouseX, double mouseY) {
            return clickableAt(screen, mouseX, mouseY);
        }
    }

    private static final class FactoryGlobalGuiHandler implements IGlobalGuiHandler {
        @Override
        public Optional<IClickableIngredient<?>> getClickableIngredientUnderMouse(
                double mouseX, double mouseY) {
            if (Minecraft.getInstance().screen instanceof FactoryScreen screen) {
                return clickableAt(screen, mouseX, mouseY);
            }
            return Optional.empty();
        }
    }
}
