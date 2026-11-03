package io.github.crafteve.idlefactory.integration.jei;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.screen.FactoryScreen;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryPassiveIoJeiTargets;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryPassiveIoScreen;
import io.github.crafteve.idlefactory.factory.capability.CapabilityKind;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IModIngredientRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.runtime.IClickableIngredient;
import mezz.jei.api.runtime.IJeiRuntime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * JEI 的 R/U 查询与拖拽入口。
 *
 * <p>旧工作台与第三版被动 IO 终端里的机器、输入输出都不是原版 Slot，JEI 看不到这些物品。
 * 这里把可点击的 {@link IClickableIngredient} 交给 JEI，让 R/U 和悬停提示都走 JEI 自己的
 * 配方/用途页；被动 IO 终端另外注册 {@link IGhostIngredientHandler}，把 JEI 里的物品 / 流体
 * 拖到虚线新增行上就新建一条输入规则。</p>
 *
 * <p>FE 没有物品形态，不能映射成红石粉：这里注册一个独立的 {@link FeIngredient} 配料，
 * 并给它一条信息页。这样仓库 / IO 列里的 FE 在 JEI 里仍然是 FE，R/U 不会跳到红石粉配方，
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
        // R/U 没有真实配方可查；给 FE 一条信息页，至少让查询落在 FE 上而不是红石粉。
        registration.addIngredientInfo(FeIngredient.INSTANCE, FeIngredient.TYPE,
                Component.translatable("jei.idlefactory.fe.info.1"),
                Component.translatable("jei.idlefactory.fe.info.2"),
                Component.translatable("jei.idlefactory.fe.info.3"));
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGuiContainerHandler(FactoryScreen.class, new FactoryGuiHandler());
        registration.addGuiContainerHandler(FactoryPassiveIoScreen.class,
                new PassiveIoGuiHandler());
        registration.addGhostIngredientHandler(FactoryPassiveIoScreen.class,
                new PassiveIoGhostHandler());
        // 兜底：部分区域被 JEI 当成普通容器处理时，全局 handler 仍然能给出目标。
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

    // ------------------------------------------------------------------
    // R / U 查询
    // ------------------------------------------------------------------

    private static Optional<IClickableIngredient<?>> clickableAt(
            FactoryScreen screen, double mouseX, double mouseY) {
        return clickableAt(screen.jeiTargetAt(mouseX, mouseY));
    }

    private static Optional<IClickableIngredient<?>> clickableAt(
            @Nullable FactoryScreen.JeiTarget target) {
        if (target == null || runtime == null) {
            return Optional.empty();
        }
        if (target.kind() == CapabilityKind.ENERGY) {
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

    private static Optional<IClickableIngredient<?>> clickableAt(
            FactoryPassiveIoScreen screen, double mouseX, double mouseY) {
        FactoryPassiveIoJeiTargets.JeiTarget target = screen.jeiTargetAt(mouseX, mouseY);
        if (target == null || runtime == null) {
            return Optional.empty();
        }
        if (target.kind() == CapabilityKind.ENERGY) {
            return runtime.getIngredientManager()
                    .createClickableIngredient(FeIngredient.TYPE, FeIngredient.INSTANCE,
                            target.area(), false)
                    .map(ingredient -> (IClickableIngredient<?>) ingredient);
        }
        if (target.stack().isEmpty()) {
            return Optional.empty();
        }
        return runtime.getIngredientManager()
                .createClickableIngredient(target.stack().copyWithCount(1), target.area(), false)
                .map(ingredient -> (IClickableIngredient<?>) ingredient);
    }

    private static final class FactoryGuiHandler implements IGuiContainerHandler<FactoryScreen> {
        @Override
        public Optional<IClickableIngredient<?>> getClickableIngredientUnderMouse(
                FactoryScreen screen, double mouseX, double mouseY) {
            return clickableAt(screen, mouseX, mouseY);
        }
    }

    private static final class PassiveIoGuiHandler
            implements IGuiContainerHandler<FactoryPassiveIoScreen> {
        @Override
        public Optional<IClickableIngredient<?>> getClickableIngredientUnderMouse(
                FactoryPassiveIoScreen screen, double mouseX, double mouseY) {
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
            if (Minecraft.getInstance().screen instanceof FactoryPassiveIoScreen screen) {
                return clickableAt(screen, mouseX, mouseY);
            }
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------
    // JEI → 新建规则
    // ------------------------------------------------------------------

    /**
     * 把 JEI 拖过来的配料转成 AE2 的资源 key。
     *
     * <p>FE 不参与拖拽：草稿 12 定的是「FE 由绑定链路决定，不走拖入」，所以这里只接收物品与
     * 流体；其它配料返回 {@code null}，拖拽目标为空，JEI 不会显示可放置区域。</p>
     *
     * @param ingredient JEI 配料
     * @return 资源 key；不支持时为 {@code null}
     */
    @Nullable
    private static AEKey toKey(Object ingredient) {
        if (ingredient instanceof ItemStack stack && !stack.isEmpty()) {
            return AEItemKey.of(stack);
        }
        if (ingredient instanceof FluidStack fluid && !fluid.isEmpty()) {
            return AEFluidKey.of(fluid);
        }
        return null;
    }

    private static final class PassiveIoGhostHandler
            implements IGhostIngredientHandler<FactoryPassiveIoScreen> {
        @Override
        public <I> List<Target<I>> getTargetsTyped(FactoryPassiveIoScreen screen,
                ITypedIngredient<I> ingredient, boolean doStart) {
            AEKey key = toKey(ingredient.getIngredient());
            if (key == null) {
                return List.of();
            }
            List<Target<I>> targets = new ArrayList<>();
            for (FactoryPassiveIoJeiTargets.AddTarget area : screen.addTargets()) {
                targets.add(new Target<>() {
                    @Override
                    public Rect2i getArea() {
                        return area.area();
                    }

                    @Override
                    public void accept(I value) {
                        screen.addFromJei(area.block(), key);
                    }
                });
            }
            return targets;
        }

        @Override
        public void onComplete() {
            if (Minecraft.getInstance().screen instanceof FactoryPassiveIoScreen screen) {
                screen.setJeiDragging(false);
            }
        }
    }
}
