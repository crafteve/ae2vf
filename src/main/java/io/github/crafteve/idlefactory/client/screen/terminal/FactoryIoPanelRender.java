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
import io.github.crafteve.idlefactory.factory.machine.MatchMode;
import io.github.crafteve.idlefactory.factory.machine.ResourceSpec;

import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;

/**
 * 被动 IO 面板的绘制与几何：搜索行、机器行、行内展开的输入 / 输出规则卡。
 *
 * <p>一台机器一行，展开区直接插在该行下面，后面的机器整体下移；规则卡固定 26 高，
 * 每段末尾一张空卡作为拖入目标，超出可视高度时按像素滚动。绘制与命中用同一套
 * {@link RowRect} / {@link CardRect} 计算，避免「看到的位置」和「点到的位置」不一致。</p>
 */
final class FactoryIoPanelRender {
    /** 排序按钮宽度。 */
    static final int SORT_WIDTH = 46;
    /** 机器行高度。 */
    static final int ROW_H = 26;
    /** 展开区段头高度。 */
    static final int SECTION_H = 11;
    /** 规则卡高度。 */
    static final int CARD_H = 26;
    /** 行与行之间的间距。 */
    static final int ROW_GAP = 2;
    /** 展开区最大高度。 */
    static final int DETAIL_MAX_H = 176;
    /** 两段之间的间距。 */
    static final int SECTION_GAP = 4;
    /** 卡片内图标槽边长。 */
    static final int ICON_SLOT = 16;
    /** 深色段头上的文字颜色。 */
    private static final int HEADER_TEXT = 0xFFE8ECF5;
    /** 行内输入 / 输出列最多画几个图标。 */
    private static final int STRIP_ICONS = 3;

    /** 展开区命中编码。 */
    static final class Hit {
        /** 没命中。 */
        static final int NONE = -1;
        /** 输入段加号。 */
        static final int ADD_INPUT = -2;
        /** 输出段加号。 */
        static final int ADD_OUTPUT = -3;
        /** 输入空卡。 */
        static final int EMPTY_INPUT = -4;
        /** 输出空卡。 */
        static final int EMPTY_OUTPUT = -5;
        /** 输入规则下标偏移。 */
        static final int INPUT_BASE = 1000;
        /** 输出规则下标偏移。 */
        static final int OUTPUT_BASE = 2000;

        private Hit() {
            // 纯常量持有类
        }
    }

    /** 一行的绝对位置。 */
    record RowRect(int viewIndex, int y, int rowBottom, int detailY, int detailH) {
    }

    /** 一张展开卡的绝对位置。 */
    record CardView(int x, int y, int width, boolean input, int ruleIndex, boolean empty) {
    }

    private final FactoryIoPanel panel;

    /**
     * @param panel 所属面板
     */
    FactoryIoPanelRender(FactoryIoPanel panel) {
        this.panel = panel;
    }

