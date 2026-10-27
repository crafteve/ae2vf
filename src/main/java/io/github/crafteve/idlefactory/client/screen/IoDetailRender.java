package io.github.crafteve.idlefactory.client.screen;

import java.util.ArrayList;
import java.util.List;

import io.github.crafteve.idlefactory.client.theme.GuiTheme;
import io.github.crafteve.idlefactory.client.widget.GuiTextures;
import io.github.crafteve.idlefactory.client.widget.NineSlice;
import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.factory.io.IoResult;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/**
 * 左表机器行的展开详情：输入/输出规则面板。
 *
 * <p>数据来自服务端快照里的 {@link FactorySnapshotBuilder.MachineView}，面板只做排版：
 * 每个配置规则生成一个单元格，显示图标、速率、面和最近一次诊断。槽位数量严格等于
 * 规则条数，0 条时只留一行「未配置」。</p>
 *
 * <p>绘制、tooltip 和 JEI 命中都必须先调用 {@link #layout}，否则三套坐标会各算各的。
 * 面板高度由动画进度决定，但只有整行单元格会画出来，避免半行贴图。</p>
 */
final class IoDetailRender {
    /** 深色段头上的冷白。 */
    private static final int HEADER_TEXT = 0xFFE8ECF5;
    /** 深色段头上的亮青数值。 */
    private static final int HEADER_VALUE = 0xFF4DE3FF;

    private IoDetailRender() {
        // 纯静态工具类
    }

    /**
     * 一个规则单元格。
     *
     * @param x         左边缘
     * @param y         上边缘
     * @param width     宽度
     * @param height    高度
     * @param input     是否输入侧
     * @param ruleIndex 在该侧规则列表里的下标
     */
    record Cell(int x, int y, int width, int height, boolean input, int ruleIndex) {
    }

    /**
     * 一个段（输入或输出）。
     *
     * @param input   是否输入侧
     * @param headerY 段头上边缘
     * @param rules   该侧规则条数
     */
    record Section(boolean input, int headerY, int rules) {
    }

    /**
     * 一次排版的结果。
     *
     * @param x             面板左边缘
     * @param y             面板上边缘
     * @param width         面板宽度
     * @param height        当前动画高度（已夹紧）
     * @param sections      两个段
     * @param cells         可见单元格
     * @param inputUnshown  输入侧因高度不足没画出的条数
     * @param outputUnshown 输出侧因高度不足没画出的条数
     */
    record Detail(int x, int y, int width, int height,
            List<Section> sections, List<Cell> cells,
            int inputUnshown, int outputUnshown) {

        Detail {
            sections = List.copyOf(sections);
            cells = List.copyOf(cells);
        }
    }

    /**
     * 展开面板的完整所需高度（未夹紧）。
     *
     * @param layout  布局
     * @param machine 机器视图
     * @return 高度
     */
    static int requiredHeight(FactoryLayout layout, FactorySnapshotBuilder.MachineView machine) {
        return layout.ioDetailHeight(machine.inputs().size(), machine.outputs().size());
    }

    /**
     * 计算当前动画高度下的面板与单元格位置。
     *
     * @param layout         布局
     * @param machine        机器视图
     * @param topY           面板上边缘（基础行底部）
     * @param animatedHeight 动画后的高度
     * @return 排版结果
     */
    static Detail layout(FactoryLayout layout, FactorySnapshotBuilder.MachineView machine,
            int topY, int animatedHeight) {
        int height = Math.max(0, animatedHeight);
        int x = layout.leftX + 2;
        int width = layout.ioPanelWidth();
        int contentX = x + FactoryLayout.IO_PANEL_PAD;
        int innerW = layout.ioInnerWidth();
        int perRow = layout.ioCellsPerRow();
        int cellW = layout.ioCellWidth();
        int bottomLimit = topY + height - FactoryLayout.IO_PANEL_PAD;

        List<Section> sections = new ArrayList<>(2);
        List<Cell> cells = new ArrayList<>();
        int cursorY = topY + FactoryLayout.IO_PANEL_PAD;

        int inputRules = machine.inputs().size();
        int inputUnshown = addSection(sections, cells, true, inputRules, cursorY,
                contentX, cellW, perRow, bottomLimit);
        cursorY += layout.ioSectionHeight(inputRules) + FactoryLayout.IO_SECTION_GAP;

        int outputRules = machine.outputs().size();
        int outputUnshown = addSection(sections, cells, false, outputRules, cursorY,
                contentX, cellW, perRow, bottomLimit);

        return new Detail(x, topY, width, height, sections, cells, inputUnshown, outputUnshown);
    }

