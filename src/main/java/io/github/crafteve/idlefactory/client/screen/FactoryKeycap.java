package io.github.crafteve.idlefactory.client.screen;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 统一的键帽表面：1px 立体边 + 内区 + 悬停提亮 + 灰显遮罩。
 *
 * <p>被动 IO 终端的顺序 / 面 / 模式 chip 与节点终端的 provider 键共用这一套光照，不再各画一份。
 * 只画表面，不画字或图标：调用方负责在键帽内居中绘制标签或像素字形。</p>
 *
 * <p>边框方向固定为「上 / 左亮、下 / 右暗」。三枚键帽并排时，平涂边框会像贴纸；有了明暗才
 * 像按得下去的键。</p>
 */
public final class FactoryKeycap {
    /** 悬停提亮（白色 20%）。 */
    private static final int HOVER = 0x33FFFFFF;
    /** 灰显遮罩（白色 33%）。 */
    private static final int DISABLED = 0x55FFFFFF;

    private FactoryKeycap() {
        // 纯静态绘制
    }

    /**
     * 画一枚键帽。
     *
     * @param graphics   画布
     * @param theme      当前主题
     * @param x          左边缘
     * @param y          上边缘
     * @param width      宽
     * @param height     高
     * @param background 内区底色
     * @param enabled    是否可点
     * @param hovered    是否悬停
     */
    public static void draw(GuiGraphics graphics, FactoryGuiTheme theme, int x, int y,
            int width, int height, int background, boolean enabled, boolean hovered) {
        if (width <= 2 || height <= 2) {
            return;
        }
        int light = theme.panelBorderLight();
        int dark = theme.slotBorderDark();
        graphics.fill(x, y, x + width, y + 1, light);
        graphics.fill(x, y + 1, x + 1, y + height - 1, light);
        graphics.fill(x, y + height - 1, x + width, y + height, dark);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, dark);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, background);
        if (enabled && hovered) {
            // 高亮垫在图标 / 文字下面，避免半透明填充把内容盖灰。
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, HOVER);
        }
        if (!enabled) {
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, DISABLED);
        }
    }
}
