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
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryCraftingTerminalScreen;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryPatternTerminalScreen;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryTerminalDropArea;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryTerminalJeiSource;
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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * JEI 的 R/U 查询与拖入入口。
 *
 * <p>工厂界面里的机器、输入输出和仓库不是原版 Slot，JEI 看不到这些物品。这里把一个
 * 可点击的 {@link IClickableIngredient} 交给 JEI，让 R/U 和悬停提示都走 JEI 自己的
 * 配方/用途页。终端左面板的机器图标、规则图标与目标图标也走同一条路。</p>
 *
 * <p>FE 没有物品形态，不能映射成红石粉：这里注册一个独立的 {@link FeIngredient} 配料，
 * 并给它一条信息页。这样仓库/IO 列里的 FE 在 JEI 里仍然是 FE，R/U 不会跳到红石粉配方，
 * 收藏也保存 FE 图标。</p>
 *
 * <p>拖入只覆盖终端左面板的输入 / 输出区：把物品或流体拖到对应区域就是加一条规则，
 * 和光标手持物品点「+」等价。</p>
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
        registration.addGuiContainerHandler(FactoryCraftingTerminalScreen.class,
                new CraftingTerminalGuiHandler());
        registration.addGuiContainerHandler(FactoryPatternTerminalScreen.class,
                new PatternTerminalGuiHandler());
        registration.addGhostIngredientHandler(FactoryCraftingTerminalScreen.class,
                new TerminalGhostHandler<FactoryCraftingTerminalScreen>());
        registration.addGhostIngredientHandler(FactoryPatternTerminalScreen.class,
                new TerminalGhostHandler<FactoryPatternTerminalScreen>());
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

    private static final class FactoryGuiHandler
            implements IGuiContainerHandler<FactoryScreen> {
        @Override
        public Optional<IClickableIngredient<?>> getClickableIngredientUnderMouse(
                FactoryScreen screen, double mouseX, double mouseY) {
            return clickableAt(screen, mouseX, mouseY);
        }
    }

    /** 合成视图的 R/U 处理器。 */
    private static final class CraftingTerminalGuiHandler
            implements IGuiContainerHandler<FactoryCraftingTerminalScreen> {
        @Override
        public Optional<IClickableIngredient<?>> getClickableIngredientUnderMouse(
                FactoryCraftingTerminalScreen screen, double mouseX, double mouseY) {
            return clickableAt(screen.jeiTargetAt(mouseX, mouseY));
        }
    }

    /** 样板视图的 R/U 处理器。 */
    private static final class PatternTerminalGuiHandler
            implements IGuiContainerHandler<FactoryPatternTerminalScreen> {
        @Override
        public Optional<IClickableIngredient<?>> getClickableIngredientUnderMouse(
                FactoryPatternTerminalScreen screen, double mouseX, double mouseY) {
            return clickableAt(screen.jeiTargetAt(mouseX, mouseY));
        }
    }

    /**
     * 终端屏幕的 JEI 拖入处理器：把物品 / 流体拖到输入或输出区。
     *
     * @param <T> 屏幕类型
     */
    private static final class TerminalGhostHandler<T extends Screen & FactoryTerminalJeiSource>
            implements IGhostIngredientHandler<T> {
        @Override
        public <I> List<Target<I>> getTargetsTyped(T screen, ITypedIngredient<I> ingredient,
                boolean doStart) {
            AEKey key = toKey(ingredient.getIngredient());
            if (key == null) {
                return List.of();
            }
            List<Target<I>> targets = new ArrayList<>();
            for (FactoryTerminalDropArea area : screen.jeiDropAreas()) {
                targets.add(new DropTarget<>(area, key, screen));
            }
            return targets;
        }

        @Override
        public void onComplete() {
            // 拖拽结束后服务端会推新快照，客户端不需要额外收尾。
        }
    }

    /**
     * 一个拖入命中区域；接受时把资源交给面板。
     *
     * @param <I> JEI 配料类型
     */
    private static final class DropTarget<I> implements IGhostIngredientHandler.Target<I> {
        private final Rect2i area;
        private final AEKey key;
        private final FactoryTerminalJeiSource screen;

        private DropTarget(FactoryTerminalDropArea area, AEKey key,
                FactoryTerminalJeiSource screen) {
            this.area = area.area();
            this.key = key;
            this.screen = screen;
        }

        @Override
        public Rect2i getArea() {
            return this.area;
        }

        @Override
        public void accept(I ingredient) {
            this.screen.dropJeiKey(this.key, this.area.getX() + this.area.getWidth() / 2,
                    this.area.getY() + this.area.getHeight() / 2);
        }
    }

    /**
     * 把 JEI 配料转成 AE2 的资源 key；不认识的类型返回 {@code null}。
     *
     * @param ingredient JEI 配料
     * @return 资源 key
     */
    @Nullable
    private static AEKey toKey(Object ingredient) {
        if (ingredient instanceof ItemStack stack) {
            return AEItemKey.of(stack);
        }
        if (ingredient instanceof FluidStack fluid) {
            return AEFluidKey.of(fluid);
        }
        return null;
    }

    private static final class FactoryGlobalGuiHandler implements IGlobalGuiHandler {
        @Override
        public Optional<IClickableIngredient<?>> getClickableIngredientUnderMouse(
                double mouseX, double mouseY) {
            if (Minecraft.getInstance().screen instanceof FactoryScreen screen) {
                return clickableAt(screen, mouseX, mouseY);
            }
            if (Minecraft.getInstance().screen instanceof FactoryTerminalJeiSource terminal
                    && Minecraft.getInstance().screen instanceof AbstractContainerScreen<?>) {
                return clickableAt(terminal.jeiTargetAt(mouseX, mouseY));
            }
            return Optional.empty();
        }
    }
}
