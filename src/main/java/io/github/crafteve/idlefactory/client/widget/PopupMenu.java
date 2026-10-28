package io.github.crafteve.idlefactory.client.widget;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.client.theme.GuiTheme;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 通用竖向浮层菜单。
 *
 * <p>产线下拉的二级菜单用它；样式与产线下拉一致：右下 2px 硬阴影、外层深色框、1px 白色内框、
 * 每一行是独立凸起按钮。它只负责几何/绘制/命中，不执行动作；调用方拿 {@link Item#id()} 自己分发。</p>
 *
 * <p>坐标是屏幕绝对坐标；宽度由调用方按文本算好并夹在面板内。</p>
 */
public final class PopupMenu {
    /** 行内边距。 */
    private static final int PAD = 3;

    /**
     * 一个菜单项。
     *
     * @param label   文案
     * @param enabled 是否可点
     * @param danger  是否用错误色（删除类）
     * @param id      调用方自定义的动作 id
     */
    public record Item(String label, boolean enabled, boolean danger, int id) {
    }

    private int x;
    private int y;
    private int width;
    private int rowHeight;
    private List<Item> items = List.of();
    private boolean open;

    /**
     * 打开菜单。
     *
     * @param x         左上角 x
     * @param y         左上角 y
     * @param width     宽度
     * @param rowHeight 行高
     * @param items     菜单项
     */
    public void open(int x, int y, int width, int rowHeight, List<Item> items) {
        this.x = x;
        this.y = y;
        this.width = Math.max(40, width);
        this.rowHeight = Math.max(12, rowHeight);
        this.items = new ArrayList<>(items);
        this.open = true;
    }

    /** 关闭菜单。 */
    public void close() {
        this.open = false;
        this.items = List.of();
    }

    /** 是否打开。 */
    public boolean isOpen() {
        return this.open;
    }

    /** 菜单总高。 */
    public int height() {
        return this.items.size() * this.rowHeight + PAD * 2;
    }

    /** 菜单宽。 */
    public int width() {
        return this.width;
    }

    /** 菜单左边缘。 */
    public int x() {
        return this.x;
    }

    /** 菜单上边缘。 */
    public int y() {
        return this.y;
    }

    /**
     * 鼠标是否落在菜单矩形内。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 在内部为 {@code true}
     */
    public boolean contains(double mouseX, double mouseY) {
        return this.open
                && mouseX >= this.x && mouseX < this.x + this.width
                && mouseY >= this.y && mouseY < this.y + height();
    }

    /**
     * 命中的菜单项。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 菜单项 id；没命中或项置灰时为 {@code null}
     */
    @Nullable
    public Integer hit(double mouseX, double mouseY) {
        if (!contains(mouseX, mouseY)) {
            return null;
        }
        int index = (int) ((mouseY - this.y - PAD) / this.rowHeight);
        if (index < 0 || index >= this.items.size()) {
            return null;
        }
        Item item = this.items.get(index);
        return item.enabled() ? item.id() : null;
    }

    /**
     * 画菜单；调用方保证它在表格之后、tooltip 之前或之后由自己决定。
     *
     * @param graphics 画布
     * @param font     字体
     * @param theme    主题
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     */
    public void draw(GuiGraphics graphics, Font font, GuiTheme theme, int mouseX, int mouseY) {
        if (!this.open) {
            return;
        }
        int height = height();
        NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK, this.x + 2, this.y + 2,
                this.width, height);
        NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK, this.x, this.y, this.width, 1);
        NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK, this.x, this.y + height - 1,
                this.width, 1);
        NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK, this.x, this.y, 1, height);
        NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK, this.x + this.width - 1, this.y,
                1, height);
        NineSlice.fill(graphics, GuiTextures.TABLE_LIGHT, this.x + 1, this.y + 1,
                this.width - 2, 1);
        NineSlice.fill(graphics, GuiTextures.TABLE_LIGHT, this.x + 1, this.y + 1,
                1, height - 2);
        NineSlice.fill(graphics, GuiTextures.TABLE_LIGHT, this.x + 1, this.y + height - 2,
                this.width - 2, 1);
        NineSlice.fill(graphics, GuiTextures.TABLE_LIGHT, this.x + this.width - 2, this.y + 1,
                1, height - 2);
        NineSlice.fill(graphics, GuiTextures.HEADER_BG, this.x + 2, this.y + 2,
                this.width - 4, height - 4);

        for (int i = 0; i < this.items.size(); i++) {
            Item item = this.items.get(i);
            int rowY = this.y + PAD + i * this.rowHeight;
            NineSlice.button(graphics, this.x + PAD, rowY, this.width - PAD * 2,
                    this.rowHeight - 2);
            boolean hovered = mouseX >= this.x + PAD && mouseX < this.x + this.width - PAD
                    && mouseY >= rowY && mouseY < rowY + this.rowHeight - 2;
            if (hovered && item.enabled()) {
                NineSlice.fill(graphics, GuiTextures.ROW_ALT, this.x + PAD + 1, rowY + 1,
                        this.width - PAD * 2 - 2, this.rowHeight - 4);
            }
            int color;
            if (!item.enabled()) {
                color = theme.textDim();
            } else if (item.danger()) {
                color = theme.error();
            } else {
                color = hovered ? theme.textValue() : theme.textPrimary();
            }
            String label = font.plainSubstrByWidth(item.label(), this.width - PAD * 2 - 8);
            graphics.drawString(font, label, this.x + PAD + 5, rowY + 3, color, false);
        }
    }
}
