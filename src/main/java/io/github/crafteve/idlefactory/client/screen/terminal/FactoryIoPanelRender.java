package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.client.screen.FactoryGuiTextures;
import io.github.crafteve.idlefactory.client.screen.FactoryLabels;
import io.github.crafteve.idlefactory.client.screen.FactoryNineSlice;
import io.github.crafteve.idlefactory.client.screen.FactoryResourceIconRender;
import io.github.crafteve.idlefactory.client.screen.FactoryStatus;
import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.factory.io.IoSide;
import io.github.crafteve.idlefactory.factory.machine.ResourceSpec;

import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 被动 IO 面板的绘制与几何：机器列表 + 行内展开的输入 / 输出标记格。
 *
 * <p>按 AE 总线的做法，配置只画标记格：输入一组、输出一组，一行 9 格，放满自动加行；
 * 状态、速率、面、精准模式全部放 tooltip，界面不画信息文字。中键点标记格弹配置框。</p>
 */
final class FactoryIoPanelRender {
    /** 机器行高度。 */
    static final int ROW_H = 22;
    /** 标记格边长。 */
    static final int SLOT = 18;
    /** 一行标记格数量。 */
    static final int COLS = 9;
    /** 输入 / 输出两组之间的间距。 */
    static final int GROUP_GAP = 4;
    /** 行与行之间的间距。 */
    static final int ROW_GAP = 2;
    /** 展开区最大高度。 */
    static final int DETAIL_MAX_H = 150;
    /** 配置框宽度。 */
    static final int POPUP_W = 126;
    /** 配置框行高。 */
    static final int POPUP_ROW = 16;
    /** 深色标题上的文字颜色。 */
    private static final int HEADER_TEXT = 0xFFE8ECF5;

    /** 命中编码。 */
    static final class Hit {
        /** 没命中。 */
        static final int NONE = -1;
        /** 输入空位。 */
        static final int EMPTY_INPUT = -2;
        /** 输出空位。 */
        static final int EMPTY_OUTPUT = -3;
        /** 输入规则下标偏移。 */
        static final int INPUT_BASE = 1000;
        /** 输出规则下标偏移。 */
        static final int OUTPUT_BASE = 2000;

        private Hit() {
            // 纯常量持有类
        }
    }

    /** 一行机器。 */
    record RowRect(int viewIndex, int y, int rowBottom, int detailY, int detailH) {
    }

    /** 一个标记格。 */
    record SlotRect(int x, int y, boolean input, int ruleIndex, boolean empty) {
    }

    private final FactoryIoPanel panel;

    /**
     * @param panel 所属面板
     */
    FactoryIoPanelRender(FactoryIoPanel panel) {
        this.panel = panel;
    }