    /**
     * 追加一段并收集放得下的单元格。
     *
     * @return 因高度不足没放下的条数
     */
    private static int addSection(List<Section> sections, List<Cell> cells,
            boolean input, int rules, int headerY, int contentX, int cellW,
            int perRow, int bottomLimit) {
        sections.add(new Section(input, headerY, rules));
        if (rules <= 0) {
            return 0;
        }
        int startY = headerY + FactoryLayout.IO_SECTION_H + FactoryLayout.IO_GAP;
        for (int i = 0; i < rules; i++) {
            int row = i / perRow;
            int col = i % perRow;
            int cellY = startY + row * (FactoryLayout.IO_CELL_H + FactoryLayout.IO_GAP);
            if (cellY + FactoryLayout.IO_CELL_H > bottomLimit) {
                return rules - i;
            }
            cells.add(new Cell(contentX + col * (cellW + FactoryLayout.IO_GAP), cellY,
                    cellW, FactoryLayout.IO_CELL_H, input, i));
        }
        return 0;
    }

    /**
     * 鼠标命中的单元格。
     *
     * @param detail 排版结果
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 单元格；没命中为 {@code null}
     */
    static Cell hit(Detail detail, double mouseX, double mouseY) {
        for (Cell cell : detail.cells()) {
            if (inside(mouseX, mouseY, cell.x(), cell.y(), cell.width(), cell.height())) {
                return cell;
            }
        }
        return null;
    }

    /**
     * 画整个展开面板。
     *
     * @param graphics 画布
     * @param font     字体
     * @param theme    主题
     * @param layout   布局
     * @param machine  机器视图
     * @param detail   排版结果
     * @param mouseX   鼠标 x（用于单元格悬停）
     * @param mouseY   鼠标 y
     */
    static void draw(GuiGraphics graphics, Font font, GuiTheme theme, FactoryLayout layout,
            FactorySnapshotBuilder.MachineView machine, Detail detail, int mouseX, int mouseY) {
        if (detail.height() <= 0) {
            return;
        }
        NineSlice.ioPanel(graphics, detail.x(), detail.y(), detail.width(), detail.height());

        for (Section section : detail.sections()) {
            drawSection(graphics, font, theme, layout, machine, detail, section);
        }
        for (Cell cell : detail.cells()) {
            drawCell(graphics, font, theme, machine, cell, mouseX, mouseY);
        }

        int unshown = detail.inputUnshown() + detail.outputUnshown();
        if (unshown > 0) {
            String note = "还有 " + unshown + " 条";
            graphics.drawString(font, note,
                    detail.x() + detail.width() - 4 - font.width(note),
                    detail.y() + detail.height() - 11, theme.textDim(), false);
        }
    }

    /** 画一个段头和它的「未配置」占位。 */
    private static void drawSection(GuiGraphics graphics, Font font, GuiTheme theme,
            FactoryLayout layout, FactorySnapshotBuilder.MachineView machine,
            Detail detail, Section section) {
        if (section.headerY() + FactoryLayout.IO_SECTION_H > detail.y() + detail.height() - 1) {
            return;
        }
        int contentX = detail.x() + FactoryLayout.IO_PANEL_PAD;
        int innerW = layout.ioInnerWidth();
        NineSlice.ioHeader(graphics, contentX, section.headerY(),
                innerW, FactoryLayout.IO_SECTION_H);
        // 段头下方 1px 阴影，让闭合的段头压在内容上。
        NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK,
                contentX + 1, section.headerY() + FactoryLayout.IO_SECTION_H,
                Math.max(0, innerW - 2), 1);
        NineSlice.fill(graphics, GuiTextures.IO_ACCENT,
                contentX + 1, section.headerY() + FactoryLayout.IO_SECTION_H - 2,
                Math.max(0, innerW - 2), 1);

