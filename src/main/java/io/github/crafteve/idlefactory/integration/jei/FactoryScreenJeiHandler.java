package io.github.crafteve.idlefactory.integration.jei;

import java.util.Optional;

import io.github.crafteve.idlefactory.client.screen.FactoryScreen;

import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.runtime.IClickableIngredient;
import mezz.jei.api.runtime.IIngredientManager;

import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;

/**
 * 让 JEI 认识自绘存储网格里的物品。
 *
 * <p>JEI 的 {@code ScreenHelper} 会把这个 handler 的结果和原版 {@code Slot} 的结果合并，
 * 所以背包槽位仍然由 JEI 自己处理；这里只负责 {@code ItemGrid} 画出来的那部分。</p>
 */
public class FactoryScreenJeiHandler implements IGuiContainerHandler<FactoryScreen> {
    private final IIngredientManager ingredientManager;

    public FactoryScreenJeiHandler(IIngredientManager ingredientManager) {
        this.ingredientManager = ingredientManager;
    }

    @Override
    public Optional<IClickableIngredient<?>> getClickableIngredientUnderMouse(FactoryScreen screen,
            double mouseX, double mouseY) {
        ItemStack stack = screen.getHoveredStorageStack(mouseX, mouseY);
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }

        Rect2i area = screen.getHoveredStorageArea(mouseX, mouseY);
        if (area == null) {
            // 理论上有物品就一定有格子矩形，兜底给鼠标周围 16×16
            area = new Rect2i((int) mouseX - 8, (int) mouseY - 8, 16, 16);
        }

        return this.ingredientManager
                .createClickableIngredient(stack, area, false)
                .map(ingredient -> (IClickableIngredient<?>) ingredient);
    }
}
