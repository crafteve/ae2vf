package io.github.crafteve.idlefactory.client.screen.wand;


import io.github.crafteve.idlefactory.client.screen.NineSliceRenderer;
import io.github.crafteve.idlefactory.client.screen.text.WandText;
import io.github.crafteve.idlefactory.location.BlockKey;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 草稿面板的行渲染：凹槽图标 + 方块名 + 坐标，删除态红字并带删除标记。
 *
 * <p>行是纯展示，不响应点击；撤销删除要回世界再右键一次。几何只在面板里算，本类只按给定
 * 矩形画一行。图标底色用 {@link NineSliceRenderer#slot}，和节点终端的成员行同一种凹槽。</p>
 */
public final class WandRowRender {
    /** 行高；18px 凹槽 + 0 间距，六行正好塞进行区。 */
    public static final int ROW_HEIGHT = 18;
    /** 图标凹槽边长。 */
    private static final int SLOT = 18;
    /** 名字左边界（槽 + 4px 间隙）。 */
    private static final int TEXT_X = SLOT + 4;
    /** 文字基线相对行顶的偏移。 */
    private static final int TEXT_Y = 5;
    /** 右侧至少有 2px 内边距。 */
    private static final int RIGHT_PAD = 2;
    /** 正文色。 */
    private static final int TEXT_PRIMARY = 0xFF000000;
    /** 次要文字（坐标）。 */
    private static final int TEXT_DIM = 0xFF5F5F5F;
    /** 待删除 / 冲突的红字。 */
    private static final int ERROR = 0xFFD63A3A;
    /** 删除标记与坐标之间的间隙。 */
    private static final int LABEL_GAP = 6;

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
    public static void draw(GuiGraphics graphics, Font font, int x, int y,
            int width, BlockKey block, ResourceLocation blockId, boolean removed,
            boolean conflicted) {
        boolean error = removed || conflicted;
        int color = error ? ERROR : TEXT_PRIMARY;
        if (removed) {
            graphics.fill(x, y, x + width, y + ROW_HEIGHT - 1, 0x22FF4D4D);
        }
        NineSliceRenderer.slot(graphics, x, y, SLOT, SLOT);
        graphics.renderItem(new ItemStack(BuiltInRegistries.BLOCK.get(blockId).asItem()),
                x + 1, y + 1);

        String pos = block.pos().toShortString();
        String label = WandText.panelRemoved().getString();
        int rightReserve = RIGHT_PAD;
        if (removed) {
            graphics.drawString(font, label, x + width - RIGHT_PAD - font.width(label), y + TEXT_Y,
                    ERROR, false);
            rightReserve += font.width(label) + LABEL_GAP;
        }
        rightReserve += font.width(pos);
        graphics.drawString(font, pos, x + width - rightReserve, y + TEXT_Y,
                TEXT_DIM, false);

        int nameWidth = width - TEXT_X - rightReserve - LABEL_GAP;
        String name = ellipsize(font, WandText.blockName(blockId), nameWidth);
        graphics.drawString(font, name, x + TEXT_X, y + TEXT_Y, color, false);
    }

    /**
     * 按像素宽度截断字符串；超宽时用省略号收尾。
     *
     * @param font  字体
     * @param text  原文
     * @param width 可用宽度
     * @return 适配宽度的文本
     */
    private static String ellipsize(Font font, String text, int width) {
        if (width <= 0) {
            return "";
        }
        if (font.width(text) <= width) {
            return text;
        }
        String ellipsis = "…";
        int budget = width - font.width(ellipsis);
        if (budget <= 0) {
            return ellipsis;
        }
        return font.plainSubstrByWidth(text, budget) + ellipsis;
    }
}