    /**
     * 画搜索框、机器行与展开的标记格。
     *
     * @param graphics 绘制器
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     */
    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        this.renderSearch(graphics);
        if (!this.panel.hasFactory) {
            graphics.drawString(this.panel.font, "还没有工厂", this.contentLeft() + 2,
                    this.listTop(), this.panel.theme.textDim(), false);
            return;
        }
        if (this.panel.visible.isEmpty()) {
            graphics.drawString(this.panel.font,
                    this.panel.machines.isEmpty() ? "机器库为空" : "没有匹配的机器",
                    this.contentLeft() + 2, this.listTop(), this.panel.theme.textDim(), false);
            return;
        }
        for (RowRect row : this.rowRects()) {
            FactorySnapshotBuilder.MachineView machine = this.panel.visible.get(row.viewIndex());
            this.drawRow(graphics, machine, row, mouseX, mouseY);
            if (row.detailH() > 0) {
                this.drawDetail(graphics, machine, row, mouseX, mouseY);
            }
        }
        this.renderFeedback(graphics);
    }

    /**
     * tooltip 与中键配置框。
     *
     * @param graphics 绘制器
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     */
    void renderForeground(GuiGraphics graphics, int mouseX, int mouseY) {
        if (this.panel.popupOpen()) {
            this.drawPopup(graphics, mouseX, mouseY);
            return;
        }
        List<Component> lines = this.tooltipAt(mouseX, mouseY);
        if (!lines.isEmpty()) {
            graphics.renderTooltip(this.panel.font,
                    lines.stream().map(Component::getVisualOrderText).toList(), mouseX, mouseY);
        }
    }

    private void renderSearch(GuiGraphics graphics) {
        int x = this.contentLeft();
        int y = this.contentTop();
        FactoryNineSlice.field(graphics, x, y, this.contentWidth(),
                FactoryTerminalGeometry.SEARCH_HEIGHT);
        String text = this.panel.searchText.isEmpty() && !this.panel.searchFocused
                ? "搜索" : this.panel.searchText;
        int color = this.panel.searchText.isEmpty() && !this.panel.searchFocused
                ? this.panel.theme.textDim() : this.panel.theme.textPrimary();
        graphics.drawString(this.panel.font, text, x + 3, y + 3, color, false);
        if (this.panel.searchFocused && (Util.getMillis() / 500L) % 2L == 0L) {
            int caretX = x + 3 + this.panel.font.width(this.panel.searchText);
            graphics.fill(caretX, y + 2, caretX + 1, y + 10, this.panel.theme.textPrimary());
        }
    }

    private void drawRow(GuiGraphics graphics, FactorySnapshotBuilder.MachineView machine,
            RowRect row, int mouseX, int mouseY) {
        int x = this.contentLeft();
        int width = this.contentWidth();
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= row.y()
                && mouseY < row.rowBottom();
        boolean selected = isExpanded(machine);
        FactoryNineSlice.card(graphics, x, row.y(), width, ROW_H - 1);
        if (hovered || selected) {
            graphics.fill(x, row.y(), x + width, row.rowBottom() - 1,
                    selected ? FactoryTerminalGeometry.ROW_SELECTED
                            : FactoryTerminalGeometry.ROW_HOVER);
        }
        FactoryStatus status = FactoryStatus.of(machine, this.panel.theme);
        FactoryNineSlice.fill(graphics, status.strip(), x + 1, row.y() + 1, 1, ROW_H - 3);
        FactoryNineSlice.icon(graphics, status.lamp(), x + 3, row.y() + 9,
                FactoryGuiTextures.STATUS_LAMP_SIZE);
        FactoryNineSlice.slot(graphics, x + 10, row.y() + 3, 16, 16);
        ItemStack icon = FactoryLabels.blockStack(machine.blockId());
        if (!icon.isEmpty()) {
            graphics.renderItem(icon, x + 10, row.y() + 3);
        }
        graphics.drawString(this.panel.font,
                trim(this.panel.font, FactoryLabels.blockName(machine.blockId()), width - 44),
                x + 28, row.y() + 7, this.panel.theme.textPrimary(), false);
        graphics.drawString(this.panel.font, selected ? "▾" : "▸", x + width - 11,
                row.y() + 7, this.panel.theme.textPrimary(), false);
    }

    private void drawDetail(GuiGraphics graphics, FactorySnapshotBuilder.MachineView machine,
            RowRect row, int mouseX, int mouseY) {
        int x = this.contentLeft();
        int width = this.contentWidth();
        graphics.enableScissor(x, row.detailY(), x + width, row.detailY() + row.detailH());
        int y = row.detailY() - this.panel.detailScroll;
        y = this.drawGroup(graphics, machine, IoSide.INPUT, x, y, width, mouseX, mouseY);
        y += GROUP_GAP;
        this.drawGroup(graphics, machine, IoSide.OUTPUT, x, y, width, mouseX, mouseY);
        graphics.disableScissor();
    }

    private int drawGroup(GuiGraphics graphics, FactorySnapshotBuilder.MachineView machine,
            IoSide side, int x, int y, int width, int mouseX, int mouseY) {
        List<ResourceSpec> rules = side == IoSide.INPUT ? machine.inputs() : machine.outputs();
        int color = side == IoSide.INPUT ? this.panel.theme.accent() : this.panel.theme.statusWarn();
        graphics.fill(x, y, x + 2, y + this.groupHeight(rules.size()), color);
        int cells = rules.size() + 1;
        for (int i = 0; i < cells; i++) {
            int col = i % COLS;
            int line = i / COLS;
            int slotX = x + 4 + col * SLOT;
            int slotY = y + line * SLOT;
            boolean empty = i == rules.size();
            this.drawSlot(graphics, rules, side, i, empty, slotX, slotY, mouseX, mouseY);
        }
        return y + this.groupHeight(rules.size());
    }

    private void drawSlot(GuiGraphics graphics, List<ResourceSpec> rules, IoSide side, int index,
            boolean empty, int x, int y, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT;
        FactoryNineSlice.slot(graphics, x, y, SLOT, SLOT);
        if (hovered) {
            graphics.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1,
                    FactoryTerminalGeometry.ROW_HOVER);
        }
        if (empty) {
            graphics.drawString(this.panel.font, "+", x + 5, y + 5,
                    hovered ? this.panel.theme.accent() : this.panel.theme.textDim(), false);
            return;
        }
        FactoryResourceIconRender.draw(graphics, this.panel.font, rules.get(index).key(), x + 1,
                y + 1);
    }

    private void drawPopup(GuiGraphics graphics, int mouseX, int mouseY) {
        ResourceSpec rule = this.panel.popupRule();
        if (rule == null) {
            return;
        }
        int x = this.panel.popupX();
        int y = this.panel.popupY();
        graphics.fill(x, y, x + POPUP_W, y + POPUP_ROW * 3 + 6, 0xF0101820);
        graphics.renderOutline(x, y, POPUP_W, POPUP_ROW * 3 + 6, 0xFFE8ECF5);
        graphics.drawString(this.panel.font, "面：" + FactoryLabels.face(rule), x + 4,
                y + 4, HEADER_TEXT, false);
        graphics.drawString(this.panel.font,
                "匹配：" + (rule.mode().name().equals("IGNORE_COMPONENTS") ? "模糊" : "精准"),
                x + 4, y + 4 + POPUP_ROW, HEADER_TEXT, false);
        graphics.drawString(this.panel.font, "删除", x + 4, y + 4 + POPUP_ROW * 2,
                this.panel.theme.error(), false);
    }

    // ---- 命中 ----

    int popupHit(double mouseX, double mouseY) {
        if (!this.panel.popupOpen()) {
            return -1;
        }
        int x = this.panel.popupX();
        int y = this.panel.popupY();
        if (mouseX < x || mouseX >= x + POPUP_W || mouseY < y
                || mouseY >= y + POPUP_ROW * 3 + 6) {
            return -1;
        }
        return Math.min(2, (int) ((mouseY - y - 2) / POPUP_ROW));
    }

    /**
     * 命中的机器行在可见列表里的下标。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @return 可见下标；没命中为 {@code -1}
     */
    int rowAt(double mouseX, double mouseY) {
        if (mouseX < this.contentLeft() || mouseX >= this.contentLeft() + this.contentWidth()) {
            return -1;
        }
        for (RowRect row : this.rowRects()) {
            if (mouseY >= row.y() && mouseY < row.rowBottom()) {
                return row.viewIndex() - this.panel.firstRow;
            }
        }
        return -1;
    }

    /**
     * 命中的展开箭头。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @return 可见下标；没命中为 {@code -1}
     */
    int chevronAt(double mouseX, double mouseY) {
        int width = this.contentWidth();
        if (mouseX < this.contentLeft() + width - 18
                || mouseX >= this.contentLeft() + width) {
            return -1;
        }
        for (RowRect row : this.rowRects()) {
            if (mouseY >= row.y() && mouseY < row.rowBottom()) {
                return row.viewIndex() - this.panel.firstRow;
            }
        }
        return -1;
    }

    /**
     * 命中展开区里的标记格。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @return {@link Hit} 常量或规则下标
     */
    int slotHit(double mouseX, double mouseY) {
        RowRect row = this.expandedRow();
        if (row == null || mouseY < row.detailY() || mouseY >= row.detailY() + row.detailH()) {
            return Hit.NONE;
        }
        FactorySnapshotBuilder.MachineView machine = this.panel.visible.get(row.viewIndex());
        int x = this.contentLeft();
        int y = row.detailY() - this.panel.detailScroll;
        for (IoSide side : IoSide.values()) {
            List<ResourceSpec> rules = side == IoSide.INPUT ? machine.inputs() : machine.outputs();
            int cells = rules.size() + 1;
            for (int i = 0; i < cells; i++) {
                int col = i % COLS;
                int line = i / COLS;
                int slotX = x + 4 + col * SLOT;
                int slotY = y + line * SLOT;
                if (mouseX >= slotX && mouseX < slotX + SLOT && mouseY >= slotY
                        && mouseY < slotY + SLOT) {
                    if (i == rules.size()) {
                        return side == IoSide.INPUT ? Hit.EMPTY_INPUT : Hit.EMPTY_OUTPUT;
                    }
                    return (side == IoSide.INPUT ? Hit.INPUT_BASE : Hit.OUTPUT_BASE) + i;
                }
            }
            y += this.groupHeight(rules.size()) + GROUP_GAP;
        }
        return Hit.NONE;
    }

    // ---- 几何 ----

    int contentLeft() {
        return this.panel.left() + FactoryTerminalGeometry.CONTENT_PADDING;
    }

    int contentTop() {
        return this.panel.top() + FactoryTerminalGeometry.HEADER_HEIGHT + 15;
    }

    int contentWidth() {
        return FactoryTerminalGeometry.contentWidth();
    }

    int listTop() {
        return this.contentTop() + FactoryTerminalGeometry.SEARCH_HEIGHT + 6;
    }

    int listBottom() {
        return this.panel.bottom() - FactoryTerminalGeometry.CONTENT_PADDING;
    }

    List<RowRect> rowRects() {
        List<RowRect> rows = new ArrayList<>();
        int y = this.listTop();
        int bottom = this.listBottom();
        for (int i = this.panel.firstRow; i < this.panel.visible.size(); i++) {
            FactorySnapshotBuilder.MachineView machine = this.panel.visible.get(i);
            int detailH = 0;
            if (isExpanded(machine)) {
                int needed = this.detailContentHeight(machine);
                int max = Math.max(0, bottom - (y + ROW_H + ROW_GAP));
                detailH = Math.min(Math.min(needed, DETAIL_MAX_H), max);
            }
            if (y + ROW_H > bottom) {
                break;
            }
            rows.add(new RowRect(i, y, y + ROW_H, y + ROW_H, detailH));
            y += ROW_H + ROW_GAP + detailH;
        }
        return rows;
    }

    @Nullable
    RowRect expandedRow() {
        for (RowRect row : this.rowRects()) {
            if (row.detailH() > 0) {
                return row;
            }
        }
        return null;
    }

    int detailContentHeight(FactorySnapshotBuilder.MachineView machine) {
        return this.groupHeight(machine.inputs().size()) + GROUP_GAP
                + this.groupHeight(machine.outputs().size());
    }

    private int groupHeight(int rules) {
        int lines = (rules + 1 + COLS - 1) / COLS;
        return Math.max(1, lines) * SLOT;
    }

    int detailMaxScroll(FactorySnapshotBuilder.MachineView machine) {
        RowRect row = this.expandedRow();
        if (row == null) {
            return 0;
        }
        return Math.max(0, this.detailContentHeight(machine) - row.detailH());
    }

    int visibleRows() {
        return Math.max(1, (this.listBottom() - this.listTop()) / (ROW_H + ROW_GAP));
    }

    boolean inPanel(double mouseX, double mouseY) {
        return mouseX >= this.contentLeft()
                && mouseX < this.contentLeft() + this.contentWidth()
                && mouseY >= this.listTop() && mouseY < this.listBottom();
    }

    boolean inExpandedDetail(double mouseX, double mouseY) {
        for (RowRect row : this.rowRects()) {
            if (row.detailH() > 0 && mouseY >= row.detailY()
                    && mouseY < row.detailY() + row.detailH()) {
                return true;
            }
        }
        return false;
    }

    boolean inSearch(double mouseX, double mouseY) {
        int x = this.contentLeft();
        int y = this.contentTop();
        return mouseX >= x && mouseX < x + this.contentWidth()
                && mouseY >= y && mouseY < y + FactoryTerminalGeometry.SEARCH_HEIGHT;
    }

    /**
     * 展开区里输入 / 输出两组的落点。
     *
     * @return 两个矩形；没展开时为空
     */
    List<Rect2i> sectionAreas() {
        RowRect row = this.expandedRow();
        if (row == null) {
            return List.of();
        }
        FactorySnapshotBuilder.MachineView machine = this.panel.visible.get(row.viewIndex());
        int top = row.detailY() - this.panel.detailScroll;
        int inputHeight = this.groupHeight(machine.inputs().size());
        int outputTop = top + inputHeight + GROUP_GAP;
        int outputHeight = this.groupHeight(machine.outputs().size());
        return List.of(
                new Rect2i(this.contentLeft() + 4, top, COLS * SLOT, inputHeight),
                new Rect2i(this.contentLeft() + 4, outputTop, COLS * SLOT, outputHeight));
    }

    private void renderFeedback(GuiGraphics graphics) {
        if (this.panel.feedback == null || Util.getMillis() > this.panel.feedbackUntil) {
            return;
        }
        int color = this.panel.feedbackOk ? this.panel.theme.statusRun() : this.panel.theme.error();
        graphics.drawString(this.panel.font,
                trim(this.panel.font, this.panel.feedback, this.contentWidth() - 4),
                this.contentLeft() + 2, this.panel.bottom() - 12, color, false);
    }

    /**
     * 某台机器是否展开。
     *
     * @param machine 机器
     * @return 是否展开
     */
    boolean isExpanded(FactorySnapshotBuilder.MachineView machine) {
        return machine.ref().id().equals(this.panel.selectedId);
    }

    /**
     * 命中编码对应的标记格位置。
     *
     * @param hit 规则命中
     * @return 格子位置；命中无效时为 {@code null}
     */
    @Nullable
    SlotRect slotOf(int hit) {
        RowRect row = this.expandedRow();
        if (row == null || hit < Hit.INPUT_BASE) {
            return null;
        }
        FactorySnapshotBuilder.MachineView machine = this.panel.visible.get(row.viewIndex());
        boolean input = hit < Hit.OUTPUT_BASE;
        int index = input ? hit - Hit.INPUT_BASE : hit - Hit.OUTPUT_BASE;
        List<ResourceSpec> rules = input ? machine.inputs() : machine.outputs();
        if (index < 0 || index >= rules.size()) {
            return null;
        }
        int y = row.detailY() - this.panel.detailScroll;
        if (!input) {
            y += this.groupHeight(machine.inputs().size()) + GROUP_GAP;
        }
        int x = this.contentLeft() + 4 + (index % COLS) * SLOT;
        int slotY = y + (index / COLS) * SLOT;
        return new SlotRect(x, slotY, input, index, false);
    }

    private List<Component> tooltipAt(double mouseX, double mouseY) {
        int rowIndex = this.rowAt(mouseX, mouseY);
        if (rowIndex >= 0) {
            FactorySnapshotBuilder.MachineView machine =
                    this.panel.visible.get(this.panel.firstRow + rowIndex);
            FactoryStatus status = FactoryStatus.of(machine, this.panel.theme);
            return List.of(
                    Component.literal(FactoryLabels.blockName(machine.blockId())),
                    Component.literal(status.text()),
                    Component.literal("输入 " + machine.inputs().size() + " · 输出 "
                            + machine.outputs().size()),
                    Component.literal(machine.ref().dimension().location() + " "
                            + machine.ref().pos().toShortString()));
        }
        int hit = this.slotHit(mouseX, mouseY);
        if (hit >= Hit.INPUT_BASE) {
            FactorySnapshotBuilder.MachineView machine = this.panel.selected();
            if (machine == null) {
                return List.of();
            }
            boolean input = hit < Hit.OUTPUT_BASE;
            int index = input ? hit - Hit.INPUT_BASE : hit - Hit.OUTPUT_BASE;
            List<ResourceSpec> rules = input ? machine.inputs() : machine.outputs();
            if (index < 0 || index >= rules.size()) {
                return List.of();
            }
            ResourceSpec rule = rules.get(index);
            return List.of(
                    Component.literal((input ? "输入" : "输出") + " · "
                            + FactoryLabels.name(rule.key())),
                    Component.literal("面：" + FactoryLabels.face(rule)),
                    Component.literal("匹配："
                            + (rule.mode().name().equals("IGNORE_COMPONENTS") ? "模糊" : "精准")),
                    Component.literal("中键配置"));
        }
        return List.of();
    }

    private static String trim(Font font, String text, int width) {
        if (width <= 0) {
            return "";
        }
        if (font.width(text) <= width) {
            return text;
        }
        String ellipsis = "…";
        int budget = width - font.width(ellipsis);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (font.width(builder.toString() + c) > budget) {
                break;
            }
            builder.append(c);
        }
        return builder + ellipsis;
    }
}
