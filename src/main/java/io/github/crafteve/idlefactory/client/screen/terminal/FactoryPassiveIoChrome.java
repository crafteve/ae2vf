package io.github.crafteve.idlefactory.client.screen.terminal;

import io.github.crafteve.idlefactory.client.screen.FactoryCard;
import io.github.crafteve.idlefactory.client.screen.FactoryNineSlice;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * 被动 IO 终端的静态 chrome：标题、搜索框底板与滚动条滑槽。
 *
 * <p>只画不接收输入；坐标由调用方给出，chunk 内不再第二次算位置。搜索框的输入控件是
 * {@code FactorySearchEditBox}，底板必须画在控件之前（{@code drawBG}），否则会盖住文字。</p>
 */
final class FactoryPassiveIoChrome {
    /** 标题颜色：纯黑，跟正文一致。 */
    static final int TITLE_COLOR = 0xFF000000;

    /** AE2 终端贴图：滑槽原来烘焙在这张图的行切片里，右移只能从同一张图取像素重画。 */
    private static final ResourceLocation AE2_TERMINAL =
            ResourceLocation.fromNamespaceAndPath("ae2", "textures/guis/terminal.png");
    /** 滑槽宽（1px 亮边 + 4px 槽体 + 1px 亮边）。 */
    private static final int GROOVE_W = 6;
    /** 终端一张行切片的高度；滑槽首尾各取一片。 */
    private static final int ROW_SLICE_H = 18;
    /** terminal.png 里 firstRow / row / lastRow 切片的 y。 */
    private static final int FIRST_ROW_V = 17;
    private static final int ROW_V = 35;
    private static final int LAST_ROW_V = 53;
    /** terminal.png 的边长。 */
    private static final int TERMINAL_TEX = 256;

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

    /**
     * 在右移后的位置重画滚动条滑槽。
     *
     * <p>滑槽原来烘焙在 AE2 终端行贴图里，x 是固定的；内容区加宽后它必须跟着滑块一起右移。
     * 这里从同一张 {@code terminal.png} 取 firstRow / 中间行 / lastRow 三段切片，在目标位置
     * 拼出同样的上端暗口、槽体和下端亮边；原位置由调用方用面板色盖掉。</p>
     *
     * @param graphics 画布
     * @param x        滑槽左边缘（屏幕坐标）
     * @param y        滑槽上边缘（屏幕坐标）
     * @param height   滑槽高度（内容行数 × 行高）
     * @param sourceU  terminal.png 里滑槽的源 x（面板宽 - 17）
     */
    static void drawScrollbarGroove(GuiGraphics graphics, int x, int y, int height, int sourceU) {
        if (height < ROW_SLICE_H) {
            return;
        }
        graphics.blit(AE2_TERMINAL, x, y, GROOVE_W, ROW_SLICE_H,
                sourceU, FIRST_ROW_V, GROOVE_W, ROW_SLICE_H, TERMINAL_TEX, TERMINAL_TEX);
        int body = height - ROW_SLICE_H * 2;
        if (body > 0) {
            // 槽体在同一张图里是 1px 均匀竖线，纵向拉伸即可。
            graphics.blit(AE2_TERMINAL, x, y + ROW_SLICE_H, GROOVE_W, body,
                    sourceU, ROW_V, GROOVE_W, 1, TERMINAL_TEX, TERMINAL_TEX);
        }
        graphics.blit(AE2_TERMINAL, x, y + height - ROW_SLICE_H, GROOVE_W, ROW_SLICE_H,
                sourceU, LAST_ROW_V, GROOVE_W, ROW_SLICE_H, TERMINAL_TEX, TERMINAL_TEX);
    }
}
