package io.github.crafteve.idlefactory.client.screen;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.client.theme.GuiTheme;
import io.github.crafteve.idlefactory.client.widget.NineSlice;
import io.github.crafteve.idlefactory.factory.io.IoResult;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 输入/输出紧凑列的排版、绘制与命中。
 *
 * <p>每列最多显示 3 条规则摘要，并在末尾为“空槽”预留 18px；空槽是资源拖拽的放置目标。
 * 绘制、点击、JEI 都用同一份 {@link Layout}，避免坐标各算各的。</p>
 */
final class RuleStrip {
    /** 最多显示几条规则摘要。 */
    private static final int MAX_RULES = 3;

    /**
     * 一条规则摘要。
     *
     * @param ruleIndex 规则下标
     * @param x         凹槽左边缘
     * @param y         凹槽上边缘
     * @param width     包含速率文字的总宽
     */
    record Entry(int ruleIndex, int x, int y, int width) {
    }

    /**
     * 一次排版。
     *
     * @param entries 规则摘要
     * @param addSlot 空槽；{@code null} 表示列太窄或被禁用
     * @param x       列起点
     * @param y       上边缘
     */
    record Layout(List<Entry> entries, @Nullable Entry addSlot, int x, int y) {
        Layout {
            entries = List.copyOf(entries);
        }
    }

    private RuleStrip() {
        // 纯静态工具类
    }

    /**
     * 排版一列。
     *
     * @param font     字体
     * @param rules    规则
     * @param rates    速率
     * @param x        左边缘
     * @param y        上边缘
     * @param maxWidth 可用宽度
     * @param canAdd   是否允许显示空槽
     * @return 排版
     */
    static Layout layout(Font font, List<ResourceSpec> rules, List<Long> rates,
            int x, int y, int maxWidth, boolean canAdd) {
        int reserve = canAdd ? FactoryLayout.CELL + 2 : 0;
        List<Entry> entries = new ArrayList<>(Math.min(rules.size(), MAX_RULES));
        int cursor = x;
        int limit = Math.min(rules.size(), MAX_RULES);
        for (int i = 0; i < limit; i++) {
            ResourceSpec spec = rules.get(i);
            long rate = i < rates.size() ? rates.get(i) : 0L;
            String rateText = FactoryLabels.rateText(rate, spec.key().kind());
            int entryWidth = FactoryLayout.CELL + 2 + font.width(rateText) + 6;
            if (!entries.isEmpty() && cursor + entryWidth + reserve > x + maxWidth) {
                break;
            }
            entries.add(new Entry(i, cursor, y, entryWidth));
            cursor += entryWidth;
        }
        Entry addSlot = null;
        if (canAdd && cursor + FactoryLayout.CELL <= x + maxWidth) {
            addSlot = new Entry(rules.size(), cursor, y, FactoryLayout.CELL);
        }
        return new Layout(entries, addSlot, x, y);
    }

    /**
     * 画一列。
     *
     * @param graphics 画布
     * @param font     字体
     * @param theme    主题
     * @param layout   排版
     * @param rules    规则
     * @param rates    速率
     * @param reasons  逐规则诊断
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     * @param dragging 是否正在拖资源（用于空槽高亮）
     * @param inactive 产线是否暂停/睡眠；此时速率用中性色
     */
    static void draw(GuiGraphics graphics, Font font, GuiTheme theme, Layout layout,
            List<ResourceSpec> rules, List<Long> rates, List<IoResult.Reason> reasons,
            int mouseX, int mouseY, boolean dragging, boolean inactive) {
        for (Entry entry : layout.entries()) {
            if (entry.ruleIndex() >= rules.size()) {
                continue;
            }
            ResourceSpec spec = rules.get(entry.ruleIndex());
            long rate = entry.ruleIndex() < rates.size() ? rates.get(entry.ruleIndex()) : 0L;
            IoResult.Reason reason = entry.ruleIndex() < reasons.size()
                    ? reasons.get(entry.ruleIndex()) : IoResult.Reason.NO_ITEM;
            IoResult.Reason effective = inactive ? IoResult.Reason.NO_ITEM
                    : FactoryLabels.effectiveReason(rate, reason);
            int rateColor = inactive ? theme.statusIdle()
                    : FactoryLabels.rateColorUi(rate, effective, theme);
            String rateText = FactoryLabels.rateText(rate, spec.key().kind());
            NineSlice.slot(graphics, entry.x(), entry.y(),
                    FactoryLayout.CELL, FactoryLayout.CELL);
            ResourceIconRenderer.draw(graphics, font, spec.key(), entry.x() + 1, entry.y() + 1);
            graphics.drawString(font, rateText, entry.x() + FactoryLayout.CELL + 2,
                    entry.y() + 5, rateColor, false);
        }
        int shown = layout.entries().size();
        if (shown < rules.size()) {
            int tailX = layout.entries().isEmpty() ? layout.x()
                    : layout.entries().get(layout.entries().size() - 1).x()
                    + layout.entries().get(layout.entries().size() - 1).width();
            graphics.drawString(font, "+" + (rules.size() - shown), tailX, layout.y() + 4,
                    theme.textValue(), false);
        }
        Entry add = layout.addSlot();
        if (add == null) {
            return;
        }
        boolean hovered = inside(mouseX, mouseY, add.x(), add.y(),
                FactoryLayout.CELL, FactoryLayout.CELL);
        NineSlice.slot(graphics, add.x(), add.y(), FactoryLayout.CELL, FactoryLayout.CELL);
        String plus = "+";
        graphics.drawString(font, plus,
                add.x() + (FactoryLayout.CELL - font.width(plus)) / 2, add.y() + 5,
                dragging && hovered ? theme.statusRun() : theme.textDim(), false);
        if (hovered) {
            int color = theme.hoverOutline();
            graphics.fill(add.x(), add.y(), add.x() + FactoryLayout.CELL, add.y() + 1, color);
            graphics.fill(add.x(), add.y() + FactoryLayout.CELL - 1,
                    add.x() + FactoryLayout.CELL, add.y() + FactoryLayout.CELL, color);
            graphics.fill(add.x(), add.y(), add.x() + 1, add.y() + FactoryLayout.CELL, color);
            graphics.fill(add.x() + FactoryLayout.CELL - 1, add.y(),
                    add.x() + FactoryLayout.CELL, add.y() + FactoryLayout.CELL, color);
        }
    }

    /**
     * 命中的规则摘要。
     *
     * @param layout 排版
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @return 条目；没命中为 {@code null}
     */
    @Nullable
    static Entry hitRule(Layout layout, double mouseX, double mouseY) {
        for (Entry entry : layout.entries()) {
            if (inside(mouseX, mouseY, entry.x(), entry.y(),
                    FactoryLayout.CELL, FactoryLayout.CELL)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * 是否命中空槽。
     *
     * @param layout 排版
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @return 命中为 {@code true}
     */
    static boolean hitAdd(Layout layout, double mouseX, double mouseY) {
        Entry add = layout.addSlot();
        return add != null && inside(mouseX, mouseY, add.x(), add.y(),
                FactoryLayout.CELL, FactoryLayout.CELL);
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width,
            int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
