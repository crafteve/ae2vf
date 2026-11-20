package io.github.crafteve.idlefactory.integration.jei;

import java.util.List;

import io.github.crafteve.idlefactory.client.screen.KeyIconRenderer;

import mezz.jei.api.ingredients.IIngredientRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.TooltipFlag;
import io.github.crafteve.idlefactory.key.AeKeyAdapter;

/**
 * FE 配料的 JEI 绘制。
 *
 * <p>直接复用仓库格/IO 列的 {@link KeyIconRenderer}：装了 Applied Flux 时画它的
 * FE 图标，没装时画绿色水贴图兜底，保证 JEI 里的 FE 和界面上的 FE 看起来是同一个东西。</p>
 */
final class FeIngredientRenderer implements IIngredientRenderer<FeIngredient> {
    /**
     * 在 JEI 给定的原点画 FE 图标。
     *
     * <p>JEI 会先把 pose 平移到槽位原点，所以这里从 {@code (0, 0)} 开始画。</p>
     *
     * @param graphics  画布
     * @param ingredient FE 配料
     */
    @Override
    public void render(GuiGraphics graphics, FeIngredient ingredient) {
        KeyIconRenderer.draw(graphics, Minecraft.getInstance().font,
                AeKeyAdapter.energy(), 0, 0);
    }

    /**
     * 悬停提示。
     *
     * @param ingredient  FE 配料
     * @param tooltipFlag 提示详细程度
     * @return 只有一行 {@code FE}
     */
    @Override
    @SuppressWarnings({"deprecation", "removal"})
    public List<Component> getTooltip(FeIngredient ingredient, TooltipFlag tooltipFlag) {
        return List.of(Component.literal("FE"));
    }
}
