package io.github.crafteve.idlefactory.client.screen.terminal;

import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.TerminalStyle;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 被动 IO 界面的 AE2 终端边框绘制。
 *
 * <p>只负责 header + N 行 + bottom 三段贴图的拼接，不关心内容；尺寸全部从
 * {@link TerminalStyle} 取，避免把 195 / 17 / 18 / 99 这些数字散落在屏幕类里。</p>
 */
public final class TerminalFrame {
    private TerminalFrame() {
        // 纯静态绘制工具
    }

    /**
     * 按行数拼出终端边框；调用方负责保证 {@code rows >= 2}。
     *
     * @param graphics 绘制器
     * @param style    终端样式
     * @param left     左边界
     * @param top      上边界
     * @param rows     中间行数
     */
    public static void draw(GuiGraphics graphics, TerminalStyle style, int left, int top, int rows) {
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
}
