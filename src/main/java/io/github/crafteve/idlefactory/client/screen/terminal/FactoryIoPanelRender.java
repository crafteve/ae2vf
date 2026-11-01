package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.List;

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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 被动 IO 面板的绘制与几何：搜索框、机器卡片、规则卡片与命中区。
 *
 * <p>状态与输入在 {@link FactoryIoPanel}；这里只读它的字段，负责坐标、截断与命中计算，
 * 输入方法复用同一套数字，保证「看到的位置」和「点到的位置」一致。视觉沿用旧工作台：
 * 卡片底、1px 状态条、状态灯、图标槽。</p>
 */
final class FactoryIoPanelRender {
    /** 排序按钮宽度。 */
    static final int SORT_WIDTH = 38;
    /** 机器卡片高度。 */
    static final int MACHINE_ROW = 26;
    /** 规则行高度。 */
    static final int RULE_ROW = 16;
    /** 分区标题高度。 */
    static final int SECTION_HEADER = 11;
    /** 移除按钮命中宽度。 */
    static final int REMOVE_WIDTH = 10;
    /** 卡片内图标槽边长。 */
    private static final int ICON_SLOT = 16;

    private final FactoryIoPanel panel;

    /**
     * @param panel 所属面板
     */
    FactoryIoPanelRender(FactoryIoPanel panel) {
        this.panel = panel;
    }

    /**
     * 画搜索框、机器卡片、规则区与反馈。
     *
     * @param graphics 绘制器
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     */
    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        this.renderSearch(graphics);
        this.renderMachineList(graphics, mouseX, mouseY);
        this.renderRules(graphics, mouseX, mouseY);
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

    private void renderMachineList(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = this.contentLeft();
        int top = this.machinesTop();
        int bottom = this.machinesBottom();
        if (!this.panel.hasFactory) {
            graphics.drawString(this.panel.font, "还没有工厂", x + 2, top + 2,
                    this.panel.theme.textDim(), false);
            return;
        }
        if (this.panel.visible.isEmpty()) {
            graphics.drawString(this.panel.font,
                    this.panel.machines.isEmpty() ? "机器库为空" : "没有匹配的机器",
                    x + 2, top + 2, this.panel.theme.textDim(), false);
            return;
        }
        graphics.enableScissor(x, top, x + this.contentWidth(), bottom);
        int rows = this.visibleRows();
        for (int row = 0; row < rows; row++) {
            int index = this.panel.firstRow + row;
            if (index >= this.panel.visible.size()) {
                break;
            }
            FactorySnapshotBuilder.MachineView machine = this.panel.visible.get(index);
            int y = top + row * MACHINE_ROW;
            boolean hovered = mouseX >= x && mouseX < x + this.contentWidth()
                    && mouseY >= y && mouseY < y + MACHINE_ROW;
            boolean selected = machine.ref().id().equals(this.panel.selectedId);
            this.drawMachineRow(graphics, machine, x, y, hovered, selected);
        }
        graphics.disableScissor();
    }

    /**
     * 画一张机器卡片：卡片底、状态条、图标槽、名字、规则数量、状态灯。
     *
     * @param graphics 绘制器
     * @param machine  机器视图
     * @param x        左边界
     * @param y        上边界
     * @param hovered  是否悬停
     * @param selected 是否选中
     */
    private void drawMachineRow(GuiGraphics graphics, FactorySnapshotBuilder.MachineView machine,
            int x, int y, boolean hovered, boolean selected) {
        int width = this.contentWidth();
        FactoryNineSlice.card(graphics, x, y, width, MACHINE_ROW - 1);
        if (hovered || selected) {
            graphics.fill(x, y, x + width, y + MACHINE_ROW - 1,
                    selected ? FactoryTerminalGeometry.ROW_SELECTED : FactoryTerminalGeometry.ROW_HOVER);
        }
        FactoryStatus status = FactoryStatus.of(machine, this.panel.theme);
        FactoryNineSlice.fill(graphics, status.strip(), x + 1, y + 1, 1, MACHINE_ROW - 3);
        FactoryNineSlice.slot(graphics, x + 3, y + 5, ICON_SLOT, ICON_SLOT);
        ItemStack icon = FactoryLabels.blockStack(machine.blockId());
        if (!icon.isEmpty()) {
            graphics.renderItem(icon, x + 3, y + 5);
        }
        int textX = x + 3 + ICON_SLOT + 3;
        int statusWidth = this.panel.font.width(status.text()) + 8;
        int nameWidth = Math.max(10, width - (textX - x) - statusWidth - 6);
        graphics.drawString(this.panel.font,
                trim(this.panel.font, FactoryLabels.blockName(machine.blockId()), nameWidth),
                textX, y + 4, this.panel.theme.textPrimary(), false);
        String rules = ruleSummary(machine);
        graphics.drawString(this.panel.font, trim(this.panel.font, rules, nameWidth),
                textX, y + 14, this.panel.theme.textDim(), false);
        FactoryNineSlice.icon(graphics, status.lamp(), x + width - statusWidth, y + 7,
                FactoryGuiTextures.STATUS_LAMP_SIZE);
        graphics.drawString(this.panel.font, status.text(), x + width - statusWidth + 6, y + 5,
                status.color(), false);
    }

