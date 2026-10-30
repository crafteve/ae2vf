package io.github.crafteve.idlefactory.client.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 顶栏的产线下拉：选线、新建、以及每条线的二级菜单（重命名/启动/暂停/删除）。
 *
 * <p>列表可滚动，底部“新建产线”行固定；每行右侧的箭头只负责打开二级菜单，不改变当前选中。
 * 所有动作通过 {@link Callbacks} 交给控制器，本类只做布局、绘制和命中。</p>
 *
 * <p>几何常量在 {@link FactoryLayout}；这里只按文本算宽度，并夹在面板内。</p>
 */
public final class FactoryLineMenu {
    /** 下拉对外的动作。 */
    public interface Callbacks {
        /** 选中一条产线；{@code null} 表示清空。 */
        void select(@Nullable UUID lineId);

        /** 打开新建产线浮层。 */
        void create();

        /** 打开重命名浮层。 */
        void rename(FactorySnapshotBuilder.LineView line);

        /** 确认删除产线。 */
        void delete(FactorySnapshotBuilder.LineView line);

        /** 启动 / 暂停。 */
        void setRunning(FactorySnapshotBuilder.LineView line, boolean running);
    }

    /** 二级菜单动作 id。 */
    private static final int SUB_RENAME = 0;
    private static final int SUB_START = 1;
    private static final int SUB_PAUSE = 2;
    private static final int SUB_DELETE = 3;

    private final Callbacks callbacks;
    private final Submenu submenu = new Submenu();

    private boolean open;
    private int anchorX;
    private int anchorY;
    private int x;
    private int y;
    private int width;
    private int height;
    private int listHeight;
    private int visibleRows;
    private int scroll;
    private FactoryLayout layout;
    private List<FactorySnapshotBuilder.LineView> lines = List.of();
    @Nullable
    private UUID selected;
    private boolean hasFactory;
    @Nullable
    private FactorySnapshotBuilder.LineView submenuLine;

