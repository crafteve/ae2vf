package io.github.crafteve.idlefactory.integration.jei;

import java.util.List;
import java.util.Optional;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.screen.FactoryScreen;
import io.github.crafteve.idlefactory.factory.resource.ResourceKind;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IModIngredientRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.runtime.IClickableIngredient;
import mezz.jei.api.runtime.IJeiRuntime;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * JEI 的 R/U 查询入口。
 *
 * <p>工厂界面里的机器、输入输出和仓库不是原版 Slot，JEI 看不到这些物品。这里把一个
 * 可点击的 {@link IClickableIngredient} 交给 JEI，让 R/U 和悬停提示都走 JEI 自己的
 * 配方/用途页。</p>
 *
 * <p>FE 没有物品形态，不能映射成红石粉：这里注册一个独立的 {@link FeIngredient} 配料，
 * 并给它一条信息页。这样仓库/IO 列里的 FE 在 JEI 里仍然是 FE，R/U 不会跳到红石粉配方，
 * 收藏也保存 FE 图标。</p>
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
    public void registerIngredients(IModIngredientRegistration registration) {
        // allIngredients 给空表：FE 只在工厂界面里按需出现，不往 JEI 全局物品列表塞伪物品。
        registration.register(FeIngredient.TYPE, List.of(), new FeIngredientHelper(),
                new FeIngredientRenderer(), FeIngredient.CODEC);
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        // R/U 没有真实配方可查；给 FE 一条信息页，至少让查询落在 FE 上而不是红石粉上。
        registration.addIngredientInfo(FeIngredient.INSTANCE, FeIngredient.TYPE,
                Component.translatable("jei.idlefactory.fe.info.1"),
                Component.translatable("jei.idlefactory.fe.info.2"),
                Component.translatable("jei.idlefactory.fe.info.3"));
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
        if (target == null) {
            return Optional.empty();
        }
        if (target.kind() == ResourceKind.ENERGY) {
            // FE 没有物品形态；用独立配料，R/U 与收藏都落在 FE 上，而不是红石粉。
            return runtime.getIngredientManager()
                    .createClickableIngredient(FeIngredient.TYPE, FeIngredient.INSTANCE,
                            target.area(), false)
                    .map(ingredient -> (IClickableIngredient<?>) ingredient);
        }
        if (target.stack().isEmpty()) {
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
