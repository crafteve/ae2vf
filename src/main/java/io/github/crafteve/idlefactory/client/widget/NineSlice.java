package io.github.crafteve.idlefactory.client.widget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * 九宫格绘制。
 *
 * <p>界面里的静态外观（面板、凹槽、按钮、输入框）全部来自贴图，这里只负责把一张固定尺寸的
 * 九宫格贴图切成九块贴到任意大小的矩形上——**不在代码里画边框、斜边、圆角**。</p>
 *
 * <p>为什么切九块而不是直接拉伸整张：直接拉伸会把四角的斜边也拉长，边框粗细就会随控件尺寸变化。
 * 九宫格保证四条边的粗细恒等于贴图里的像素数。</p>
 *
 * <p>纯 1×1 的贴图（分隔线、表头底色）用 {@link #fill} 拉伸，等价于一个没有边框的九宫格。</p>
 *
 * <p><b>注意</b>：{@code GuiGraphics.blit} 的源尺寸和目标尺寸是两组独立参数，不能混用——
 * 用「目标尺寸」当源尺寸去拉伸 1×1 贴图会采样越界，画出来是花屏。所有调用都显式给源矩形。</p>
 */
public final class NineSlice {
    private NineSlice() {
        // 纯静态工具类
    }

    // ------------------------------------------------------------------
    // 具名快捷方法：边框宽度写在这里，和 GuiTextures 里的常量对应
    // ------------------------------------------------------------------

    /** 画面板底。 */
    public static void panel(GuiGraphics graphics, int x, int y, int width, int height) {
        slice(graphics, GuiTextures.PANEL, GuiTextures.PANEL_SIZE, GuiTextures.PANEL_BORDER,
                x, y, width, height);
    }

    /** 画凹槽格子。 */
    public static void slot(GuiGraphics graphics, int x, int y, int width, int height) {
        slice(graphics, GuiTextures.SLOT, GuiTextures.SLOT_SIZE, 1, x, y, width, height);
    }

    /** 画凸起按钮。 */
    public static void button(GuiGraphics graphics, int x, int y, int width, int height) {
        slice(graphics, GuiTextures.BUTTON, GuiTextures.BUTTON_SIZE, 1, x, y, width, height);
    }

    /** 画输入框。 */
    public static void field(GuiGraphics graphics, int x, int y, int width, int height) {
        slice(graphics, GuiTextures.FIELD, GuiTextures.FIELD_SIZE, 1, x, y, width, height);
    }

    /** 画凸起表头。 */
    public static void header(GuiGraphics graphics, int x, int y, int width, int height) {
        slice(graphics, GuiTextures.TABLE_HEADER, GuiTextures.TABLE_FRAME_SIZE, 1,
                x, y, width, height);
    }

    /** 画行卡片。 */
    public static void card(GuiGraphics graphics, int x, int y, int width, int height) {
        slice(graphics, GuiTextures.ROW_CARD, GuiTextures.TABLE_FRAME_SIZE, 1,
                x, y, width, height);
    }

    /** 画机器库卡片。 */
    public static void machineCard(GuiGraphics graphics, int x, int y, int width, int height) {
        slice(graphics, GuiTextures.MACHINE_CARD, GuiTextures.MACHINE_CARD_SIZE, 1,
                x, y, width, height);
    }

    /** 画 IO 展开详情底板。 */
    public static void ioPanel(GuiGraphics graphics, int x, int y, int width, int height) {
        slice(graphics, GuiTextures.IO_PANEL, GuiTextures.IO_PANEL_SIZE,
                GuiTextures.IO_PANEL_BORDER, x, y, width, height);
    }

    /** 画 IO 规则单元格。 */
    public static void ioCell(GuiGraphics graphics, int x, int y, int width, int height) {
        slice(graphics, GuiTextures.IO_CELL, GuiTextures.IO_CELL_SIZE, 1, x, y, width, height);
    }

    /** 画 IO 规则单元格悬停底。 */
    public static void ioCellHover(GuiGraphics graphics, int x, int y, int width, int height) {
        slice(graphics, GuiTextures.IO_CELL_HOVER, GuiTextures.IO_CELL_SIZE, 1,
                x, y, width, height);
    }

    /** 画 IO 段头：深色卡片，下/右 2px 深阴影。 */
    public static void ioHeader(GuiGraphics graphics, int x, int y, int width, int height) {
        slice(graphics, GuiTextures.IO_HEADER, GuiTextures.IO_HEADER_SIZE,
                GuiTextures.IO_HEADER_BORDER, x, y, width, height);
    }

    /** 画按钮悬停底。 */
    public static void buttonHover(GuiGraphics graphics, int x, int y, int width, int height) {
        slice(graphics, GuiTextures.BUTTON_HOVER, GuiTextures.IO_CELL_SIZE, 1,
                x, y, width, height);
    }

    /** 画一张固定尺寸的小图标（不做九宫格拉伸）。 */
    public static void icon(GuiGraphics graphics, ResourceLocation texture, int x, int y, int size) {
        graphics.blit(texture, x, y, size, size, 0.0F, 0.0F, size, size, size, size);
    }

    /**
     * 用 1×1 贴图填一个矩形（分隔线、表头底色这类纯色块）。
     *
     * @param texture 1×1 贴图
     */
    public static void fill(GuiGraphics graphics, ResourceLocation texture,
            int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        graphics.blit(texture, x, y, width, height, 0.0F, 0.0F, 1, 1, 1, 1);
    }

    /**
     * 通用九宫格。
     *
     * <p>矩形小于两倍边框时退化成整张拉伸——宁可歪一点，也不要算出负宽度。
     *
     * @param texture 贴图
     * @param size    贴图边长（正方形）
     * @param border  边框像素数
     * @param x       目标左上角
     * @param y       目标上边
     * @param width   目标宽
     * @param height  目标高
     */
    public static void slice(GuiGraphics graphics, ResourceLocation texture, int size, int border,
            int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (border <= 0 || width < border * 2 || height < border * 2) {
            graphics.blit(texture, x, y, width, height, 0.0F, 0.0F, size, size, size, size);
            return;
        }

        int innerWidth = width - border * 2;
        int innerHeight = height - border * 2;
        int srcInner = size - border * 2;
        int far = size - border;

        // 四角（源尺寸 = 目标尺寸 = border）
        piece(graphics, texture, size, x, y, border, border, 0, 0);
        piece(graphics, texture, size, x + width - border, y, border, border, far, 0);
        piece(graphics, texture, size, x, y + height - border, border, border, 0, far);
        piece(graphics, texture, size, x + width - border, y + height - border,
                border, border, far, far);

        // 上下边（横向拉伸，源宽 = 内区宽）
        stretch(graphics, texture, size, x + border, y, innerWidth, border, border, 0, srcInner, border);
        stretch(graphics, texture, size, x + border, y + height - border,
                innerWidth, border, border, far, srcInner, border);

        // 左右边（纵向拉伸）
        stretch(graphics, texture, size, x, y + border, border, innerHeight, 0, border, border, srcInner);
        stretch(graphics, texture, size, x + width - border, y + border,
                border, innerHeight, far, border, border, srcInner);

        // 中间
        stretch(graphics, texture, size, x + border, y + border, innerWidth, innerHeight,
                border, border, srcInner, srcInner);
    }

    private static void piece(GuiGraphics graphics, ResourceLocation texture, int size,
            int x, int y, int width, int height, int u, int v) {
        stretch(graphics, texture, size, x, y, width, height, u, v, width, height);
    }

    private static void stretch(GuiGraphics graphics, ResourceLocation texture, int size,
            int x, int y, int width, int height, int u, int v, int srcWidth, int srcHeight) {
        if (width <= 0 || height <= 0 || srcWidth <= 0 || srcHeight <= 0) {
            return;
        }
        graphics.blit(texture, x, y, width, height, (float) u, (float) v,
                srcWidth, srcHeight, size, size);
    }
}