    private void renderRules(GuiGraphics graphics, int mouseX, int mouseY) {
        FactorySnapshotBuilder.MachineView machine = this.panel.selected();
        if (machine == null) {
            return;
        }
        int x = this.contentLeft();
        int width = this.contentWidth();
        int top = this.rulesTop();
        int sectionHeight = (this.machinesBottom() - top) / 2;
        this.renderSection(graphics, machine, mouseX, mouseY, x, top, width, sectionHeight,
                IoSide.INPUT, machine.inputs());
        this.renderSection(graphics, machine, mouseX, mouseY, x, top + sectionHeight, width,
                sectionHeight, IoSide.OUTPUT, machine.outputs());
    }

    private void renderSection(GuiGraphics graphics, FactorySnapshotBuilder.MachineView machine,
            int mouseX, int mouseY, int x, int y, int width, int height, IoSide side,
            List<ResourceSpec> rules) {
        FactoryNineSlice.ioHeader(graphics, x, y, width, SECTION_HEADER);
        graphics.drawString(this.panel.font, side == IoSide.INPUT ? "输入" : "输出", x + 2, y + 2,
                this.panel.theme.textPrimary(), false);
        boolean addHovered = mouseX >= x + width - 12 && mouseX < x + width
                && mouseY >= y && mouseY < y + SECTION_HEADER;
        FactoryNineSlice.button(graphics, x + width - 12, y, 12, SECTION_HEADER);
        graphics.drawString(this.panel.font, "+", x + width - 9, y + 2,
                addHovered ? this.panel.theme.accent() : this.panel.theme.textPrimary(), false);
        int maxRows = Math.max(0, (height - SECTION_HEADER) / RULE_ROW);
        if (rules.isEmpty()) {
            graphics.drawString(this.panel.font, "光标拿物品点 +", x + 2, y + SECTION_HEADER + 2,
                    this.panel.theme.textDim(), false);
            return;
        }
        for (int i = 0; i < Math.min(maxRows, rules.size()); i++) {
            this.drawRuleRow(graphics, rules.get(i), x, y + SECTION_HEADER + i * RULE_ROW,
                    width, mouseX, mouseY);
        }
        if (rules.size() > maxRows) {
            graphics.drawString(this.panel.font, "+" + (rules.size() - maxRows), x + 2,
                    y + height - 10, this.panel.theme.textDim(), false);
        }
    }

