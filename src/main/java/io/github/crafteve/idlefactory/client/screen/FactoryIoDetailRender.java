package io.github.crafteve.idlefactory.client.screen;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.factory.io.IoResult;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import io.github.crafteve.idlefactory.factory.storage.AeKeySupport;

/**
 * 展开 IO 面板的绘制层：段头、配置卡、空卡、滚动条。
 *
 * <p>只读 {@link FactoryIoDetailLayout.Detail} 的绝对坐标；不自己算布局。卡片控件（面、匹配）的
 * 矩形来自 {@link FactoryIoCardControls}，和点击命中共用一份。卡片保持 26px：第一行槽位 + 名称 +
 * 右对齐速率，第二行面按钮 + 匹配按钮 + 状态文字。</p>
 */
final class FactoryIoDetailRender {
    /** 深色段头上的冷白。 */
    private static final int HEADER_TEXT = 0xFFE8ECF5;
    /** 深色段头上的亮青数值。 */
    private static final int HEADER_VALUE = 0xFF4DE3FF;
    /** 黄色匹配按钮上的深棕文字。 */
    private static final int MATCH_TEXT = 0xFF4A3200;

    private FactoryIoDetailRender() {
        // 纯静态工具类
    }

    /**
     * 画整个展开面板。
     *
     * @param graphics         画布
     * @param font             字体
     * @param theme            主题
     * @param layout           主布局（取内宽等）
     * @param machine          机器视图
     * @param detail           滚动布局
     * @param mouseX           鼠标 x
     * @param mouseY           鼠标 y
     * @param draggingResource 是否正在拖资源
     * @param idleLabel        机器睡眠时的中性文案；运行中传 {@code null}
     */
    static void draw(GuiGraphics graphics, Font font, FactoryGuiTheme theme, FactoryLayout layout,
            FactorySnapshotBuilder.MachineView machine, FactoryIoDetailLayout.Detail detail,
            int mouseX, int mouseY, boolean draggingResource, @Nullable String idleLabel) {
        if (detail.panelH() <= 0 || detail.panelW() <= 0) {
            return;
        }
        FactoryNineSlice.ioPanel(graphics, detail.panelX(), detail.panelY(),
                detail.panelW(), detail.panelH());

        graphics.enableScissor(detail.panelX() + 1, detail.panelY() + 1,
                detail.panelX() + detail.panelW() - 1,
                detail.panelY() + detail.panelH() - 1);
        for (FactoryIoDetailLayout.Section section : detail.sections()) {
            drawSection(graphics, font, theme, layout, detail, section, idleLabel);
        }
        for (FactoryIoDetailLayout.Card card : detail.cards()) {
            if (card.y() + FactoryLayout.IO_CELL_H < detail.panelY()
                    || card.y() > detail.panelY() + detail.panelH()) {
                continue;
            }
            if (card.empty()) {
                drawEmptyCard(graphics, font, theme, card, mouseX, mouseY, draggingResource);
            } else {
                drawRuleCard(graphics, font, theme, machine, card, mouseX, mouseY, idleLabel);
            }
        }
        graphics.disableScissor();
        drawScrollbar(graphics, detail);
    }

    private static void drawSection(GuiGraphics graphics, Font font, FactoryGuiTheme theme,
            FactoryLayout layout, FactoryIoDetailLayout.Detail detail, FactoryIoDetailLayout.Section section,
            @Nullable String idleLabel) {
        int contentX = detail.panelX() + FactoryLayout.IO_PANEL_PAD;
        int innerW = layout.ioInnerWidth();
        FactoryNineSlice.ioHeader(graphics, contentX, section.headerY(), innerW,
                FactoryLayout.IO_SECTION_H);
        String label = (section.input() ? "输入" : "输出") + " · ";
        String count = Integer.toString(section.rules());
        graphics.drawString(font, label, contentX + 4, section.headerY() + 2,
                HEADER_TEXT, false);
        graphics.drawString(font, count, contentX + 4 + font.width(label),
                section.headerY() + 2, HEADER_VALUE, false);
        graphics.drawString(font, " 条", contentX + 4 + font.width(label) + font.width(count),
                section.headerY() + 2, HEADER_TEXT, false);

        String rateText = FactoryLabels.count(section.rate()) + "/s";
        int rateColor = idleLabel != null ? HEADER_TEXT
                : section.rate() > 0L ? theme.statusRun() : HEADER_TEXT;
        graphics.drawString(font, rateText,
                contentX + innerW - 4 - font.width(rateText), section.headerY() + 2,
                rateColor, false);
    }

