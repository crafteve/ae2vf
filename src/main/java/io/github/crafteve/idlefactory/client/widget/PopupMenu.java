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
 * <p>产线下拉的二级菜单与右区排序菜单都用它；外框/阴影一致，行样式由 {@link Style} 选择：
 * 二级菜单用独立凸起按钮，排序菜单用整块列表（行间 1px 分隔线、选中/悬停整行填底）。
 * 它只负责几何/绘制/命中，不执行动作；调用方拿 {@link Item#id()} 自己分发。</p>
 *
 * <p>菜单项用 {@link Kind} 表达语义，而不是若干个布尔参数：普通动作、当前选中项、危险动作、
 * 置灰项、分组标题。分组标题不画按钮底，只占一行高度并画一条分隔线；选中项在左侧画一条
 * 强调条。这样排序菜单能同时表达「排序方式」分组和「当前选中的排序键」，而产线下拉的行为
 * 保持不变。</p>
 *
 * <p>坐标是屏幕绝对坐标；宽度由调用方按文本算好并夹在面板内。</p>
 */
public final class PopupMenu {
    /** 行内边距。 */
    private static final int PAD = 3;
    /** 分组标题行的高度；普通行仍由调用方传入。 */
    private static final int HEADER_H = 12;

    /**
     * 行样式。
     */
    public enum Style {
        /** 每行独立凸起按钮；产线下拉的二级菜单使用。 */
        BUTTONS,
        /** 整块列表：行间 1px 分隔线，选中/悬停整行填底；排序菜单使用。 */
        LIST
    }

    /**
     * 菜单项种类。
     *
     * <p>它同时决定是否可点与用什么颜色画，避免调用方传 {@code enabled/danger/selected}
     * 三个布尔值。</p>
     */
    public enum Kind {
        /** 普通可点动作。 */
        ACTION,
        /** 可点，且是当前选中项：左侧强调条 + 青色文字。 */
        ACTION_SELECTED,
        /** 可点，删除类动作，错误色。 */
        DANGER,
        /** 不可点，灰色文字。 */
        DISABLED,
        /** 分组标题：不画按钮底，只画一行灰字和底部 1px 分隔线。 */
        HEADER;

        /**
         * 这一项是否能被点击。
         *
         * @return 普通动作、选中项、危险项为 {@code true}
         */
        public boolean clickable() {
            return this == ACTION || this == ACTION_SELECTED || this == DANGER;
        }
    }

    /**
     * 一个菜单项。
     *
     * @param label 文案
     * @param kind  种类，决定颜色、可点性与是否画按钮底
     * @param id    调用方自定义的动作 id；标题与置灰项不会被命中
     */
    public record Item(String label, Kind kind, int id) {
    }

    private int x;
    private int y;
    private int width;
    private int rowHeight;
    private List<Item> items = List.of();
    private Style style = Style.BUTTONS;
    private boolean open;

    /**
     * 打开菜单（默认凸起按钮样式）。
     *
     * @param x         左上角 x
     * @param y         左上角 y
     * @param width     宽度
     * @param rowHeight 普通行高；分组标题固定使用内部高度
     * @param items     菜单项
     */
    public void open(int x, int y, int width, int rowHeight, List<Item> items) {
        open(x, y, width, rowHeight, items, Style.BUTTONS);
    }

    /**
     * 打开菜单并指定行样式。
     *
     * @param x         左上角 x
     * @param y         左上角 y
     * @param width     宽度
     * @param rowHeight 普通行高；分组标题固定使用内部高度
     * @param items     菜单项
     * @param style     行样式
     */
    public void open(int x, int y, int width, int rowHeight, List<Item> items, Style style) {
        this.x = x;
        this.y = y;
        this.width = Math.max(40, width);
        this.rowHeight = Math.max(12, rowHeight);
        this.items = new ArrayList<>(items);
        this.style = style == null ? Style.BUTTONS : style;
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
        return measureHeight(this.items, this.rowHeight);
    }

    /**
     * 计算一组菜单项的总高度。
     *
     * <p>供调用方在 {@link #open} 之前做纵向夹紧；分组标题按固定 12px 计，其余按行高计。</p>
     *
     * @param items     菜单项
     * @param rowHeight 普通行高
     * @return 含上下内边距的总高度
     */
    public static int measureHeight(List<Item> items, int rowHeight) {
        int height = PAD * 2;
        for (Item item : items) {
            height += heightOf(item, rowHeight);
        }
        return height;
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
     * @return 菜单项 id；没命中、标题或置灰项时为 {@code null}
     */
    @Nullable
    public Integer hit(double mouseX, double mouseY) {
        if (!contains(mouseX, mouseY)) {
            return null;
        }
        int cursor = this.y + PAD;
        for (Item item : this.items) {
            int rowH = heightOf(item, this.rowHeight);
            if (mouseY >= cursor && mouseY < cursor + rowH) {
                return item.kind().clickable() ? item.id() : null;
            }
            cursor += rowH;
        }
        return null;
    }

    /**
     * 画菜单；调用方保证它在表格之后、tooltip 之前或之后由自己决定。
     *
     * <p>菜单是浮层，必须高于物品图标（150）与自定义数量（300）。层级由调用方显式传入：
     * 已经处在浮层 pose 内的调用方传 {@link GuiLayer#BASE}，独立调用传
     * {@link GuiLayer#OVERLAY}。这样不会出现「父级已提升、子菜单再提升」叠到 tooltip
     * 之上的情况。</p>
     *
     * @param graphics 画布
     * @param font     字体
     * @param theme    主题
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     * @param layer    绘制层级
     */
    public void draw(GuiGraphics graphics, Font font, GuiTheme theme, int mouseX, int mouseY,
            GuiLayer layer) {
        if (!this.open) {
            return;
        }
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, layer.z());
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

        int cursor = this.y + PAD;
        for (int i = 0; i < this.items.size(); i++) {
            Item item = this.items.get(i);
            int rowH = heightOf(item, this.rowHeight);
            if (item.kind() == Kind.HEADER) {
                drawHeader(graphics, font, theme, item, cursor, rowH);
            } else if (this.style == Style.LIST) {
                drawListAction(graphics, font, theme, item, cursor, rowH,
                        mouseX, mouseY, i == this.items.size() - 1);
            } else {
                drawButtonAction(graphics, font, theme, item, cursor, rowH, mouseX, mouseY);
            }
            cursor += rowH;
        }
        graphics.pose().popPose();
    }

    private void drawHeader(GuiGraphics graphics, Font font, GuiTheme theme, Item item,
            int rowY, int rowH) {
        NineSlice.fill(graphics, GuiTextures.DIVIDER, this.x + PAD, rowY + rowH - 2,
                this.width - PAD * 2, 1);
        String label = font.plainSubstrByWidth(item.label(), this.width - PAD * 2 - 8);
        int textX = this.x + PAD + (this.style == Style.LIST ? 7 : 5);
        graphics.drawString(font, label, textX, rowY + 2, theme.textDim(), false);
    }

    /**
     * 列表行：整行填底，不画凸起按钮；选中行左侧加强调条，非末行画 1px 分隔线。
     */
    private void drawListAction(GuiGraphics graphics, Font font, GuiTheme theme, Item item,
            int rowY, int rowH, int mouseX, int mouseY, boolean last) {
        int x = this.x + PAD + 1;
        int w = this.width - PAD * 2 - 2;
        boolean hovered = mouseX >= x && mouseX < x + w
                && mouseY >= rowY && mouseY < rowY + rowH;
        boolean selected = item.kind() == Kind.ACTION_SELECTED;
        if (hovered || selected) {
            NineSlice.fill(graphics, GuiTextures.ROW_ALT, x, rowY, w, rowH - 1);
        }
        if (selected) {
            // 强调条压在填底之上；只表达选中，不替代 hover。
            graphics.fill(x + 1, rowY + 2, x + 3, rowY + rowH - 3, theme.accent());
        }
        if (!last) {
            NineSlice.fill(graphics, GuiTextures.DIVIDER, x + 2, rowY + rowH - 1,
                    w - 4, 1);
        }

        int color;
        switch (item.kind()) {
            case DISABLED -> color = theme.textDim();
            case DANGER -> color = theme.error();
            case ACTION_SELECTED -> color = theme.textValue();
            default -> color = hovered ? theme.textValue() : theme.textPrimary();
        }
        String label = font.plainSubstrByWidth(item.label(), w - 12);
        graphics.drawString(font, label, x + 6, rowY + (rowH - 9) / 2 + 1, color, false);
    }

    private void drawButtonAction(GuiGraphics graphics, Font font, GuiTheme theme, Item item,
            int rowY, int rowH, int mouseX, int mouseY) {
        int buttonH = rowH - 2;
        NineSlice.button(graphics, this.x + PAD, rowY, this.width - PAD * 2, buttonH);

        boolean hovered = mouseX >= this.x + PAD && mouseX < this.x + this.width - PAD
                && mouseY >= rowY && mouseY < rowY + buttonH;
        if (hovered && item.kind().clickable()) {
            NineSlice.fill(graphics, GuiTextures.ROW_ALT, this.x + PAD + 1, rowY + 1,
                    this.width - PAD * 2 - 2, buttonH - 2);
        }
        if (item.kind() == Kind.ACTION_SELECTED) {
            // 强调条最后画，压在 hover 底色之上；只表达选中，不替代 hover。
            graphics.fill(this.x + PAD + 2, rowY + 1,
                    this.x + PAD + 4, rowY + buttonH - 1, theme.accent());
        }

        int color;
        switch (item.kind()) {
            case DISABLED -> color = theme.textDim();
            case DANGER -> color = theme.error();
            case ACTION_SELECTED -> color = theme.textValue();
            default -> color = hovered ? theme.textValue() : theme.textPrimary();
        }
        String label = font.plainSubstrByWidth(item.label(), this.width - PAD * 2 - 8);
        graphics.drawString(font, label, this.x + PAD + 5, rowY + 3, color, false);
    }

    private static int heightOf(Item item, int rowHeight) {
        return item.kind() == Kind.HEADER ? HEADER_H : rowHeight;
    }
}
