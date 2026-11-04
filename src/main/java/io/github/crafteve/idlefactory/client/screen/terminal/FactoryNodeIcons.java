package io.github.crafteve.idlefactory.client.screen.terminal;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 节点终端的像素字形：provider 键、编辑 / 删除键与定位键共用。
 *
 * <p>按钮不写字，图标全部用 1px 方块画，不生成贴图，也不依赖字体里是否有
 * {@code ⊘ ⇑ ⇄ ↻ ⌖} 这些字形。调用方给出键帽中心，字形按固定尺寸居中。</p>
 *
 * <p>三个 provider 字形按语义重画，而不是照搬字符：阻挡是圆环加斜杠，倍增是两枚向上的
 * 折线，逐台 / 轮询是一右一左两支箭头。上下叠放的 + / × 只用 5~6px，避免在小键帽里糊成
 * 一团。</p>
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
        int x = centerX;
        int y = centerY;
        graphics.fill(x - 2, y - 4, x + 3, y - 3, color);
        graphics.fill(x - 2, y + 3, x + 3, y + 4, color);
        graphics.fill(x - 4, y - 2, x - 3, y + 3, color);
        graphics.fill(x + 3, y - 2, x + 4, y + 3, color);
        graphics.fill(x - 3, y - 3, x - 2, y - 2, color);
        graphics.fill(x + 2, y - 3, x + 3, y - 2, color);
        graphics.fill(x - 3, y + 2, x - 2, y + 3, color);
        graphics.fill(x + 2, y + 2, x + 3, y + 3, color);
        for (int i = 0; i < 6; i++) {
            graphics.fill(x - 3 + i, y + 3 - i, x - 2 + i, y + 4 - i, color);
        }
    }

    /**
     * 倍增：两枚向上的折线加一根竖杆。
     *
     * @param graphics 画布
     * @param centerX  中心 x
     * @param centerY  中心 y
     * @param color    颜色
     */
    static void multiplier(GuiGraphics graphics, int centerX, int centerY, int color) {
        drawChevron(graphics, centerX, centerY - 3, color);
        drawChevron(graphics, centerX, centerY, color);
        graphics.fill(centerX, centerY, centerX + 1, centerY + 4, color);
    }

    private static void drawChevron(GuiGraphics graphics, int centerX, int top, int color) {
        for (int i = 0; i < 3; i++) {
            graphics.fill(centerX - 3 + i, top + i, centerX - 2 + i, top + i + 1, color);
            graphics.fill(centerX + 2 - i, top + i, centerX + 3 - i, top + i + 1, color);
        }
    }

    /**
     * 逐台 / 轮询：一右一左两支箭头。
     *
     * @param graphics 画布
     * @param centerX  中心 x
     * @param centerY  中心 y
     * @param color    颜色
     */
    static void dispatch(GuiGraphics graphics, int centerX, int centerY, int color) {
        graphics.fill(centerX - 4, centerY - 2, centerX + 2, centerY - 1, color);
        graphics.fill(centerX + 2, centerY - 3, centerX + 3, centerY - 2, color);
        graphics.fill(centerX + 2, centerY - 1, centerX + 3, centerY, color);
        graphics.fill(centerX + 3, centerY - 2, centerX + 4, centerY - 1, color);
        graphics.fill(centerX - 2, centerY + 2, centerX + 4, centerY + 3, color);
        graphics.fill(centerX - 2, centerY + 1, centerX - 1, centerY + 2, color);
        graphics.fill(centerX - 2, centerY + 3, centerX - 1, centerY + 4, color);
        graphics.fill(centerX - 3, centerY + 2, centerX - 2, centerY + 3, color);
    }

    /**
     * 轮询：顺时针圆环加箭头；逐台 / 轮询真正落地后用来表示轮询态。
     *
     * @param graphics 画布
     * @param centerX  中心 x
     * @param centerY  中心 y
     * @param color    颜色
     */
    static void roundRobin(GuiGraphics graphics, int centerX, int centerY, int color) {
        graphics.fill(centerX - 2, centerY - 4, centerX + 3, centerY - 3, color);
        graphics.fill(centerX - 2, centerY + 3, centerX + 3, centerY + 4, color);
        graphics.fill(centerX - 4, centerY - 2, centerX - 3, centerY + 3, color);
        graphics.fill(centerX + 3, centerY - 2, centerX + 4, centerY + 3, color);
        graphics.fill(centerX + 2, centerY - 5, centerX + 4, centerY - 3, color);
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
        graphics.fill(centerX - 1, centerY - 1, centerX + 2, centerY + 2, color);
        graphics.fill(centerX - 4, centerY, centerX - 2, centerY + 1, color);
        graphics.fill(centerX + 3, centerY, centerX + 5, centerY + 1, color);
        graphics.fill(centerX, centerY - 4, centerX + 1, centerY - 2, color);
        graphics.fill(centerX, centerY + 3, centerX + 1, centerY + 5, color);
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
        graphics.fill(centerX - 3, centerY - 1, centerX + 4, centerY + 1, color);
        graphics.fill(centerX - 1, centerY - 3, centerX + 1, centerY + 4, color);
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
        for (int i = -2; i <= 2; i++) {
            graphics.fill(centerX + i, centerY + i, centerX + i + 1, centerY + i + 1, color);
            graphics.fill(centerX + i, centerY - i, centerX + i + 1, centerY - i + 1, color);
        }
    }
}
