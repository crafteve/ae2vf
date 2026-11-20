package io.github.crafteve.idlefactory.client.screen.terminal;

import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.TerminalStyle;

import io.github.crafteve.idlefactory.client.screen.search.SearchEditBox;

import io.github.crafteve.idlefactory.client.screen.paint.Card;
import io.github.crafteve.idlefactory.client.screen.NineSliceRenderer;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 被动 IO 终端的静态 chrome：标题与搜索框底板。
 *
 * <p>只画不接收输入；坐标由调用方给出，chunk 内不再第二次算位置。搜索框的输入控件是
 * {@code SearchEditBox}，底板必须画在控件之前（{@code drawBG}），否则会盖住文字。</p>
 */
public final class TerminalChrome {
    /** 标题颜色：纯黑，跟正文一致。 */
    public static final int TITLE_COLOR = 0xFF000000;

    private TerminalChrome() {
        // 纯静态绘制
    }

    /**
     * 画工作区大卡片：和其他卡片共用 {@link Card} 的光照与阴影，只换层级底色。
     *
     * @param graphics 画布
     * @param x        左边缘
     * @param y        上边缘
     * @param width    宽
     * @param height   高
     */
    public static void drawWorkAreaFrame(GuiGraphics graphics, int x, int y, int width, int height) {
        Card.draw(graphics, x, y, width, height, Card.Level.WORK_AREA, true);
    }

    /**
     * 画标题。
     *
     * @param graphics 画布
     * @param font     字体
     * @param leftPos  面板左边缘
     * @param topPos   面板上边缘
     */
    public static void drawTitle(GuiGraphics graphics, Font font, int leftPos, int topPos,
            String title) {
        drawTitle(graphics, font, leftPos, topPos, title, Integer.MAX_VALUE);
    }

    /**
     * 画标题，超出可用宽度时省略，避免压到右侧搜索框。
     *
     * @param graphics 画布
     * @param font     字体
     * @param leftPos  面板左边缘
     * @param topPos   面板上边缘
     * @param title    标题
     * @param maxWidth 允许占用的最大宽度
     */
    public static void drawTitle(GuiGraphics graphics, Font font, int leftPos, int topPos,
            String title, int maxWidth) {
        String shown = title;
        if (font.width(shown) > maxWidth) {
            String ellipsis = "…";
            shown = font.plainSubstrByWidth(shown, Math.max(0, maxWidth - font.width(ellipsis)))
                    + ellipsis;
        }
        graphics.drawString(font, shown, leftPos + 8, topPos + 5, TITLE_COLOR, false);
    }

    /**
     * 按行数拼出 AE2 终端边框；调用方负责保证 {@code rows >= 2}。
     *
     * @param graphics 绘制器
     * @param style    终端样式
     * @param left     左边界
     * @param top      上边界
     * @param rows     中间行数
     */
    public static void drawFrame(GuiGraphics graphics, TerminalStyle style, int left, int top,
            int rows) {
        int y = blit(style.getHeader(), graphics, left, top);
        for (int row = 0; row < rows; row++) {
            Blitter blitter = row == 0 ? style.getFirstRow()
                    : row == rows - 1 ? style.getLastRow() : style.getRow();
            y = blit(blitter, graphics, left, y);
        }
        blit(style.getBottom(), graphics, left, y);
    }

    private static int blit(Blitter blitter, GuiGraphics graphics, int x, int y) {
        // copy() 避免把缓存的样式对象上的 dest 坐标留到下一次绘制。
        blitter.copy().dest(x, y).blit(graphics);
        return y + blitter.getSrcHeight();
    }

    /**
     * 搜索框底板：纯色填充 + 1px 外框 + 内凹阴影 / 高光。
     *
     * @param graphics 画布
     * @param x        左边缘
     * @param y        上边缘
     * @param width    宽
     * @param height   高
     */
    public static void drawSearchField(GuiGraphics graphics, int x, int y, int width, int height) {
        // AE2 原生搜索框贴图：亮色边框 + 内凹阴影，和样板管理终端同款。
        NineSliceRenderer.ae2TextField(graphics, x, y, width);
    }
}