        String label = (section.input() ? "输入" : "输出") + " · ";
        String count = Integer.toString(section.rules());
        graphics.drawString(font, label, contentX + 4, section.headerY() + 2,
                HEADER_TEXT, false);
        graphics.drawString(font, count, contentX + 4 + font.width(label),
                section.headerY() + 2, HEADER_VALUE, false);
        graphics.drawString(font, " 条", contentX + 4 + font.width(label) + font.width(count),
                section.headerY() + 2, HEADER_TEXT, false);

        long rate = section.input() ? machine.rateIn() : machine.rateOut();
        String rateText = FactoryLabels.count(rate) + "/s";
        graphics.drawString(font, rateText,
                contentX + innerW - 4 - font.width(rateText),
                section.headerY() + 2,
                rate > 0L ? theme.statusRun() : HEADER_TEXT, false);

        if (section.rules() == 0) {
            int textY = section.headerY() + FactoryLayout.IO_SECTION_H
                    + FactoryLayout.IO_GAP + (FactoryLayout.IO_CELL_H - 9) / 2;
            graphics.drawString(font, "未配置", contentX + 4, textY, theme.textDim(), false);
        }
    }

    /** 画一个规则单元格：状态条、图标槽、速率、面与诊断。 */
    private static void drawCell(GuiGraphics graphics, Font font, GuiTheme theme,
            FactorySnapshotBuilder.MachineView machine, Cell cell, int mouseX, int mouseY) {
        List<ResourceSpec> rules = cell.input() ? machine.inputs() : machine.outputs();
        if (cell.ruleIndex() >= rules.size()) {
            return;
        }
        ResourceSpec spec = rules.get(cell.ruleIndex());
        long rate = cell.input() ? machine.ruleRate(true, cell.ruleIndex())
                : machine.ruleRate(false, cell.ruleIndex());
        List<IoResult.Reason> reasons = cell.input()
                ? machine.inputReasons() : machine.outputReasons();
        IoResult.Reason reason = cell.ruleIndex() < reasons.size()
                ? reasons.get(cell.ruleIndex()) : IoResult.Reason.NO_ITEM;

        boolean hovered = inside(mouseX, mouseY, cell.x(), cell.y(), cell.width(), cell.height());
        if (hovered) {
            NineSlice.ioCellHover(graphics, cell.x(), cell.y(), cell.width(), cell.height());
        } else {
            NineSlice.ioCell(graphics, cell.x(), cell.y(), cell.width(), cell.height());
        }
        NineSlice.fill(graphics, FactoryLabels.reasonStrip(reason),
                cell.x() + 1, cell.y() + 2, 1, cell.height() - 4);

        int slotX = cell.x() + 3;
        int slotY = cell.y() + 4;
        NineSlice.slot(graphics, slotX, slotY, FactoryLayout.CELL, FactoryLayout.CELL);
        ItemStack icon = FactoryLabels.icon(spec.key());
        graphics.renderItem(icon, slotX + 1, slotY + 1);

        String rateValue = FactoryLabels.count(rate);
        String rateUnit = FactoryLabels.rateUnit(spec.key().kind());
        int rateColor = FactoryLabels.rateColorUi(rate, reason, theme);
        int rateWidth = font.width(rateValue) + font.width(rateUnit);
        int rateX = cell.x() + cell.width() - 4 - rateWidth;
        graphics.drawString(font, rateValue, rateX, cell.y() + 4, rateColor, false);
        graphics.drawString(font, rateUnit, rateX + font.width(rateValue), cell.y() + 4,
                rateColor, false);

        int textX = slotX + FactoryLayout.CELL + 4;
        int lineY = cell.y() + 14;
        String face = FactoryLabels.face(spec);
        graphics.drawString(font, face, textX, lineY, theme.textFace(), false);
        int reasonX = textX + font.width(face) + 4;
        int reasonWidth = cell.x() + cell.width() - 4 - reasonX;
        String reasonText = FactoryLabels.reason(reason, cell.input());
        graphics.drawString(font, font.plainSubstrByWidth(reasonText, Math.max(0, reasonWidth)),
                reasonX, lineY, FactoryLabels.reasonColor(reason, theme), false);
    }

    /** 鼠标是否落在给定矩形内。 */
    private static boolean inside(double mouseX, double mouseY,
            int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
