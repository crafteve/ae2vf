package io.github.crafteve.idlefactory.client.screen.terminal;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 节点终端的像素字形：provider 键、编辑 / 删除键与定位键共用。
 *
 * <p>按钮不写字，图标全部用 1px 方块画，不生成贴图，也不依赖字体里是否有 {@code ⊘ ⇑ ⇄ ↻ ⌖}
 * 这些字形。调用方给出键帽中心，字形按固定尺寸居中。</p>
 */
final class FactoryNodeIcons {
    private FactoryNodeIcons() {
        // 纯静态绘制
    }

    /**
     * 阻挡：圆环加一道斜杠。
     *
     * @param graphics 画布
     * @param centerX  中心 x
     * @param centerY  中心 y
     * @param color    颜色
     */
    static void blocking(GuiGraphics graphics, int centerX, int centerY, int color) {
        int x = centerX - 4;
        int y = centerY - 4;
        graphics.fill(x + 2, y, x + 7, y + 1, color);
        graphics.fill(x + 2, y + 8, x + 7, y + 9, color);
        graphics.fill(x, y + 2, x + 1, y + 7, color);
        graphics.fill(x + 8, y + 2, x + 9, y + 7, color);
        graphics.fill(x + 2, y + 1, x + 3, y + 2, color);
        graphics.fill(x + 6, y + 1, x + 7, y + 2, color);
        graphics.fill(x + 2, y + 7, x + 3, y + 8, color);
        graphics.fill(x + 6, y + 7, x + 7, y + 8, color);
        for (int i = 0; i < 6; i++) {
            graphics.fill(x + 2 + i, y + 2 + i, x + 3 + i, y + 3 + i, color);
        }
    }

    /**
     * 倍增：向上的双箭头。
     *
     * @param graphics 画布
     * @param centerX  中心 x
     * @param centerY  中心 y
     * @param color    颜色
     */
    static void multiplier(GuiGraphics graphics, int centerX, int centerY, int color) {
        int x = centerX - 3;
        int y = centerY - 4;
        for (int row = 0; row < 3; row++) {
            graphics.fill(x + row, y + row, x + row + 1, y + row + 2, color);
            graphics.fill(x + 6 - row, y + row, x + 7 - row, y + row + 2, color);
        }
        graphics.fill(centerX - 1, y + 3, centerX, y + 8, color);
        graphics.fill(centerX, y + 8, centerX + 1, y + 9, color);
    }

    /**
     * 逐台：一右一左两支箭头。
     *
     * @param graphics 画布
     * @param centerX  中心 x
     * @param centerY  中心 y
     * @param color    颜色
     */
    static void dispatch(GuiGraphics graphics, int centerX, int centerY, int color) {
        int x = centerX - 4;
        int top = centerY - 3;
        graphics.fill(x, top, x + 6, top + 1, color);
        graphics.fill(x + 6, top - 1, x + 7, top + 2, color);
        graphics.fill(x + 5, top - 1, x + 6, top, color);
        graphics.fill(x + 5, top + 1, x + 6, top + 2, color);
        int bottom = centerY + 2;
        graphics.fill(x + 2, bottom, x + 8, bottom + 1, color);
        graphics.fill(x + 2, bottom - 1, x + 3, bottom + 2, color);
        graphics.fill(x + 3, bottom - 1, x + 4, bottom, color);
        graphics.fill(x + 3, bottom + 1, x + 4, bottom + 2, color);
    }

    /**
     * 轮询：顺时针圆环加箭头。
     *
     * @param graphics 画布
     * @param centerX  中心 x
     * @param centerY  中心 y
     * @param color    颜色
     */
    static void roundRobin(GuiGraphics graphics, int centerX, int centerY, int color) {
        int x = centerX - 4;
        int y = centerY - 4;
        graphics.fill(x + 2, y, x + 7, y + 1, color);
        graphics.fill(x + 2, y + 8, x + 7, y + 9, color);
        graphics.fill(x, y + 2, x + 1, y + 7, color);
        graphics.fill(x + 8, y + 2, x + 9, y + 7, color);
        graphics.fill(x + 6, y - 1, x + 8, y + 1, color);
    }

    /**
     * 定位：准星。
     *
     * @param graphics 画布
     * @param centerX  中心 x
     * @param centerY  中心 y
     * @param color    颜色
     */
    static void locate(GuiGraphics graphics, int centerX, int centerY, int color) {
        int x = centerX - 4;
        int y = centerY - 4;
        graphics.fill(centerX - 1, centerY - 1, centerX + 1, centerY + 1, color);
        graphics.fill(x, y + 3, x + 2, y + 5, color);
        graphics.fill(x + 7, y + 3, x + 9, y + 5, color);
        graphics.fill(x + 3, y, x + 5, y + 2, color);
        graphics.fill(x + 3, y + 7, x + 5, y + 9, color);
    }

    /**
     * 加号：编辑。
     *
     * @param graphics 画布
     * @param centerX  中心 x
     * @param centerY  中心 y
     * @param color    颜色
     */
    static void plus(GuiGraphics graphics, int centerX, int centerY, int color) {
        graphics.fill(centerX - 4, centerY - 1, centerX + 4, centerY, color);
        graphics.fill(centerX - 1, centerY - 4, centerX, centerY + 4, color);
    }

    /**
     * 叉号：删除。
     *
     * @param graphics 画布
     * @param centerX  中心 x
     * @param centerY  中心 y
     * @param color    颜色
     */
    static void cross(GuiGraphics graphics, int centerX, int centerY, int color) {
        for (int i = -3; i <= 3; i++) {
            graphics.fill(centerX + i, centerY + i, centerX + i + 1, centerY + i + 1, color);
            graphics.fill(centerX + i, centerY - i, centerX + i + 1, centerY - i + 1, color);
        }
    }
}
