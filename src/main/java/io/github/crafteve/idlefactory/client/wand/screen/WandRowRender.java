package io.github.crafteve.idlefactory.client.wand.screen;

import io.github.crafteve.idlefactory.client.screen.FactoryGuiTheme;
import io.github.crafteve.idlefactory.client.wand.WandMessages;
import io.github.crafteve.idlefactory.factory.location.BlockKey;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 草稿面板的行渲染：图标 + 方块名 + 坐标，删除态红字并带删除标记。
 *
 * <p>行是纯展示，不响应点击；撤销删除要回世界再右键一次。几何只在面板里算，本类只按给定
 * 矩形画一行。</p>
 */
public final class WandRowRender {
    /** 行高；图标 12 + 上下 1px。 */
    public static final int ROW_HEIGHT = 14;
    /** 左侧内边距。 */
    private static final int PADDING_X = 2;

    private WandRowRender() {
        // 纯静态渲染
    }

    /**
     * 画一行。
     *
     * @param graphics     绘制上下文
     * @param font         字体
     * @param theme        主题色板
     * @param x            行左边界
     * @param y            行顶边
     * @param width        行宽
     * @param block        方块坐标
     * @param blockId      用来取图标与名字的方块 id
     * @param removed      是否是待删除行
     * @param conflicted   是否被校验判为冲突
     */
    public static void draw(GuiGraphics graphics, Font font, FactoryGuiTheme theme, int x, int y,
            int width, BlockKey block, ResourceLocation blockId, boolean removed,
            boolean conflicted) {
        boolean error = removed || conflicted;
        int color = error ? theme.error() : theme.textPrimary();
        if (removed) {
            graphics.fill(x - PADDING_X, y, x + width, y + ROW_HEIGHT - 1, 0x22FF4D4D);
        }
        graphics.renderItem(new ItemStack(BuiltInRegistries.BLOCK.get(blockId).asItem()),
                x + PADDING_X, y + 1);
        graphics.drawString(font, WandMessages.blockName(blockId), x + 18, y + 3, color, false);
        String pos = block.pos().toShortString();
        String removedLabel = WandMessages.panelRemoved().getString();
        int rightReserve = removed ? font.width(removedLabel) + 6 : 2;
        graphics.drawString(font, pos, x + width - rightReserve - font.width(pos), y + 3,
                theme.textDim(), false);
        if (removed) {
            graphics.drawString(font, removedLabel, x + width - font.width(removedLabel), y + 3,
                    theme.error(), false);
        }
    }
}
