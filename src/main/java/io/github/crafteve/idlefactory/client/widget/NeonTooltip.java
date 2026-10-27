package io.github.crafteve.idlefactory.client.widget;

import java.util.List;

import io.github.crafteve.idlefactory.client.theme.GuiTheme;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 工厂界面自绘的浅色 tooltip。
 *
 * <p>原版 tooltip 是深紫底，和「明亮终端」的界面放在一起偏暗黑；这里改成浅色卡片，
 * 顶部压一条青→紫→黄→绿的 RGB 科技线，文字颜色由 {@link GuiTheme.Tooltip} 控制。</p>
 *
 * <p>不处理物品图标，只画文字；工厂界面的 tooltip 都只有文字。位置会自动避开屏幕边缘。</p>
 */
public final class NeonTooltip {
    /** 左右内边距。 */
    private static final int PAD_X = 5;
    /** 上下内边距。 */
    private static final int PAD_Y = 4;
    /** 行高。 */
    private static final int LINE_H = 10;
    /** 鼠标与 tooltip 的间距。 */
    private static final int OFFSET = 10;
    /** RGB 科技线的颜色节点。 */
    private static final int[] RAINBOW = {
        0xFF00E5FF, 0xFFFF3DCC, 0xFFFFE66D, 0xFF66FF99, 0xFF00E5FF
    };

    private NeonTooltip() {
        // 纯静态工具类
    }

    /**
     * 在鼠标附近画一个浅色 tooltip。
     *
     * @param graphics 画布
     * @param font     字体
     * @param lines    文本行；每行可以带自己的颜色
     * @param mouseX   鼠标屏幕 x
     * @param mouseY   鼠标屏幕 y
     * @param screenW  屏幕宽
     * @param screenH  屏幕高
     */
    public static void draw(GuiGraphics graphics, Font font, List<Component> lines,
            int mouseX, int mouseY, int screenW, int screenH) {
        if (lines.isEmpty()) {
            return;
        }
        int textWidth = 0;
        for (Component line : lines) {
            textWidth = Math.max(textWidth, font.width(line.getString()));
        }
        int width = textWidth + PAD_X * 2 + 2;
        int height = lines.size() * LINE_H + PAD_Y * 2 + 2;

        int x = mouseX + OFFSET;
        int y = mouseY + OFFSET;
        if (x + width > screenW - 4) {
            x = mouseX - width - OFFSET;
        }
        if (y + height > screenH - 4) {
            y = mouseY - height - OFFSET;
        }
        x = Math.max(4, Math.min(x, screenW - width - 4));
        y = Math.max(4, Math.min(y, screenH - height - 4));

        // 右下硬阴影 + 浅色卡片 + 顶部高光。
        NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK, x + 2, y + 2, width, height);
        NineSlice.card(graphics, x, y, width, height);
        NineSlice.fill(graphics, GuiTextures.TABLE_LIGHT, x + 1, y + 1, width - 2, 1);

        drawRgbLine(graphics, x + 1, y, width - 2);

        int textY = y + PAD_Y + 1;
        for (Component line : lines) {
            graphics.drawString(font, line, x + PAD_X, textY, 0xFFFFFFFF, false);
            textY += LINE_H;
        }
    }

    /** 画一条横向 RGB 渐变线。 */
    private static void drawRgbLine(GuiGraphics graphics, int x, int y, int width) {
        for (int i = 0; i < width; i++) {
            float t = width <= 1 ? 0.0F : (float) i / (width - 1);
            graphics.fill(x + i, y, x + i + 1, y + 1, rainbow(t));
        }
    }

    /** 在青、紫、黄、绿之间插值。 */
    private static int rainbow(float t) {
        float scaled = t * (RAINBOW.length - 1);
        int index = Math.min((int) scaled, RAINBOW.length - 2);
        float local = scaled - index;
        return lerpColor(RAINBOW[index], RAINBOW[index + 1], local);
    }

    private static int lerpColor(int from, int to, float t) {
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF)
                - ((from >> 16) & 0xFF)) * t);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF)
                - ((from >> 8) & 0xFF)) * t);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