    /**
     * 画一条规则卡片：图标、名字、面、移除按钮。
     *
     * @param graphics 绘制器
     * @param rule     规则
     * @param x        左边界
     * @param y        上边界
     * @param width    宽度
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     */
    private void drawRuleRow(GuiGraphics graphics, ResourceSpec rule, int x, int y, int width,
            int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y
                && mouseY < y + RULE_ROW;
        if (hovered) {
            graphics.fill(x, y, x + width, y + RULE_ROW, FactoryTerminalGeometry.ROW_HOVER);
        }
        FactoryNineSlice.slot(graphics, x + 1, y, ICON_SLOT, ICON_SLOT);
        FactoryResourceIconRender.draw(graphics, this.panel.font, rule.key(), x + 1, y);
        int removeX = x + width - REMOVE_WIDTH;
        int faceWidth = this.panel.font.width(FactoryLabels.face(rule));
        int labelWidth = removeX - (x + 20) - faceWidth - 10;
        graphics.drawString(this.panel.font,
                trim(this.panel.font, FactoryLabels.name(rule.key()), labelWidth), x + 20,
                y + 4, this.panel.theme.textPrimary(), false);
        graphics.drawString(this.panel.font, FactoryLabels.face(rule),
                removeX - 2 - faceWidth, y + 4, this.panel.theme.textFace(), false);
        graphics.drawString(this.panel.font, "×", removeX + 1, y + 4, this.panel.theme.error(),
                false);
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

    int machinesTop() {
        return this.contentTop() + FactoryTerminalGeometry.SEARCH_HEIGHT + 4;
    }

    int machinesBottom() {
        if (this.panel.selected() == null) {
            return this.panel.bottom() - FactoryTerminalGeometry.CONTENT_PADDING;
        }
        return this.panel.bottom() - FactoryTerminalGeometry.CONTENT_PADDING - this.rulesHeight();
    }

    int rulesTop() {
        return this.machinesBottom() + 2;
    }

    int rulesHeight() {
        return Math.min(130, Math.max(74, this.panel.height() / 3));
    }

    int visibleRows() {
        return Math.max(1, (this.machinesBottom() - this.machinesTop()) / MACHINE_ROW);
    }

    int machineRowAt(double mouseY) {
        return (int) ((mouseY - this.machinesTop()) / MACHINE_ROW);
    }

    boolean inSearch(double mouseX, double mouseY) {
        int x = this.contentLeft();
        int y = this.contentTop();
        return mouseX >= x && mouseX < x + this.searchWidth()
                && mouseY >= y && mouseY < y + FactoryTerminalGeometry.SEARCH_HEIGHT;
    }

    boolean inSort(double mouseX, double mouseY) {
        int x = this.contentLeft() + this.searchWidth() + 4;
        int y = this.contentTop();
        return mouseX >= x && mouseX < x + SORT_WIDTH
                && mouseY >= y && mouseY < y + FactoryTerminalGeometry.SEARCH_HEIGHT;
    }

    boolean inMachineList(double mouseX, double mouseY) {
        return mouseX >= this.contentLeft()
                && mouseX < this.contentLeft() + this.contentWidth()
                && mouseY >= this.machinesTop() && mouseY < this.machinesBottom();
    }

    /**
     * 命中规则区：返回 {@link RuleHit} 的常量或规则下标编码。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @return 命中结果
     */
    int ruleHit(double mouseX, double mouseY) {
        FactorySnapshotBuilder.MachineView machine = this.panel.selected();
        if (machine == null) {
            return RuleHit.NONE;
        }
        int x = this.contentLeft();
        int width = this.contentWidth();
        int top = this.rulesTop();
        int sectionHeight = (this.machinesBottom() - top) / 2;
        int hit = this.sectionHit(mouseX, mouseY, x, top, width, sectionHeight,
                IoSide.INPUT, machine.inputs(), RuleHit.INPUT_BASE);
        if (hit != RuleHit.NONE) {
            return hit;
        }
        return this.sectionHit(mouseX, mouseY, x, top + sectionHeight, width, sectionHeight,
                IoSide.OUTPUT, machine.outputs(), RuleHit.OUTPUT_BASE);
    }

    private int sectionHit(double mouseX, double mouseY, int x, int y, int width, int height,
            IoSide side, List<ResourceSpec> rules, int base) {
        if (mouseY < y || mouseY >= y + height) {
            return RuleHit.NONE;
        }
        if (mouseY < y + SECTION_HEADER) {
            if (mouseX >= x + width - 12 && mouseX < x + width) {
                return side == IoSide.INPUT ? RuleHit.ADD_INPUT : RuleHit.ADD_OUTPUT;
            }
            return RuleHit.NONE;
        }
        int index = (int) ((mouseY - y - SECTION_HEADER) / RULE_ROW);
        if (index < 0 || index >= rules.size()) {
            return RuleHit.NONE;
        }
        if (mouseX >= x + width - REMOVE_WIDTH && mouseX < x + width) {
            return base + index;
        }
        return RuleHit.NONE;
    }

    private String sortLabel() {
        String key = switch (this.panel.sortKey) {
            case STATUS -> "状态";
            case RULES -> "规则";
            default -> "名称";
        };
        return key + (this.panel.descending ? "↓" : "↑");
    }

    /**
     * 机器卡片的第二行：规则数量摘要。
     *
     * @param machine 机器视图
     * @return 摘要文字
     */
    private static String ruleSummary(FactorySnapshotBuilder.MachineView machine) {
        int inputs = machine.inputs().size();
        int outputs = machine.outputs().size();
        if (inputs == 0 && outputs == 0) {
            return "无规则";
        }
        return "输入 " + inputs + " · 输出 " + outputs;
    }

    /**
     * 按像素宽度截断文字，末尾加省略号。
     *
     * @param font  字体
     * @param text  原文
     * @param width 最大宽度
     * @return 截断后的文字
     */
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

    /** 规则区命中编码。 */
    static final class RuleHit {
        /** 没命中。 */
        static final int NONE = -1;
        /** 输入区加号。 */
        static final int ADD_INPUT = -2;
        /** 输出区加号。 */
        static final int ADD_OUTPUT = -3;
        /** 输入规则下标偏移。 */
        static final int INPUT_BASE = 0;
        /** 输出规则下标偏移；与输入错开，避免同一 block 被当成两侧下标。 */
        static final int OUTPUT_BASE = 1000;

        private RuleHit() {
            // 纯常量持有类
        }
    }
}