    /**
     * 画搜索行、机器行与展开区。
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

    private void renderSearch(GuiGraphics graphics) {
        int x = this.contentLeft();
        int y = this.contentTop();
        int width = this.searchWidth();
        FactoryNineSlice.field(graphics, x, y, width, FactoryTerminalGeometry.SEARCH_HEIGHT);
        String text = this.panel.searchText.isEmpty() && !this.panel.searchFocused
                ? "搜索机器" : this.panel.searchText;
        int color = this.panel.searchText.isEmpty() && !this.panel.searchFocused
                ? this.panel.theme.textDim() : this.panel.theme.textPrimary();
        graphics.drawString(this.panel.font, text, x + 3, y + 3, color, false);
        if (this.panel.searchFocused && (Util.getMillis() / 500L) % 2L == 0L) {
            int caretX = x + 3 + this.panel.font.width(this.panel.searchText);
            graphics.fill(caretX, y + 2, caretX + 1, y + 10, this.panel.theme.textPrimary());
        }
        int sortX = x + width + 4;
        String label = this.sortLabel();
        FactoryNineSlice.sortButton(graphics, sortX, y, SORT_WIDTH,
                FactoryTerminalGeometry.SEARCH_HEIGHT);
        graphics.drawString(this.panel.font, label,
                sortX + (SORT_WIDTH - this.panel.font.width(label)) / 2, y + 3,
                this.panel.theme.textPrimary(), false);
    }

    /**
     * 画一台机器：卡片、状态条、图标、名字、状态、输入/输出列、展开箭头。
     *
     * @param graphics 绘制器
     * @param machine  机器
     * @param row      行位置
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     */
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
        FactoryNineSlice.slot(graphics, x + 3, row.y() + 5, ICON_SLOT, ICON_SLOT);
        ItemStack icon = FactoryLabels.blockStack(machine.blockId());
        if (!icon.isEmpty()) {
            graphics.renderItem(icon, x + 3, row.y() + 5);
        }
        int nameX = x + 3 + ICON_SLOT + 3;
        int nameWidth = 150;
        graphics.drawString(this.panel.font,
                trim(this.panel.font, FactoryLabels.blockName(machine.blockId()), nameWidth),
                nameX, row.y() + 4, this.panel.theme.textPrimary(), false);
        graphics.drawString(this.panel.font,
                trim(this.panel.font, ruleSummary(machine), nameWidth),
                nameX, row.y() + 14, this.panel.theme.textDim(), false);
        int statusX = x + 180;
        FactoryNineSlice.icon(graphics, status.lamp(), statusX, row.y() + 9,
                FactoryGuiTextures.STATUS_LAMP_SIZE);
        graphics.drawString(this.panel.font,
                trim(this.panel.font, status.text(), 66), statusX + 6, row.y() + 7,
                status.color(), false);
        this.drawStrip(graphics, machine.inputs(), x + 256, row.y() + 5);
        this.drawStrip(graphics, machine.outputs(), x + 336, row.y() + 5);
        String chevron = selected ? "▾" : "▸";
        graphics.drawString(this.panel.font, chevron, x + width - 12, row.y() + 8,
                this.panel.theme.textPrimary(), false);
    }

    /**
     * 画一列的规则图标；超过三个时用 +N 收尾。
     *
     * @param graphics 绘制器
     * @param rules    规则
     * @param x        左边界
     * @param y        上边界
     */
    private void drawStrip(GuiGraphics graphics, List<ResourceSpec> rules, int x, int y) {
        if (rules.isEmpty()) {
            graphics.drawString(this.panel.font, "—", x, y + 4, this.panel.theme.textDim(), false);
            return;
        }
        int shown = Math.min(STRIP_ICONS, rules.size());
        for (int i = 0; i < shown; i++) {
            FactoryResourceIconRender.draw(graphics, this.panel.font, rules.get(i).key(),
                    x + i * (ICON_SLOT + 1), y);
        }
        if (rules.size() > shown) {
            graphics.drawString(this.panel.font, "+" + (rules.size() - shown),
                    x + shown * (ICON_SLOT + 1), y + 4, this.panel.theme.textDim(), false);
        }
    }

    /**
     * 画展开区：两个段头 + 规则卡 + 空卡，按 detailScroll 纵向滚动。
     *
     * @param graphics 绘制器
     * @param machine  机器
     * @param row      行位置
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     */
    private void drawDetail(GuiGraphics graphics, FactorySnapshotBuilder.MachineView machine,
            RowRect row, int mouseX, int mouseY) {
        int x = this.contentLeft();
        int width = this.contentWidth();
        graphics.enableScissor(x, row.detailY(), x + width, row.detailY() + row.detailH());
        int y = row.detailY() - this.panel.detailScroll;
        for (IoSide side : IoSide.values()) {
            List<ResourceSpec> rules = side == IoSide.INPUT ? machine.inputs() : machine.outputs();
            this.drawSectionHeader(graphics, side, x, y, width, mouseX, mouseY);
            y += SECTION_H;
            for (int i = 0; i < rules.size(); i++) {
                this.drawRuleCard(graphics, side, i, rules.get(i), x, y, width, mouseX, mouseY);
                y += CARD_H;
            }
            this.drawEmptyCard(graphics, side, x, y, width, mouseX, mouseY);
            y += CARD_H + SECTION_GAP;
        }
        graphics.disableScissor();
    }

    private void drawSectionHeader(GuiGraphics graphics, IoSide side, int x, int y, int width,
            int mouseX, int mouseY) {
        FactoryNineSlice.ioHeader(graphics, x, y, width, SECTION_H);
        graphics.drawString(this.panel.font, side == IoSide.INPUT ? "输入" : "输出", x + 2, y + 2,
                HEADER_TEXT, false);
        boolean addHovered = mouseX >= x + width - 12 && mouseX < x + width
                && mouseY >= y && mouseY < y + SECTION_H;
        FactoryNineSlice.button(graphics, x + width - 12, y, 12, SECTION_H);
        graphics.drawString(this.panel.font, "+", x + width - 9, y + 2,
                addHovered ? this.panel.theme.accent() : HEADER_TEXT, false);
    }

    private void drawRuleCard(GuiGraphics graphics, IoSide side, int index, ResourceSpec rule,
            int x, int y, int width, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y
                && mouseY < y + CARD_H;
        FactoryNineSlice.card(graphics, x, y, width, CARD_H - 1);
        if (hovered) {
            graphics.fill(x, y, x + width, y + CARD_H - 1, FactoryTerminalGeometry.ROW_HOVER);
        }
        FactoryNineSlice.slot(graphics, x + 2, y + 5, ICON_SLOT, ICON_SLOT);
        FactoryResourceIconRender.draw(graphics, this.panel.font, rule.key(), x + 2, y + 5);
        int removeX = x + width - 12;
        int faceWidth = this.panel.font.width(FactoryLabels.face(rule));
        int nameWidth = removeX - (x + 22) - faceWidth - 8;
        graphics.drawString(this.panel.font,
                trim(this.panel.font, FactoryLabels.name(rule.key()), nameWidth), x + 22, y + 9,
                this.panel.theme.textPrimary(), false);
        graphics.drawString(this.panel.font, FactoryLabels.face(rule),
                removeX - 2 - faceWidth, y + 9, this.panel.theme.textFace(), false);
        graphics.drawString(this.panel.font, "×", removeX + 1, y + 9, this.panel.theme.error(),
                false);
        String match = rule.mode() == MatchMode.IGNORE_COMPONENTS ? "模糊" : "精确";
        graphics.drawString(this.panel.font, match, x + width - 60, y + 9,
                this.panel.theme.textDim(), false);
    }

    private void drawEmptyCard(GuiGraphics graphics, IoSide side, int x, int y, int width,
            int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y
                && mouseY < y + CARD_H;
        FactoryNineSlice.card(graphics, x, y, width, CARD_H - 1);
        if (hovered) {
            graphics.fill(x, y, x + width, y + CARD_H - 1, FactoryTerminalGeometry.ROW_HOVER);
        }
        graphics.drawString(this.panel.font, "+", x + 3, y + 9,
                hovered ? this.panel.theme.accent() : this.panel.theme.textDim(), false);
        graphics.drawString(this.panel.font,
                "拖入资源，或光标拿物品点这里", x + 20, y + 9,
                this.panel.theme.textDim(), false);
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

    int searchWidth() {
        return this.contentWidth() - SORT_WIDTH - 4;
    }

    /**
     * 鼠标是否在搜索框上。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @return 命中时为 {@code true}
     */
    boolean inSearch(double mouseX, double mouseY) {
        int x = this.contentLeft();
        int y = this.contentTop();
        return mouseX >= x && mouseX < x + this.searchWidth()
                && mouseY >= y && mouseY < y + FactoryTerminalGeometry.SEARCH_HEIGHT;
    }

    /**
     * 鼠标是否在排序按钮上。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @return 命中时为 {@code true}
     */
    boolean inSort(double mouseX, double mouseY) {
        int x = this.contentLeft() + this.searchWidth() + 4;
        int y = this.contentTop();
        return mouseX >= x && mouseX < x + SORT_WIDTH
                && mouseY >= y && mouseY < y + FactoryTerminalGeometry.SEARCH_HEIGHT;
    }

    int listTop() {
        return this.contentTop() + FactoryTerminalGeometry.SEARCH_HEIGHT + 6;
    }

    int listBottom() {
        return this.panel.bottom() - FactoryTerminalGeometry.CONTENT_PADDING;
    }

    /**
     * 当前可视行及其展开区位置。
     *
     * @return 行位置表
     */
    List<RowRect> rowRects() {
        List<RowRect> rows = new ArrayList<>();
        int y = this.listTop();
        int bottom = this.listBottom();
        for (int i = this.panel.firstRow; i < this.panel.visible.size(); i++) {
            FactorySnapshotBuilder.MachineView machine = this.panel.visible.get(i);
            int detailH = 0;
            if (isExpanded(machine)) {
                int max = Math.max(0, bottom - (y + ROW_H + ROW_GAP));
                detailH = Math.min(Math.min(this.detailContentHeight(machine), DETAIL_MAX_H), max);
            }
            if (y + ROW_H > bottom) {
                break;
            }
            rows.add(new RowRect(i, y, y + ROW_H, y + ROW_H, detailH));
            y += ROW_H + ROW_GAP + detailH;
        }
        return rows;
    }

    /**
     * 展开区的完整内容高度。
     *
     * @param machine 机器
     * @return 高度
     */
    int detailContentHeight(FactorySnapshotBuilder.MachineView machine) {
        int cards = machine.inputs().size() + 1 + machine.outputs().size() + 1;
        return cards * CARD_H + 2 * SECTION_H + SECTION_GAP;
    }

    /**
     * 展开区可滚动像素。
     *
     * @param machine 机器
     * @return 最大滚动
     */
    int detailMaxScroll(FactorySnapshotBuilder.MachineView machine) {
        for (RowRect row : this.rowRects()) {
            if (row.detailH() > 0 && this.panel.visible.get(row.viewIndex()) == machine) {
                return Math.max(0, this.detailContentHeight(machine) - row.detailH());
            }
        }
        return 0;
    }

    /**
     * 可见机器行数。
     *
     * @return 行数
     */
    int visibleRows() {
        return Math.max(1, (this.listBottom() - this.listTop()) / (ROW_H + ROW_GAP));
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
     * 命中展开区里的段头、规则卡或空卡。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @return {@link Hit} 常量或规则下标
     */
    int detailHit(double mouseX, double mouseY) {
        int x = this.contentLeft();
        int width = this.contentWidth();
        for (RowRect row : this.rowRects()) {
            if (row.detailH() <= 0 || mouseY < row.detailY()
                    || mouseY >= row.detailY() + row.detailH()) {
                continue;
            }
            FactorySnapshotBuilder.MachineView machine = this.panel.visible.get(row.viewIndex());
            int y = row.detailY() - this.panel.detailScroll;
            for (IoSide side : IoSide.values()) {
                List<ResourceSpec> rules = side == IoSide.INPUT ? machine.inputs() : machine.outputs();
                if (mouseY >= y && mouseY < y + SECTION_H) {
                    if (mouseX >= x + width - 12 && mouseX < x + width) {
                        return side == IoSide.INPUT ? Hit.ADD_INPUT : Hit.ADD_OUTPUT;
                    }
                    return Hit.NONE;
                }
                y += SECTION_H;
                for (int i = 0; i < rules.size(); i++) {
                    if (inCard(mouseX, mouseY, x, y, width)) {
                        if (mouseX >= x + width - 12) {
                            return (side == IoSide.INPUT ? Hit.INPUT_BASE : Hit.OUTPUT_BASE) + i;
                        }
                        return Hit.NONE;
                    }
                    y += CARD_H;
                }
                if (inCard(mouseX, mouseY, x, y, width)) {
                    return side == IoSide.INPUT ? Hit.EMPTY_INPUT : Hit.EMPTY_OUTPUT;
                }
                y += CARD_H + SECTION_GAP;
            }
        }
        return Hit.NONE;
    }

    private static boolean inCard(double mouseX, double mouseY, int x, int y, int width) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + CARD_H;
    }

    /**
     * 鼠标是否落在列表区。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @return 命中时为 {@code true}
     */
    boolean inPanel(double mouseX, double mouseY) {
        return mouseX >= this.contentLeft()
                && mouseX < this.contentLeft() + this.contentWidth()
                && mouseY >= this.listTop() && mouseY < this.listBottom();
    }

    /**
     * 鼠标是否落在展开区。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @return 命中时为 {@code true}
     */
    boolean inExpandedDetail(double mouseX, double mouseY) {
        for (RowRect row : this.rowRects()) {
            if (row.detailH() > 0 && mouseY >= row.detailY()
                    && mouseY < row.detailY() + row.detailH()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 鼠标下的机器行（不含展开区）。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @return 行位置或 {@code null}
     */
    @Nullable
    RowRect rowAtPoint(double mouseX, double mouseY) {
        if (mouseX < this.contentLeft()
                || mouseX >= this.contentLeft() + this.contentWidth()) {
            return null;
        }
        for (RowRect row : this.rowRects()) {
            if (mouseY >= row.y() && mouseY < row.rowBottom()) {
                return row;
            }
        }
        return null;
    }

    /**
     * 当前展开的行。
     *
     * @return 行位置或 {@code null}
     */
    @Nullable
    RowRect expandedRow() {
        for (RowRect row : this.rowRects()) {
            if (row.detailH() > 0) {
                return row;
            }
        }
        return null;
    }

    /**
     * 展开区里的卡片位置（含空卡）。
     *
     * @return 卡片列表
     */
    List<CardView> cardViews() {
        RowRect row = this.expandedRow();
        if (row == null) {
            return List.of();
        }
        FactorySnapshotBuilder.MachineView machine = this.panel.visible.get(row.viewIndex());
        List<CardView> cards = new ArrayList<>();
        int y = row.detailY() - this.panel.detailScroll;
        for (IoSide side : IoSide.values()) {
            List<ResourceSpec> rules = side == IoSide.INPUT ? machine.inputs() : machine.outputs();
            y += SECTION_H;
            for (int i = 0; i < rules.size(); i++) {
                cards.add(new CardView(this.contentLeft(), y, this.contentWidth(),
                        side == IoSide.INPUT, i, false));
                y += CARD_H;
            }
            cards.add(new CardView(this.contentLeft(), y, this.contentWidth(),
                    side == IoSide.INPUT, rules.size(), true));
            y += CARD_H + SECTION_GAP;
        }
        return cards;
    }

    /**
     * 展开区里输入 / 输出两段的落点区域。
     *
     * @return 两段区域；没有展开时为空
     */
    List<Rect2i> sectionAreas() {
        RowRect row = this.expandedRow();
        if (row == null) {
            return List.of();
        }
        FactorySnapshotBuilder.MachineView machine = this.panel.visible.get(row.viewIndex());
        int top = row.detailY() - this.panel.detailScroll;
        int inputHeight = SECTION_H + (machine.inputs().size() + 1) * CARD_H;
        int outputTop = top + inputHeight + SECTION_GAP;
        int outputHeight = SECTION_H + (machine.outputs().size() + 1) * CARD_H;
        int minY = row.detailY();
        int maxY = row.detailY() + row.detailH();
        Rect2i input = clip(this.contentLeft(), top, this.contentWidth(), inputHeight,
                minY, maxY);
        Rect2i output = clip(this.contentLeft(), outputTop, this.contentWidth(), outputHeight,
                minY, maxY);
        return List.of(input, output);
    }

    private static Rect2i clip(int x, int y, int width, int height, int minY, int maxY) {
        int top = Math.max(y, minY);
        int bottom = Math.min(y + height, maxY);
        return new Rect2i(x, top, width, Math.max(0, bottom - top));
    }

    /**
     * 某台机器是否处于展开状态。
     *
     * @param machine 机器
     * @return 是否展开
     */
    boolean isExpanded(FactorySnapshotBuilder.MachineView machine) {
        return machine.ref().id().equals(this.panel.selectedId);
    }

    private String sortLabel() {
        String key = switch (this.panel.sortKey) {
            case STATUS -> "状态";
            case RULES -> "规则";
            default -> "名称";
        };
        return key + (this.panel.descending ? "↓" : "↑");
    }

    private static String ruleSummary(FactorySnapshotBuilder.MachineView machine) {
        return "输入 " + machine.inputs().size() + " · 输出 " + machine.outputs().size();
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