    /**
     * 空卡：与规则卡同一槽位、同一高度，只把按钮行换成提示文字。
     *
     * @param graphics         画布
     * @param font             字体
     * @param theme            主题
     * @param card             卡片
     * @param mouseX           鼠标 x
     * @param mouseY           鼠标 y
     * @param draggingResource 是否正在拖资源
     */
    private static void drawEmptyCard(GuiGraphics graphics, Font font, FactoryGuiTheme theme,
            FactoryIoDetailLayout.Card card, int mouseX, int mouseY, boolean draggingResource) {
        boolean hovered = inside(mouseX, mouseY, card.x(), card.y(),
                card.width(), FactoryLayout.IO_CELL_H);
        if (hovered) {
            FactoryNineSlice.ioCellHover(graphics, card.x(), card.y(),
                    card.width(), FactoryLayout.IO_CELL_H);
        } else {
            FactoryNineSlice.ioCell(graphics, card.x(), card.y(),
                    card.width(), FactoryLayout.IO_CELL_H);
        }
        int slotX = card.x() + FactoryLayout.IO_CARD_PAD;
        int slotY = card.y() + FactoryLayout.IO_CARD_SLOT_Y;
        FactoryNineSlice.slot(graphics, slotX, slotY, FactoryLayout.CELL, FactoryLayout.CELL);
        int color = draggingResource && hovered ? theme.statusRun() : theme.textDim();
        String plus = "+";
        graphics.drawString(font, plus,
                slotX + (FactoryLayout.CELL - font.width(plus)) / 2, slotY + 5, color, false);

        int textX = card.x() + FactoryLayout.IO_CARD_TEXT_X;
        graphics.drawString(font, "拖入资源", textX, card.y() + 4, color, false);
        graphics.drawString(font, "从仓库拖入", textX,
                card.y() + FactoryLayout.IO_CARD_LINE2_Y + 2, color, false);
    }

    private static void drawRuleCard(GuiGraphics graphics, Font font, FactoryGuiTheme theme,
            FactorySnapshotBuilder.MachineView machine, FactoryIoDetailLayout.Card card,
            int mouseX, int mouseY, @Nullable String idleLabel) {
        List<ResourceSpec> rules = card.input() ? machine.inputs() : machine.outputs();
        if (card.ruleIndex() >= rules.size()) {
            return;
        }
        ResourceSpec spec = rules.get(card.ruleIndex());
        long rate = machine.ruleRate(card.input(), card.ruleIndex());
        List<IoResult.Reason> reasons = card.input()
                ? machine.inputReasons() : machine.outputReasons();
        IoResult.Reason reason = card.ruleIndex() < reasons.size()
                ? reasons.get(card.ruleIndex()) : IoResult.Reason.NO_ITEM;
        // 速率是 1 秒窗口、原因是最近一轮；有流量时不让上一轮的红色盖住正在搬运的事实。
        IoResult.Reason effective = idleLabel != null ? IoResult.Reason.NO_ITEM
                : FactoryLabels.effectiveReason(rate, reason);

        boolean hovered = inside(mouseX, mouseY, card.x(), card.y(),
                card.width(), FactoryLayout.IO_CELL_H);
        if (hovered) {
            FactoryNineSlice.ioCellHover(graphics, card.x(), card.y(),
                    card.width(), FactoryLayout.IO_CELL_H);
        } else {
            FactoryNineSlice.ioCell(graphics, card.x(), card.y(),
                    card.width(), FactoryLayout.IO_CELL_H);
        }
        FactoryNineSlice.fill(graphics,
                idleLabel != null ? FactoryGuiTextures.STATUS_STRIP_IDLE
                        : FactoryLabels.reasonStrip(effective),
                card.x() + 1, card.y() + 2, 1, FactoryLayout.IO_CELL_H - 4);

        int slotX = card.x() + FactoryLayout.IO_CARD_PAD;
        int slotY = card.y() + FactoryLayout.IO_CARD_SLOT_Y;
        FactoryNineSlice.slot(graphics, slotX, slotY, FactoryLayout.CELL, FactoryLayout.CELL);
        FactoryResourceIconRender.draw(graphics, font, spec.key(), slotX + 1, slotY + 1);

        int textX = card.x() + FactoryLayout.IO_CARD_TEXT_X;
        String rateText = FactoryLabels.rateText(rate, AeKeySupport.kind(spec.key()));
        int rateX = card.x() + card.width() - 4 - font.width(rateText);
        int nameWidth = Math.max(0, rateX - textX - 4);
        graphics.drawString(font,
                font.plainSubstrByWidth(FactoryLabels.name(spec.key()), nameWidth),
                textX, card.y() + 4, theme.textPrimary(), false);
        int rateColor = idleLabel != null ? theme.statusIdle()
                : FactoryLabels.rateColorUi(rate, effective, theme);
        graphics.drawString(font, rateText, rateX, card.y() + 4, rateColor, false);

        drawFaceButton(graphics, font, theme, card, spec, mouseX, mouseY);
        drawMatchButton(graphics, font, theme, card, spec, mouseX, mouseY);

        int statusX = FactoryIoCardControls.reasonX(card.x(), spec);
        int statusWidth = Math.max(0, card.x() + card.width() - 4 - statusX);
        String statusText = idleLabel != null ? idleLabel
                : FactoryLabels.reason(effective, card.input());
        int statusColor = idleLabel != null ? theme.statusIdle()
                : FactoryLabels.reasonColor(effective, theme);
        graphics.drawString(font, font.plainSubstrByWidth(statusText, statusWidth),
                statusX, card.y() + FactoryLayout.IO_CARD_LINE2_Y + 2, statusColor, false);
    }

