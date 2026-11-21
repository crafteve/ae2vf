package io.github.crafteve.ae2vf.integration.jei;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

import io.github.crafteve.ae2vf.VoidFactory;
import io.github.crafteve.ae2vf.client.screen.terminal.passiveio.PassiveIoJeiIngredients;
import io.github.crafteve.ae2vf.client.screen.terminal.passiveio.PassiveIoScreen;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.runtime.IClickableIngredient;
import mezz.jei.api.runtime.IJeiRuntime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Rect2i;
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
 * <p>FE 不注册 JEI 配料：它走 AE2 的通用包裹堆栈（{@code GenericStack}），
 * 拖拽与查询都按包裹里的真实资源处理，不再需要 FE 假身份。</p>
 */
@JeiPlugin
public final class FactoryJeiPlugin implements IModPlugin {
    /** JEI 运行时由 {@link #onRuntimeAvailable(IJeiRuntime)} 注入。 */
    private static IJeiRuntime runtime;

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(VoidFactory.MODID, "factory_jei");
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGuiContainerHandler(PassiveIoScreen.class,
                new PassiveIoGuiHandler());
        registration.addGhostIngredientHandler(PassiveIoScreen.class,
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
            PassiveIoScreen screen, double mouseX, double mouseY) {
        PassiveIoJeiIngredients.JeiTarget target = screen.jeiTargetAt(mouseX, mouseY);
        if (target == null || runtime == null) {
            return Optional.empty();
        }
        if (target.stack().isEmpty()) {
            return Optional.empty();
        }
        return runtime.getIngredientManager()
                .createClickableIngredient(target.stack().copyWithCount(1), target.area(), false)
                .map(ingredient -> (IClickableIngredient<?>) ingredient);
    }

    private static final class PassiveIoGuiHandler
            implements IGuiContainerHandler<PassiveIoScreen> {
        @Override
        public Optional<IClickableIngredient<?>> getClickableIngredientUnderMouse(
                PassiveIoScreen screen, double mouseX, double mouseY) {
            return clickableAt(screen, mouseX, mouseY);
        }
    }

    private static final class FactoryGlobalGuiHandler implements IGlobalGuiHandler {
        @Override
        public Optional<IClickableIngredient<?>> getClickableIngredientUnderMouse(
                double mouseX, double mouseY) {
            if (Minecraft.getInstance().screen instanceof PassiveIoScreen screen) {
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
     * <p>只接收物品与流体；FE 走 AE2 的通用包裹，会以物品形态到达，由
     * {@link #keyOfItem} 拆开。其它配料返回 {@code null}，拖拽目标为空。</p>
     *
     * @param ingredient JEI 配料
     * @return 资源 key；不支持时为 {@code null}
     */
    @Nullable
    private static AEKey toKey(Object ingredient) {
        if (ingredient instanceof ItemStack stack && !stack.isEmpty()) {
            return keyOfItem(stack);
        }
        if (ingredient instanceof FluidStack fluid && !fluid.isEmpty()) {
            return AEFluidKey.of(fluid);
        }
        return null;
    }

    /**
     * 物品配料转资源 key：先拆 AE2 的「包裹通用堆栈」，再按普通物品处理。
     *
     * <p>包裹堆栈是 AE2 用来在终端之间传非物品资源的包装纸（FE、流体、化学品都可能装在里面）。
     * 不拆的话规则会写成「搬这张纸」，机器永远收不到里面的资源。</p>
     *
     * @param stack 物品配料
     * @return 资源 key
     */
    private static AEKey keyOfItem(ItemStack stack) {
        if (GenericStack.isWrapped(stack)) {
            GenericStack inner = GenericStack.unwrapItemStack(stack);
            if (inner != null) {
                return inner.what();
            }
        }
        return AEItemKey.of(stack);
    }

    private static final class PassiveIoGhostHandler
            implements IGhostIngredientHandler<PassiveIoScreen> {
        @Override
        public <I> List<Target<I>> getTargetsTyped(PassiveIoScreen screen,
                ITypedIngredient<I> ingredient, boolean doStart) {
            AEKey key = toKey(ingredient.getIngredient());
            if (key == null) {
                return List.of();
            }
            List<Target<I>> targets = new ArrayList<>();
            for (PassiveIoJeiIngredients.AddTarget area : screen.addTargets()) {
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
            if (Minecraft.getInstance().screen instanceof PassiveIoScreen screen) {
                screen.setJeiDragging(false);
            }
        }
    }
}
