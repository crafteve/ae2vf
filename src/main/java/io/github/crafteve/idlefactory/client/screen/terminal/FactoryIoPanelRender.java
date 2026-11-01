package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.client.screen.FactoryLabels;
import io.github.crafteve.idlefactory.client.screen.FactoryNineSlice;
import io.github.crafteve.idlefactory.client.screen.FactoryResourceIconRender;
import io.github.crafteve.idlefactory.client.screen.FactoryStatus;
import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.factory.io.IoSide;
import io.github.crafteve.idlefactory.factory.machine.MatchMode;
import io.github.crafteve.idlefactory.factory.machine.ResourceSpec;
import io.github.crafteve.idlefactory.network.payload.FactoryRuleActionC2S;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 被动 IO 面板的绘制与几何：搜索框、机器列表、规则区与 JEI 命中区。
 *
 * <p>状态和输入在 {@link FactoryIoPanel}；这里只读它的字段，负责坐标、截断与命中计算。
 * 输入方法也复用这里算出的区域，保证「看到的位置」和「点到的位置」是同一套数字。</p>
 */
final class FactoryIoPanelRender {
    /** 排序按钮宽度。 */
    static final int SORT_WIDTH = 38;
    /** 机器行高。 */
    static final int MACHINE_ROW = 16;
    /** 规则行高。 */
    static final int RULE_ROW = 16;
    /** 规则区标题高度。 */
    static final int SECTION_HEADER = 11;
    /** 移除按钮命中宽度。 */
    static final int REMOVE_WIDTH = 10;

    private final FactoryIoPanel panel;

    /**
     * @param panel 所属面板
     */
    FactoryIoPanelRender(FactoryIoPanel panel) {
        this.panel = panel;
    }

    /**
     * 画搜索框、机器列表、规则区与反馈。
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
            if (selected) {
                graphics.fill(x, y, x + this.contentWidth(), y + MACHINE_ROW,
                        FactoryTerminalGeometry.ROW_SELECTED);
            } else if (hovered) {
                graphics.fill(x, y, x + this.contentWidth(), y + MACHINE_ROW,
                        FactoryTerminalGeometry.ROW_HOVER);
            }
            ItemStack icon = FactoryLabels.blockStack(machine.blockId());
            if (!icon.isEmpty()) {
                graphics.renderItem(icon, x, y);
            }
            int statusWidth = 34;
            String status = trim(this.panel.font, FactoryStatus.text(machine, this.panel.theme),
                    statusWidth);
            int nameWidth = this.contentWidth() - 20 - statusWidth - 2;
            graphics.drawString(this.panel.font,
                    trim(this.panel.font, FactoryLabels.blockName(machine.blockId()), nameWidth),
                    x + 18, y + 4, this.panel.theme.textPrimary(), false);
            graphics.drawString(this.panel.font, status,
                    x + this.contentWidth() - this.panel.font.width(status), y + 4,
                    this.panel.theme.textDim(), false);
        }
        graphics.disableScissor();
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
        renderSection(graphics, this.panel, mouseX, mouseY, x, top, width, sectionHeight,
                IoSide.INPUT, machine.inputs());
        renderSection(graphics, this.panel, mouseX, mouseY, x, top + sectionHeight, width,
                sectionHeight, IoSide.OUTPUT, machine.outputs());
    }

    private static void renderSection(GuiGraphics graphics, FactoryIoPanel panel, int mouseX,
            int mouseY, int x, int y, int width, int height, IoSide side,
            List<ResourceSpec> rules) {
        FactoryNineSlice.ioHeader(graphics, x, y, width, SECTION_HEADER);
        graphics.drawString(panel.font, side == IoSide.INPUT ? "输入" : "输出", x + 2, y + 2,
                panel.theme.textPrimary(), false);
        boolean addHovered = mouseX >= x + width - 12 && mouseX < x + width
                && mouseY >= y && mouseY < y + SECTION_HEADER;
        FactoryNineSlice.button(graphics, x + width - 12, y, 12, SECTION_HEADER);
        graphics.drawString(panel.font, "+", x + width - 9, y + 2,
                addHovered ? panel.theme.accent() : panel.theme.textPrimary(), false);
        int maxRows = Math.max(0, (height - SECTION_HEADER) / RULE_ROW);
        for (int i = 0; i < Math.min(maxRows, rules.size()); i++) {
            ResourceSpec rule = rules.get(i);
            int rowY = y + SECTION_HEADER + i * RULE_ROW;
            boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= rowY
                    && mouseY < rowY + RULE_ROW;
            if (hovered) {
                graphics.fill(x, rowY, x + width, rowY + RULE_ROW,
                        FactoryTerminalGeometry.ROW_HOVER);
            }
            FactoryResourceIconRender.draw(graphics, panel.font, rule.key(), x + 1, rowY);
            int removeX = x + width - REMOVE_WIDTH;
            int labelWidth = removeX - (x + 19) - 16;
            graphics.drawString(panel.font,
                    trim(panel.font, FactoryLabels.name(rule.key()), labelWidth), x + 19,
                    rowY + 4, panel.theme.textPrimary(), false);
            String face = FactoryLabels.face(rule);
            graphics.drawString(panel.font, face,
                    removeX - 2 - panel.font.width(face), rowY + 4, panel.theme.textFace(), false);
            graphics.drawString(panel.font, "×", removeX + 1, rowY + 4, panel.theme.error(),
                    false);
        }
        if (rules.size() > maxRows) {
            graphics.drawString(panel.font, "+" + (rules.size() - maxRows), x + 2, y + height - 10,
                    panel.theme.textDim(), false);
        }
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
     * 按像素宽度截断文字，末尾加省略号。
     *
     * @param font  字体
     * @param text  原文
     * @param width 最大宽度
     * @return 截断后的文字
     */
    private static String trim(net.minecraft.client.gui.Font font, String text, int width) {
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
