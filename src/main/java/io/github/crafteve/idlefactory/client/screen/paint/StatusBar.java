package io.github.crafteve.idlefactory.client.screen.paint;


import net.minecraft.client.gui.GuiGraphics;

/**
 * 立式状态灯：3px 宽的竖条，左列提亮、中列本色、右列压暗。
 *
 * <p>被动 IO 终端里只有机器行用它：IO 卡片的方向已经由 chip 表达，不再画灯。灯宽固定 3px，
 * 正好顶掉卡片最左侧一条竖边——灯因此落在卡片轮廓之内，卡片的下 / 右外阴影能正常收口，
 * 不会出现灯悬在卡片外、下面没有阴影接着的情况。</p>
 *
 * <p>只用纯色三列、不生成贴图：灯色随状态 / 方向变化，贴图会多出一处色源；三个 {@code fill}
 * 也没有采样成本。</p>
 */
public final class StatusBar {
    /** 灯宽（像素）；排版给灯预留的左缩进必须用它推导，不能写字面量 3。 */
    public static final int WIDTH = 3;
    /** 亮列 / 暗列相对本色的偏移，和状态灯贴图的 ±45 放在同一量级。 */
    private static final int SHADE_SHIFT = 38;

    private StatusBar() {
        // 纯静态绘制
    }

    /**
     * 画一条立式状态灯。
     *
     * @param graphics 画布
     * @param x        左边缘；灯占 {@code [x, x + WIDTH)}
     * @param y        上边缘
     * @param height   高度
     * @param color    本色 ARGB；亮列与暗列由它推出
     */
    public static void draw(GuiGraphics graphics, int x, int y, int height, int color) {
        if (height <= 0) {
            return;
        }
        graphics.fill(x, y, x + 1, y + height, shift(color, SHADE_SHIFT));
        graphics.fill(x + 1, y, x + 2, y + height, color);
        graphics.fill(x + 2, y, x + WIDTH, y + height, shift(color, -SHADE_SHIFT));
    }

    /**
     * 按固定偏移量提亮 / 压暗颜色。
     *
     * @param color 原色 ARGB
     * @param delta 正数提亮、负数压暗
     * @return 调整后的颜色，保留原 alpha
     */
    private static int shift(int color, int delta) {
        int r = clamp(((color >> 16) & 0xFF) + delta);
        int g = clamp(((color >> 8) & 0xFF) + delta);
        int b = clamp((color & 0xFF) + delta);
        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static int clamp(int channel) {
        return Math.max(0, Math.min(255, channel));
    }
}
