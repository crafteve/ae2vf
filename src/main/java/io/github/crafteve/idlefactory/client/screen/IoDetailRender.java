package io.github.crafteve.idlefactory.client.screen;

import java.util.List;

import io.github.crafteve.idlefactory.client.theme.GuiTheme;
import io.github.crafteve.idlefactory.client.widget.GuiTextures;
import io.github.crafteve.idlefactory.client.widget.NineSlice;
import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.factory.io.IoResult;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 展开 IO 面板的绘制层：段头、配置卡、空卡、滚动条。
 *
 * <p>只读 {@link IoDetailLayout.Detail} 的绝对坐标；不自己算布局。卡片控件（面、匹配、删除）
 * 的矩形来自 {@link IoCardControls}，和点击命中共用一份。</p>
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
     */
    static void draw(GuiGraphics graphics, Font font, GuiTheme theme, FactoryLayout layout,
            FactorySnapshotBuilder.MachineView machine, IoDetailLayout.Detail detail,
            int mouseX, int mouseY, boolean draggingResource) {
        if (detail.panelH() <= 0 || detail.panelW() <= 0) {
            return;
        }
        NineSlice.ioPanel(graphics, detail.panelX(), detail.panelY(),
                detail.panelW(), detail.panelH());

        graphics.enableScissor(detail.panelX() + 1, detail.panelY() + 1,
                detail.panelX() + detail.panelW() - 1,
                detail.panelY() + detail.panelH() - 1);
        for (IoDetailLayout.Section section : detail.sections()) {
            drawSection(graphics, font, theme, layout, detail, section);
        }
        for (IoDetailLayout.Card card : detail.cards()) {
            if (card.y() + FactoryLayout.IO_CARD_H < detail.panelY()
                    || card.y() > detail.panelY() + detail.panelH()) {
                continue;
            }
            if (card.empty()) {
                drawEmptyCard(graphics, font, theme, card, mouseX, mouseY, draggingResource);
            } else {
                drawRuleCard(graphics, font, theme, machine, card, mouseX, mouseY);
            }
        }
        graphics.disableScissor();
        drawScrollbar(graphics, detail);
    }

    private static void drawSection(GuiGraphics graphics, Font font, GuiTheme theme,
            FactoryLayout layout, IoDetailLayout.Detail detail, IoDetailLayout.Section section) {
        int contentX = detail.panelX() + FactoryLayout.IO_PANEL_PAD;
        int innerW = layout.ioInnerWidth();
        NineSlice.ioHeader(graphics, contentX, section.headerY(), innerW,
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
        graphics.drawString(font, rateText,
                contentX + innerW - 4 - font.width(rateText), section.headerY() + 2,
                section.rate() > 0L ? theme.statusRun() : HEADER_TEXT, false);
    }

    private static void drawEmptyCard(GuiGraphics graphics, Font font, GuiTheme theme,
            IoDetailLayout.Card card, int mouseX, int mouseY, boolean draggingResource) {
        boolean hovered = inside(mouseX, mouseY, card.x(), card.y(),
                card.width(), FactoryLayout.IO_CARD_H);
        if (hovered) {
            NineSlice.ioCellHover(graphics, card.x(), card.y(),
                    card.width(), FactoryLayout.IO_CARD_H);
        } else {
            NineSlice.ioCell(graphics, card.x(), card.y(),
                    card.width(), FactoryLayout.IO_CARD_H);
        }
        int slotX = card.x() + (card.width() - FactoryLayout.CELL) / 2;
        int slotY = card.y() + 8;
        NineSlice.slot(graphics, slotX, slotY, FactoryLayout.CELL, FactoryLayout.CELL);
        graphics.drawString(font, "+",
                slotX + (FactoryLayout.CELL - font.width("+")) / 2, slotY + 5,
                draggingResource && hovered ? theme.statusRun() : theme.textDim(), false);
        String hint = "拖入资源";
        graphics.drawString(font, hint,
                card.x() + (card.width() - font.width(hint)) / 2, card.y() + 31,
                draggingResource && hovered ? theme.statusRun() : theme.textDim(), false);
    }

    private static void drawRuleCard(GuiGraphics graphics, Font font, GuiTheme theme,
            FactorySnapshotBuilder.MachineView machine, IoDetailLayout.Card card,
            int mouseX, int mouseY) {
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

        boolean hovered = inside(mouseX, mouseY, card.x(), card.y(),
                card.width(), FactoryLayout.IO_CARD_H);
        if (hovered) {
            NineSlice.ioCellHover(graphics, card.x(), card.y(),
                    card.width(), FactoryLayout.IO_CARD_H);
        } else {
            NineSlice.ioCell(graphics, card.x(), card.y(),
                    card.width(), FactoryLayout.IO_CARD_H);
        }
        NineSlice.fill(graphics, FactoryLabels.reasonStrip(reason),
                card.x() + 1, card.y() + 2, 1, FactoryLayout.IO_CARD_H - 4);

        int slotX = card.x() + 3;
        int slotY = card.y() + 3;
        NineSlice.slot(graphics, slotX, slotY, FactoryLayout.CELL, FactoryLayout.CELL);
        ResourceIconRenderer.draw(graphics, font, spec.key(), slotX + 1, slotY + 1);

        int textX = slotX + FactoryLayout.CELL + 3;
        int deleteX = card.x() + card.width() - FactoryLayout.IO_DELETE_SIZE - 3;
        int nameWidth = Math.max(0, deleteX - textX - 2);
        graphics.drawString(font,
                font.plainSubstrByWidth(FactoryLabels.name(spec.key()), nameWidth),
                textX, card.y() + 4, theme.textPrimary(), false);
        String rateText = FactoryLabels.rateText(rate, spec.key().kind());
        graphics.drawString(font, rateText, textX, card.y() + 14,
                FactoryLabels.rateColorUi(rate, reason, theme), false);

        drawDelete(graphics, font, theme, card, mouseX, mouseY);
        drawFaceButtons(graphics, font, theme, card, spec, mouseX, mouseY);
        drawMatch(graphics, font, theme, card, spec, mouseX, mouseY);
    }

    private static void drawDelete(GuiGraphics graphics, Font font, GuiTheme theme,
            IoDetailLayout.Card card, int mouseX, int mouseY) {
        IoCardControls.Rect rect = IoCardControls.deleteRect(card.x(), card.y(), card.width());
        boolean hovered = inside(mouseX, mouseY, rect);
        if (hovered) {
            NineSlice.buttonHover(graphics, rect.x(), rect.y(), rect.width(), rect.height());
        } else {
            NineSlice.button(graphics, rect.x(), rect.y(), rect.width(), rect.height());
        }
        graphics.drawString(font, "x",
                rect.x() + (rect.width() - font.width("x")) / 2, rect.y() + 2,
                hovered ? theme.error() : theme.textPrimary(), false);
    }

    private static void drawFaceButtons(GuiGraphics graphics, Font font, GuiTheme theme,
            IoDetailLayout.Card card, ResourceSpec spec, int mouseX, int mouseY) {
        for (int i = 0; i < IoCardControls.FACES.length; i++) {
            IoCardControls.Rect rect = IoCardControls.faceRect(card.x(), card.y(), i);
            boolean hovered = inside(mouseX, mouseY, rect);
            if (hovered) {
                NineSlice.buttonHover(graphics, rect.x(), rect.y(), rect.width(), rect.height());
            } else {
                NineSlice.button(graphics, rect.x(), rect.y(), rect.width(), rect.height());
            }
            if (IoCardControls.faceSelected(spec, i)) {
                NineSlice.fill(graphics, GuiTextures.ROW_ALT, rect.x() + 1, rect.y() + 1,
                        rect.width() - 2, rect.height() - 2);
            }
            String label = IoCardControls.FACE_LABELS[i];
            graphics.drawString(font, label,
                    rect.x() + (rect.width() - font.width(label)) / 2, rect.y() + 2,
                    IoCardControls.faceSelected(spec, i)
                            ? theme.textValue() : theme.textPrimary(), false);
        }
    }

    private static void drawMatch(GuiGraphics graphics, Font font, GuiTheme theme,
            IoDetailLayout.Card card, ResourceSpec spec, int mouseX, int mouseY) {
        if (!IoCardControls.matchVisible(spec)) {
            return;
        }
        IoCardControls.Rect rect = IoCardControls.matchRect(card.x(), card.y());
        boolean enabled = IoCardControls.matchEnabled(spec);
        boolean hovered = enabled && inside(mouseX, mouseY, rect);
        if (!enabled) {
            NineSlice.button(graphics, rect.x(), rect.y(), rect.width(), rect.height());
        } else if (hovered) {
            NineSlice.buttonHover(graphics, rect.x(), rect.y(), rect.width(), rect.height());
        } else {
            NineSlice.slice(graphics, GuiTextures.BUTTON_MATCH, GuiTextures.BUTTON_SIZE, 1,
                    rect.x(), rect.y(), rect.width(), rect.height());
        }
        String label = IoCardControls.matchLabel(spec);
        graphics.drawString(font, label,
                rect.x() + (rect.width() - font.width(label)) / 2, rect.y() + 2,
                enabled ? 0xFF4A3200 : theme.textDim(), false);
    }

    private static void drawScrollbar(GuiGraphics graphics, IoDetailLayout.Detail detail) {
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

    private static boolean inside(double mouseX, double mouseY, IoCardControls.Rect rect) {
        return inside(mouseX, mouseY, rect.x(), rect.y(), rect.width(), rect.height());
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width,
            int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