    private static void drawFaceButton(GuiGraphics graphics, Font font, FactoryGuiTheme theme,
            FactoryIoDetailLayout.Card card, ResourceSpec spec, int mouseX, int mouseY) {
        FactoryIoCardControls.Rect rect = FactoryIoCardControls.faceRect(card.x(), card.y());
        boolean hovered = inside(mouseX, mouseY, rect);
        if (hovered) {
            FactoryNineSlice.buttonHover(graphics, rect.x(), rect.y(), rect.width(), rect.height());
        } else {
            FactoryNineSlice.button(graphics, rect.x(), rect.y(), rect.width(), rect.height());
        }
        String label = FactoryIoCardControls.faceLabel(spec);
        graphics.drawString(font, label,
                rect.x() + (rect.width() - font.width(label)) / 2, rect.y() + 2,
                theme.textFace(), false);
    }

    private static void drawMatchButton(GuiGraphics graphics, Font font, FactoryGuiTheme theme,
            FactoryIoDetailLayout.Card card, ResourceSpec spec, int mouseX, int mouseY) {
        if (!FactoryIoCardControls.matchVisible(spec)) {
            return;
        }
        FactoryIoCardControls.Rect rect = FactoryIoCardControls.matchRect(card.x(), card.y());
        boolean hovered = inside(mouseX, mouseY, rect);
        if (hovered) {
            FactoryNineSlice.buttonHover(graphics, rect.x(), rect.y(), rect.width(), rect.height());
        } else {
            FactoryNineSlice.slice(graphics, FactoryGuiTextures.BUTTON_MATCH, FactoryGuiTextures.BUTTON_SIZE, 1,
                    rect.x(), rect.y(), rect.width(), rect.height());
        }
        String label = FactoryIoCardControls.matchLabel(spec);
        graphics.drawString(font, label,
                rect.x() + (rect.width() - font.width(label)) / 2, rect.y() + 2,
                MATCH_TEXT, false);
    }

    private static void drawScrollbar(GuiGraphics graphics, FactoryIoDetailLayout.Detail detail) {
        if (detail.maxScroll() <= 0) {
            return;
        }
        int trackX = detail.panelX() + detail.panelW() - 4;
        int trackY = detail.panelY() + 4;
        int trackH = Math.max(1, detail.panelH() - 8);
        int thumbH = Math.max(12,
                trackH * detail.panelH() / Math.max(1, detail.contentH() + 8));
        int thumbY = trackY + (trackH - thumbH) * detail.scroll()
                / Math.max(1, detail.maxScroll());
        graphics.fill(trackX, trackY, trackX + 2, trackY + trackH, 0x40000000);
        graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0xFF4D6A8A);
    }

    private static boolean inside(double mouseX, double mouseY, FactoryIoCardControls.Rect rect) {
        return inside(mouseX, mouseY, rect.x(), rect.y(), rect.width(), rect.height());
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width,
            int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