    /**
     * @param callbacks 动作回调
     */
    public FactoryLineMenu(Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    /**
     * 打开下拉。
     *
     * @param layout   当前布局
     * @param anchorX  下拉左边缘
     * @param anchorY  下拉上边缘
     * @param lines    产线列表
     * @param selected 当前选中 id
     * @param hasFactory 玩家是否已有工厂
     */
    public void open(FactoryLayout layout, int anchorX, int anchorY,
            List<FactorySnapshotBuilder.LineView> lines, @Nullable UUID selected,
            boolean hasFactory) {
        this.layout = layout;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.selected = selected;
        this.hasFactory = hasFactory;
        this.lines = List.copyOf(lines);
        this.scroll = 0;
        this.submenu.close();
        this.submenuLine = null;
        this.open = true;
        this.width = 168;
        relayout();
    }

    /** 关闭下拉与二级菜单。 */
    public void close() {
        this.open = false;
        this.submenu.close();
        this.submenuLine = null;
    }

    /** 是否打开。 */
    public boolean isOpen() {
        return this.open;
    }

    /**
     * 快照更新时刷新列表；保持打开并按新数据夹紧滚动。
     *
     * @param lines    产线列表
     * @param selected 当前选中 id
     * @param hasFactory 玩家是否已有工厂
     */
    public void update(List<FactorySnapshotBuilder.LineView> lines, @Nullable UUID selected,
            boolean hasFactory) {
        this.lines = List.copyOf(lines);
        this.selected = selected;
        this.hasFactory = hasFactory;
        if (this.open) {
            relayout();
        }
        if (this.submenuLine != null) {
            FactorySnapshotBuilder.LineView refreshed = findLine(this.submenuLine.id());
            if (refreshed == null) {
                this.submenu.close();
                this.submenuLine = null;
            } else {
                // 快照每 20 tick 来一次，不能在这里关二级菜单，否则菜单永远点不完。
                this.submenuLine = refreshed;
            }
        }
    }

    private void relayout() {
        if (this.layout == null) {
            return;
        }
        int maxHeight = this.layout.lineMenuMaxHeight();
        this.x = Math.max(this.layout.panelX + FactoryLayout.PAD,
                Math.min(this.anchorX, this.layout.panelX + this.layout.panelW
                        - FactoryLayout.PAD - this.width));
        this.y = this.anchorY;
        this.visibleRows = Math.max(1,
                (maxHeight - (this.hasFactory ? FactoryLayout.LINE_ADD_ROW_H : 0)
                        - FactoryLayout.LINE_MENU_PAD * 2) / FactoryLayout.LINE_ROW_H);
        int contentRows = this.hasFactory && !this.lines.isEmpty()
                ? this.lines.size() : 1;
        int shownRows = Math.min(contentRows, this.visibleRows);
        this.listHeight = shownRows * FactoryLayout.LINE_ROW_H;
        this.height = FactoryLayout.LINE_MENU_PAD * 2 + this.listHeight
                + (this.hasFactory ? FactoryLayout.LINE_ADD_ROW_H : 0);
        this.scroll = Math.max(0, Math.min(this.scroll, Math.max(0, contentRows - this.visibleRows)));
    }

    /**
     * 画下拉和二级菜单。
     *
     * @param graphics 画布
     * @param font     字体
     * @param theme    主题
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     */
    public void draw(GuiGraphics graphics, Font font, FactoryGuiTheme theme, int mouseX, int mouseY) {
        if (!this.open || this.layout == null) {
            return;
        }
        this.width = Math.min(this.layout.lineMenuMaxWidth(), measureWidth(font));
        this.x = Math.max(this.layout.panelX + FactoryLayout.PAD,
                Math.min(this.anchorX, this.layout.panelX + this.layout.panelW
                        - FactoryLayout.PAD - this.width));
        relayout();

        FactoryNineSlice.fill(graphics, FactoryGuiTextures.DIVIDER_DARK, this.x + 2, this.y + 2,
                this.width, this.height);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.DIVIDER_DARK, this.x, this.y, this.width, 1);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.DIVIDER_DARK, this.x, this.y + this.height - 1,
                this.width, 1);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.DIVIDER_DARK, this.x, this.y, 1, this.height);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.DIVIDER_DARK, this.x + this.width - 1, this.y,
                1, this.height);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.TABLE_LIGHT, this.x + 1, this.y + 1,
                this.width - 2, 1);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.TABLE_LIGHT, this.x + 1, this.y + 1,
                1, this.height - 2);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.TABLE_LIGHT, this.x + 1, this.y + this.height - 2,
                this.width - 2, 1);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.TABLE_LIGHT, this.x + this.width - 2, this.y + 1,
                1, this.height - 2);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.HEADER_BG, this.x + 2, this.y + 2,
                this.width - 4, this.height - 4);

        int rowX = this.x + FactoryLayout.LINE_MENU_PAD;
        int rowW = this.width - FactoryLayout.LINE_MENU_PAD * 2;
        int rowY = this.y + FactoryLayout.LINE_MENU_PAD;
        if (!this.hasFactory) {
            graphics.drawString(font, "先执行 /idf create", rowX + 5, rowY + 4,
                    theme.textDim(), false);
        } else if (this.lines.isEmpty()) {
            graphics.drawString(font, "还没有产线", rowX + 5, rowY + 4, theme.textDim(), false);
        } else {
            int end = Math.min(this.lines.size(), this.scroll + this.visibleRows);
            for (int i = this.scroll; i < end; i++) {
                FactorySnapshotBuilder.LineView line = this.lines.get(i);
                drawLineRow(graphics, font, theme, mouseX, mouseY, line, rowX, rowY, rowW);
                rowY += FactoryLayout.LINE_ROW_H;
            }
            drawScrollbar(graphics);
        }

        if (this.hasFactory) {
            int addY = this.y + this.height - FactoryLayout.LINE_ADD_ROW_H
                    - FactoryLayout.LINE_MENU_PAD;
            FactoryNineSlice.button(graphics, rowX, addY, rowW, FactoryLayout.LINE_ADD_ROW_H - 3);
            boolean hovered = mouseX >= rowX && mouseX < rowX + rowW
                    && mouseY >= addY && mouseY < addY + FactoryLayout.LINE_ADD_ROW_H - 3;
            if (hovered) {
                FactoryNineSlice.fill(graphics, FactoryGuiTextures.ROW_ALT, rowX + 1, addY + 1,
                        rowW - 2, FactoryLayout.LINE_ADD_ROW_H - 5);
            }
            graphics.drawString(font, "+ 新建产线", rowX + 5, addY + 3,
                    hovered ? theme.textValue() : theme.textPrimary(), false);
        }

        this.submenu.draw(graphics, font, theme, mouseX, mouseY);
    }

    /**
     * 一行产线的状态灯：暂停红、睡眠灰、运行绿。
     *
     * @param line 产线
     * @return 状态灯贴图
     */
    private static net.minecraft.resources.ResourceLocation statusLamp(
            FactorySnapshotBuilder.LineView line) {
        if (!line.running()) {
            return FactoryGuiTextures.STATUS_LAMP_ERROR;
        }
        return line.sleeping() ? FactoryGuiTextures.STATUS_LAMP_IDLE : FactoryGuiTextures.STATUS_LAMP_RUN;
    }

    private void drawLineRow(GuiGraphics graphics, Font font, FactoryGuiTheme theme,
            int mouseX, int mouseY, FactorySnapshotBuilder.LineView line, int rowX, int rowY,
            int rowW) {
        FactoryNineSlice.button(graphics, rowX, rowY, rowW, FactoryLayout.LINE_ROW_H - 2);
        boolean selectedRow = line.id().equals(this.selected);
        boolean hovered = mouseX >= rowX && mouseX < rowX + rowW
                && mouseY >= rowY && mouseY < rowY + FactoryLayout.LINE_ROW_H - 2;
        if (selectedRow || hovered) {
            FactoryNineSlice.fill(graphics, FactoryGuiTextures.ROW_ALT, rowX + 1, rowY + 1,
                    rowW - 2, FactoryLayout.LINE_ROW_H - 4);
        }
        FactoryNineSlice.icon(graphics,
                statusLamp(line),
                rowX + 5, rowY + 6, FactoryGuiTextures.STATUS_LAMP_SIZE);

        boolean scrollable = this.lines.size() > this.visibleRows;
        int caretX = rowX + rowW - 5 - FactoryLayout.LINE_CARET_W
                - (scrollable ? 4 : 0);
        String count = line.machineCount() + " 台";
        int countX = caretX - 4 - font.width(count);
        int nameX = rowX + 13;
        int nameWidth = Math.max(10, countX - nameX - 4);
        graphics.drawString(font, font.plainSubstrByWidth(line.name(), nameWidth),
                nameX, rowY + 4,
                selectedRow ? theme.textValue() : theme.textPrimary(), false);
        graphics.drawString(font, count, countX, rowY + 4, theme.textDim(), false);
        FactoryNineSlice.icon(graphics, FactoryGuiTextures.CHEVRON_RIGHT, caretX, rowY + 5,
                FactoryGuiTextures.CHEVRON_SIZE);
    }

    /** 列表内容超过可视行数时，在右缘画一条 2px 滚动条。 */
    private void drawScrollbar(GuiGraphics graphics) {
        if (this.lines.size() <= this.visibleRows) {
            return;
        }
        int trackX = this.x + this.width - 5;
        int trackY = this.y + FactoryLayout.LINE_MENU_PAD + 1;
        int trackH = this.listHeight - 2;
        int content = this.lines.size() * FactoryLayout.LINE_ROW_H;
        int thumbH = Math.max(8, trackH * this.listHeight / Math.max(1, content));
        int thumbY = trackY + (trackH - thumbH) * this.scroll
                / Math.max(1, this.lines.size() - this.visibleRows);
        graphics.fill(trackX, trackY, trackX + 2, trackY + trackH, 0x40000000);
        graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0xFF4D6A8A);
    }

    /**
     * 处理点击。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @param button 鼠标键
     * @return 是否消费
     */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.open) {
            return false;
        }
        if (this.submenu.isOpen()) {
            if (this.submenu.contains(mouseX, mouseY)) {
                Integer hit = this.submenu.hit(mouseX, mouseY);
                if (hit != null) {
                    handleSubmenu(hit);
                }
                return true;
            }
            this.submenu.close();
        }
        if (button != 0 || !inside(mouseX, mouseY, this.x, this.y, this.width, this.height)) {
            if (inside(mouseX, mouseY, this.x, this.y, this.width, this.height)) {
                return true;
            }
            this.close();
            return false;
        }
        int rowX = this.x + FactoryLayout.LINE_MENU_PAD;
        int rowW = this.width - FactoryLayout.LINE_MENU_PAD * 2;
        if (this.hasFactory) {
            int addY = this.y + this.height - FactoryLayout.LINE_ADD_ROW_H
                    - FactoryLayout.LINE_MENU_PAD;
            if (mouseY >= addY && mouseY < addY + FactoryLayout.LINE_ADD_ROW_H - 3) {
                this.close();
                this.callbacks.create();
                return true;
            }
        }
        if (!this.lines.isEmpty()) {
            int listTop = this.y + FactoryLayout.LINE_MENU_PAD;
            int index = (int) ((mouseY - listTop) / FactoryLayout.LINE_ROW_H);
            int absolute = this.scroll + index;
            if (mouseY >= listTop && absolute >= 0 && absolute < this.lines.size()
                    && mouseY < listTop + this.listHeight) {
                FactorySnapshotBuilder.LineView line = this.lines.get(absolute);
                boolean scrollable = this.lines.size() > this.visibleRows;
                int caretX = rowX + rowW - 5 - FactoryLayout.LINE_CARET_W
                        - (scrollable ? 4 : 0);
                if (mouseX >= caretX - 2 && mouseX < rowX + rowW) {
                    openSubmenu(line, index, caretX);
                    return true;
                }
                this.selected = line.id();
                this.close();
                this.callbacks.select(line.id());
                return true;
            }
        }
        return true;
    }

    private void openSubmenu(FactorySnapshotBuilder.LineView line, int index, int caretX) {
        this.submenuLine = line;
        List<Submenu.Item> items = new ArrayList<>(4);
        items.add(new Submenu.Item("重命名", Submenu.Kind.ACTION, SUB_RENAME));
        items.add(new Submenu.Item("启动",
                line.running() ? Submenu.Kind.DISABLED : Submenu.Kind.ACTION, SUB_START));
        items.add(new Submenu.Item("暂停",
                line.running() ? Submenu.Kind.ACTION : Submenu.Kind.DISABLED, SUB_PAUSE));
        items.add(new Submenu.Item("删除产线", Submenu.Kind.DANGER, SUB_DELETE));
        int subW = 92;
        int rowY = this.y + FactoryLayout.LINE_MENU_PAD + index * FactoryLayout.LINE_ROW_H;
        int subX = this.x + this.width - 2;
        if (subX + subW > this.layout.panelX + this.layout.panelW - FactoryLayout.PAD) {
            subX = this.x - subW + 2;
        }
        int subH = Submenu.measureHeight(items, FactoryLayout.SUBMENU_ROW_H);
        int subY = Math.max(this.layout.panelY + FactoryLayout.PAD,
                Math.min(rowY, this.layout.panelY + this.layout.panelH
                        - FactoryLayout.PAD - subH));
        this.submenu.open(subX, subY, subW, FactoryLayout.SUBMENU_ROW_H, items);
    }

    private void handleSubmenu(int id) {
        FactorySnapshotBuilder.LineView line = this.submenuLine;
        this.submenu.close();
        this.submenuLine = null;
        if (line == null) {
            return;
        }
        switch (id) {
            case SUB_RENAME -> {
                this.close();
                this.callbacks.rename(line);
            }
            case SUB_START -> {
                this.close();
                this.callbacks.setRunning(line, true);
            }
            case SUB_PAUSE -> {
                this.close();
                this.callbacks.setRunning(line, false);
            }
            case SUB_DELETE -> {
                this.close();
                this.callbacks.delete(line);
            }
            default -> {
                // 未知 id：只收起菜单。
            }
        }
    }

    /**
     * 处理滚轮。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @param delta  滚动量
     * @return 是否消费
     */
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!this.open || !inside(mouseX, mouseY, this.x, this.y, this.width, this.height)) {
            return false;
        }
        if (this.lines.size() <= this.visibleRows) {
            return true;
        }
        int max = this.lines.size() - this.visibleRows;
        this.scroll = Math.max(0, Math.min(max, this.scroll + (delta > 0 ? -1 : 1)));
        return true;
    }

    private int measureWidth(Font font) {
        int max = font.width("+ 新建产线") + 24;
        max = Math.max(max, font.width("先执行 /idf create") + 18);
        max = Math.max(max, font.width("还没有产线") + 18);
        for (FactorySnapshotBuilder.LineView line : this.lines) {
            int row = 13 + font.width(line.name()) + 6 + font.width(line.machineCount() + " 台")
                    + 8 + FactoryLayout.LINE_CARET_W + 12;
            max = Math.max(max, row);
        }
        return max;
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width,
            int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    @Nullable
    private FactorySnapshotBuilder.LineView findLine(UUID id) {
        for (FactorySnapshotBuilder.LineView line : this.lines) {
            if (line.id().equals(id)) {
                return line;
            }
        }
        return null;
    }

    /**
     * 产线二级菜单：重命名 / 启动 / 暂停 / 删除。
     *
     * <p>原来是一个独立的通用菜单类，实际只有产线选择器使用；并回这里，避免
     * 「看起来通用、其实只有一个调用方」的误读。行样式是凸起按钮，与产线列表的
     * 整行样式不同。</p>
     */
    private static final class Submenu {
        /** 行内边距。 */
        private static final int PAD = 3;

        /** 菜单项种类。 */
        enum Kind {
            /** 普通可点动作。 */
            ACTION,
            /** 不可点，灰色。 */
            DISABLED,
            /** 危险动作，错误色。 */
            DANGER;

            /**
             * 这一项是否能点。
             *
             * @return 普通动作与危险项为 {@code true}
             */
            boolean clickable() {
                return this == ACTION || this == DANGER;
            }
        }

        /**
         * 一个菜单项。
         *
         * @param label 文案
         * @param kind  种类
         * @param id    调用方动作 id
         */
        record Item(String label, Kind kind, int id) {
        }

        private int x;
        private int y;
        private int width;
        private int rowHeight;
        private List<Item> items = List.of();
        private boolean open;

        void open(int x, int y, int width, int rowHeight, List<Item> items) {
            this.x = x;
            this.y = y;
            this.width = Math.max(40, width);
            this.rowHeight = Math.max(12, rowHeight);
            this.items = new ArrayList<>(items);
            this.open = true;
        }

        void close() {
            this.open = false;
            this.items = List.of();
        }

        boolean isOpen() {
            return this.open;
        }

        int height() {
            return measureHeight(this.items, this.rowHeight);
        }

        static int measureHeight(List<Item> items, int rowHeight) {
            return items.size() * rowHeight + PAD * 2;
        }

        boolean contains(double mouseX, double mouseY) {
            return this.open
                    && mouseX >= this.x && mouseX < this.x + this.width
                    && mouseY >= this.y && mouseY < this.y + height();
        }

        @Nullable
        Integer hit(double mouseX, double mouseY) {
            if (!contains(mouseX, mouseY)) {
                return null;
            }
            int index = (int) ((mouseY - this.y - PAD) / this.rowHeight);
            if (index < 0 || index >= this.items.size()) {
                return null;
            }
            Item item = this.items.get(index);
            return item.kind().clickable() ? item.id() : null;
        }

        void draw(GuiGraphics graphics, Font font, FactoryGuiTheme theme, int mouseX, int mouseY) {
            if (!this.open) {
                return;
            }
            int height = height();
            FactoryNineSlice.fill(graphics, FactoryGuiTextures.DIVIDER_DARK, this.x + 2, this.y + 2,
                    this.width, height);
            FactoryNineSlice.fill(graphics, FactoryGuiTextures.DIVIDER_DARK, this.x, this.y, this.width, 1);
            FactoryNineSlice.fill(graphics, FactoryGuiTextures.DIVIDER_DARK, this.x, this.y + height - 1,
                    this.width, 1);
            FactoryNineSlice.fill(graphics, FactoryGuiTextures.DIVIDER_DARK, this.x, this.y, 1, height);
            FactoryNineSlice.fill(graphics, FactoryGuiTextures.DIVIDER_DARK, this.x + this.width - 1,
                    this.y, 1, height);
            FactoryNineSlice.fill(graphics, FactoryGuiTextures.TABLE_LIGHT, this.x + 1, this.y + 1,
                    this.width - 2, 1);
            FactoryNineSlice.fill(graphics, FactoryGuiTextures.TABLE_LIGHT, this.x + 1, this.y + 1,
                    1, height - 2);
            FactoryNineSlice.fill(graphics, FactoryGuiTextures.TABLE_LIGHT, this.x + 1,
                    this.y + height - 2, this.width - 2, 1);
            FactoryNineSlice.fill(graphics, FactoryGuiTextures.TABLE_LIGHT,
                    this.x + this.width - 2, this.y + 1, 1, height - 2);
            FactoryNineSlice.fill(graphics, FactoryGuiTextures.HEADER_BG, this.x + 2, this.y + 2,
                    this.width - 4, height - 4);

            for (int i = 0; i < this.items.size(); i++) {
                Item item = this.items.get(i);
                int rowY = this.y + PAD + i * this.rowHeight;
                FactoryNineSlice.button(graphics, this.x + PAD, rowY,
                        this.width - PAD * 2, this.rowHeight - 2);
                boolean hovered = mouseX >= this.x + PAD
                        && mouseX < this.x + this.width - PAD
                        && mouseY >= rowY && mouseY < rowY + this.rowHeight - 2;
                if (hovered && item.kind().clickable()) {
                    FactoryNineSlice.fill(graphics, FactoryGuiTextures.ROW_ALT, this.x + PAD + 1,
                            rowY + 1, this.width - PAD * 2 - 2, this.rowHeight - 4);
                }
                int color;
                switch (item.kind()) {
                    case DISABLED -> color = theme.textDim();
                    case DANGER -> color = theme.error();
                    default -> color = hovered ? theme.textValue() : theme.textPrimary();
                }
                String label = font.plainSubstrByWidth(item.label(),
                        this.width - PAD * 2 - 8);
                graphics.drawString(font, label, this.x + PAD + 5, rowY + 3, color, false);
            }
        }
    }
}
