package io.github.crafteve.idlefactory.client.screen;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 统一的卡片组件：所有层级都走这一套光照明暗。
 *
 * <p>三级卡片——工作区大卡片、机器大卡片、IO 配置小卡片——只有底色不同，
 * 高光方向、暗边方向、外侧阴影颜色和偏移完全一致：上 / 左 1px 亮边，下 / 右 1px 暗边，
 * 下 / 右外侧 1px 半透明阴影。调用方负责在卡片外侧留出 1px 影子空间。</p>
 */
public final class FactoryCard {
    /** 卡片层级；只决定底色，不改变光照方向。 */
    public enum Level {
        /** 工作区大卡片。 */
        WORK_AREA,
        /** 机器大卡片（机器行 + IO 卡片的父卡）。 */
        MACHINE,
        /** IO 配置小卡片（列表行 / 磁贴）。 */
        IO
    }

    /** 外侧阴影：所有层级共用同一个颜色和 1px 偏移。 */
    private static final int OUTER_SHADOW = 0x33000000;

    private FactoryCard() {
        // 纯静态组件
    }

    /**
     * 画一张卡片：底色 + 上 / 左亮边 + 下 / 右暗边 + 可选外侧阴影。
     *
     * @param graphics 画布
     * @param x        左边缘
     * @param y        上边缘
     * @param width    宽
     * @param height   高
     * @param level    层级
     * @param shadow   是否画外侧 1px 阴影；调用方必须保证外侧有 1px 空间
     */
    public static void draw(GuiGraphics graphics, int x, int y, int width, int height,
            Level level, boolean shadow) {
        if (width <= 2 || height <= 2) {
            return;
        }
        FactoryGuiTheme theme = FactoryGuiTheme.current();
        graphics.fill(x, y, x + width, y + height, background(level, theme));
        int light = theme.panelBorderLight();
        graphics.fill(x, y, x + width, y + 1, light);
        graphics.fill(x, y, x + 1, y + height, light);
        int dark = theme.slotBorderDark();
        graphics.fill(x, y + height - 1, x + width, y + height, dark);
        graphics.fill(x + width - 1, y, x + width, y + height, dark);
        if (shadow) {
            graphics.fill(x + 1, y + height, x + width, y + height + 1, OUTER_SHADOW);
            graphics.fill(x + width, y + 1, x + width + 1, y + height + 1, OUTER_SHADOW);
        }
    }

    private static int background(Level level, FactoryGuiTheme theme) {
        return switch (level) {
            case WORK_AREA, IO -> theme.panelBg();
            case MACHINE -> theme.bevelLight();
        };
    }
}
