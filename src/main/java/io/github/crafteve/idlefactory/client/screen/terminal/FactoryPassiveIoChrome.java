package io.github.crafteve.idlefactory.client.screen.terminal;

import io.github.crafteve.idlefactory.client.screen.FactoryCard;
import io.github.crafteve.idlefactory.client.screen.FactoryNineSlice;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 被动 IO 终端的静态 chrome：标题与搜索框底板。
 *
 * <p>只画不接收输入；坐标由调用方给出，chunk 内不再第二次算位置。搜索框的输入控件是
 * {@code FactorySearchEditBox}，底板必须画在控件之前（{@code drawBG}），否则会盖住文字。</p>
 */
final class FactoryPassiveIoChrome {
    /** 标题颜色：纯黑，跟正文一致。 */
    static final int TITLE_COLOR = 0xFF000000;

    private FactoryPassiveIoChrome() {
        // 纯静态绘制
    }

    /**
     * 画工作区大卡片：和其他卡片共用 {@link FactoryCard} 的光照与阴影，只换层级底色。
     *
     * @param graphics 画布
     * @param x        左边缘
     * @param y        上边缘
     * @param width    宽
     * @param height   高
     */
    static void drawWorkAreaFrame(GuiGraphics graphics, int x, int y, int width, int height) {
        FactoryCard.draw(graphics, x, y, width, height, FactoryCard.Level.WORK_AREA, true);
    }

    /**
     * 画标题。
     *
     * @param graphics 画布
     * @param font     字体
     * @param leftPos  面板左边缘
     * @param topPos   面板上边缘
     */
    static void drawTitle(GuiGraphics graphics, Font font, int leftPos, int topPos) {
        graphics.drawString(font, "被动 IO", leftPos + 8, topPos + 5, TITLE_COLOR, false);
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
    static void drawSearchField(GuiGraphics graphics, int x, int y, int width, int height) {
        // AE2 原生搜索框贴图：亮色边框 + 内凹阴影，和样板管理终端同款。
        FactoryNineSlice.ae2TextField(graphics, x, y, width);
    }
}
