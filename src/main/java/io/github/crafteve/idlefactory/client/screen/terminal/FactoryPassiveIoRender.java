package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEItemKey;

import io.github.crafteve.idlefactory.client.screen.FactoryGuiTheme;
import io.github.crafteve.idlefactory.client.screen.FactoryLabels;
import io.github.crafteve.idlefactory.client.screen.FactoryNineSlice;
import io.github.crafteve.idlefactory.client.screen.FactoryResourceIconRender;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryPassiveIoLayout.BlockBox;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryPassiveIoLayout.Hit;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryPassiveIoLayout.Viewport;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryPassiveIoModel.BlockView;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryPassiveIoModel.RuleRow;
import io.github.crafteve.idlefactory.factory.capability.CapabilityKind;
import io.github.crafteve.idlefactory.factory.io.IoResult;
import io.github.crafteve.idlefactory.factory.machine.ResourceSpec;
import io.github.crafteve.idlefactory.factory.storage.AeKeySupport;
import io.github.crafteve.idlefactory.menu.FactoryPassiveIoView;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 被动 IO 内容区的绘制。
 *
 * <p>只读模型与排版，不写任何状态：鼠标悬停由屏幕算好 {@link Hit} 传进来，滚动只影响
 * {@code scroll}，不改排版。所有静态外观来自 {@link FactoryNineSlice} 贴图与
 * {@link FactoryGuiTheme.Io} 色值。</p>
 *
 * <p>列表式为机器行 + 规则行 + 虚线新增行；磁贴式为机器行 + 77×34 磁贴两列 + 虚线新增磁贴。
 * 块左侧 3px 状态竖线贯穿整块，是分组边界也是状态表达。</p>
 */
final class FactoryPassiveIoRender {
    /** 磁贴方向色条高度（磁贴高减上下各 1px）。 */
    private static final int TILE_DIR_STRIPE_H = 32;
    /** 磁贴方向色条宽。 */
    private static final int TILE_DIR_STRIPE_W = 3;
    /** 悬停底色（白色 12%）。 */
    private static final int HOVER_FILL = 0x1FFFFFFF;
    /** chip 悬停提亮（白色 20%）。 */
    private static final int CHIP_HOVER = 0x33FFFFFF;
    /** 卡片外阴影。 */
    private static final int CARD_SHADOW = 0x33000000;
    /** 磁贴里速率列宽。 */
    private static final int TILE_RATE_W = 32;
    /** 磁贴名字宽。 */
    private static final int TILE_NAME_W = FactoryPassiveIoLayout.TILE_W
            - FactoryPassiveIoLayout.TILE_NAME_X - 2;

    private final Font font;
    private final FactoryGuiTheme theme;

    /**
     * @param font  字体
     * @param theme 当前主题
     */
    FactoryPassiveIoRender(Font font, FactoryGuiTheme theme) {
        this.font = font;
        this.theme = theme;
    }

    /**
     * 画整个内容区。
     *
     * @param graphics 画布
     * @param layout   排版
     * @param blocks   模型块
     * @param scroll   滚动像素
     * @param hover    当前悬停目标；可为 {@code null}
     * @param dragging 是否正在从 JEI 拖动资源
     * @param emptyText 内容为空时的提示
     */
    void draw(GuiGraphics graphics, FactoryPassiveIoLayout layout,
            List<BlockView> blocks, int scroll, @Nullable Hit hover, boolean dragging,
            String emptyText) {
        Viewport viewport = layout.viewport();
        if (blocks.isEmpty()) {
            drawEmpty(graphics, viewport, emptyText);
            return;
        }
        for (BlockBox box : layout.boxes()) {
            BlockView block = blocks.get(box.index());
            int top = FactoryPassiveIoLayout.screenY(viewport, box.top(), scroll);
            int bottom = FactoryPassiveIoLayout.screenY(viewport, box.bottom(), scroll);
            if (bottom <= viewport.y() || top >= viewport.y() + viewport.height()) {
                continue;
            }
            drawStatusLine(graphics, viewport, block, top,
                    FactoryPassiveIoLayout.MACHINE_ROW_H);
            drawMachineRow(graphics, viewport, block, top);
            if (layout.view() == FactoryPassiveIoView.LIST) {
                drawListBody(graphics, viewport, box, block, scroll, hover);
            } else {
                drawTileBody(graphics, viewport, box, block, scroll, hover, dragging);
            }
        }
        if (!dragging) {
            drawHover(graphics, layout, hover, scroll);
        }
    }

    /**
     * 画悬停高亮；chip 用自己的提亮，不整行铺白。
     *
     * @param graphics 画布
     * @param layout   排版
     * @param hover    命中
     * @param scroll   滚动
     */
    private void drawHover(GuiGraphics graphics, FactoryPassiveIoLayout layout,
            @Nullable Hit hover, int scroll) {
        if (hover == null) {
            return;
        }
        Viewport viewport = layout.viewport();
        BlockBox box = layout.boxes().get(hover.block());
        switch (hover.kind()) {
            case MACHINE -> graphics.fill(FactoryPassiveIoLayout.rowAreaX(viewport),
                    FactoryPassiveIoLayout.screenY(viewport, box.top(), scroll),
                    FactoryPassiveIoLayout.contentRight(viewport),
                    FactoryPassiveIoLayout.screenY(viewport, box.bodyTop(), scroll), HOVER_FILL);
            case RULE -> {
                if (layout.view() == FactoryPassiveIoView.LIST) {
                    int rowY = rowScreenY(layout, box, hover.rule(), scroll);
                    graphics.fill(FactoryPassiveIoLayout.bodyX(viewport), rowY,
                            FactoryPassiveIoLayout.contentRight(viewport),
                            rowY + FactoryPassiveIoLayout.ROW_H, HOVER_FILL);
                } else {
                    // 磁贴悬停必须用磁贴自己的几何；之前误用了列表行高，只亮了卡片的一截。
                    int row = hover.rule() / 2;
                    int col = hover.rule() % 2;
                    int tileX = FactoryPassiveIoLayout.tileX(viewport, col);
                    int tileY = FactoryPassiveIoLayout.tileRowY(viewport, box, row, scroll);
                    graphics.fill(tileX + 1, tileY + 1,
                            tileX + FactoryPassiveIoLayout.TILE_W - 1,
                            tileY + FactoryPassiveIoLayout.TILE_H - 1, HOVER_FILL);
                }
            }
            case ADD -> drawAddHover(graphics, layout, box, scroll);
            default -> {
                // chip 的高亮在 drawChip 内部按 chip 矩形画，这里不再叠第二层。
            }
        }
    }

    private void drawAddHover(GuiGraphics graphics, FactoryPassiveIoLayout layout, BlockBox box,
            int scroll) {
        Viewport viewport = layout.viewport();
        if (layout.view() == FactoryPassiveIoView.LIST) {
            int y = FactoryPassiveIoLayout.screenY(viewport, box.addTop(), scroll);
            graphics.fill(FactoryPassiveIoLayout.bodyX(viewport) + FactoryPassiveIoLayout.PAD,
                    y + 1, FactoryPassiveIoLayout.contentRight(viewport), y + FactoryPassiveIoLayout.ADD_ROW_H - 1,
                    HOVER_FILL);
            return;
        }
        int addIndex = box.ruleCount();
        int row = addIndex / 2;
        int col = addIndex % 2;
        int x = FactoryPassiveIoLayout.tileX(viewport, col);
        int y = FactoryPassiveIoLayout.tileRowY(viewport, box, row, scroll);
        graphics.fill(x + 1, y + 1, x + FactoryPassiveIoLayout.TILE_W - 1,
                y + FactoryPassiveIoLayout.TILE_H - 1, HOVER_FILL);
    }

    private int rowScreenY(FactoryPassiveIoLayout layout, BlockBox box, int rule, int scroll) {
        return FactoryPassiveIoLayout.rowY(layout.viewport(), box, rule, scroll);
    }

    private void drawStatusLine(GuiGraphics graphics, Viewport viewport, BlockView block,
            int top, int height) {
        graphics.fill(viewport.x(), top, viewport.x() + FactoryPassiveIoLayout.STATUS_W,
                top + height, block.status().color());
    }

    private void drawMachineRow(GuiGraphics graphics, Viewport viewport, BlockView block,
            int top) {
        int x = FactoryPassiveIoLayout.rowAreaX(viewport);
        int width = viewport.width() - FactoryPassiveIoLayout.STATUS_W;
        FactoryNineSlice.machineCard(graphics, x, top, width,
                FactoryPassiveIoLayout.MACHINE_ROW_H);
        int slotX = x + FactoryPassiveIoLayout.PAD;
        FactoryNineSlice.slot(graphics, slotX, top + 1, FactoryPassiveIoLayout.SLOT,
                FactoryPassiveIoLayout.SLOT);
        ItemStack icon = block.machine().machineMissing()
                ? new ItemStack(Items.BARRIER) : FactoryLabels.blockStack(block.machine().blockId());
        graphics.renderItem(icon.isEmpty() ? new ItemStack(Items.BARRIER) : icon,
                slotX + 1, top + 2);
        int nameX = slotX + FactoryPassiveIoLayout.SLOT + FactoryPassiveIoLayout.GAP;
        int right = FactoryPassiveIoLayout.contentRight(viewport);
        int badge2X = right - 4 - 2 - FactoryPassiveIoLayout.BADGE_W;
        int badge1X = badge2X - 2 - FactoryPassiveIoLayout.BADGE_W;
        graphics.drawString(this.font,
                ellipsize(block.name(), badge1X - 2 - nameX), nameX, top + 6,
                0xFFFFFFFF, false);
        dashedRect(graphics, badge1X, top + 4, FactoryPassiveIoLayout.BADGE_W,
                FactoryPassiveIoLayout.BADGE_H, 0xFFD8D9DF);
        dashedRect(graphics, badge2X, top + 4, FactoryPassiveIoLayout.BADGE_W,
                FactoryPassiveIoLayout.BADGE_H, 0xFFD8D9DF);
        FactoryNineSlice.icon(graphics, block.status().lamp(), right - 4, top + 8, 4);
    }

    private void drawListBody(GuiGraphics graphics, Viewport viewport, BlockBox box,
            BlockView block, int scroll, @Nullable Hit hover) {
        int x = FactoryPassiveIoLayout.bodyX(viewport);
        int width = viewport.width();
        int bodyTop = FactoryPassiveIoLayout.screenY(viewport, box.bodyTop(), scroll);
        int bottom = FactoryPassiveIoLayout.screenY(viewport, box.bottom(), scroll);
        FactoryNineSlice.card(graphics, x, bodyTop, width, bottom - bodyTop);
        // 卡片外 1px 阴影，和亮边一起把块从终端底板上抬起来。
        graphics.fill(x + 1, bottom, x + width, bottom + 1, CARD_SHADOW);
        graphics.fill(x + width, bodyTop + 1, x + width + 1, bottom + 1, CARD_SHADOW);
        for (int i = 0; i < block.rules().size(); i++) {
            drawRuleRow(graphics, viewport, box, block, block.rules().get(i), i, scroll, hover);
        }
        drawAddRow(graphics, viewport, box, scroll, hover);
    }

    private void drawRuleRow(GuiGraphics graphics, Viewport viewport, BlockBox box,
            BlockView block, RuleRow rule, int index, int scroll, @Nullable Hit hover) {
        int y = FactoryPassiveIoLayout.rowY(viewport, box, index, scroll);
        int x = FactoryPassiveIoLayout.bodyX(viewport);
        FactoryNineSlice.card(graphics, x + 1, y, viewport.width() - 2,
                FactoryPassiveIoLayout.ROW_H);
        graphics.fill(x + FactoryPassiveIoLayout.PAD, y,
                x + FactoryPassiveIoLayout.PAD + FactoryPassiveIoLayout.DIR_STRIPE_W,
                y + FactoryPassiveIoLayout.ROW_H, directionColor(rule));
        int slotX = FactoryPassiveIoLayout.slotX(viewport);
        int slotY = y + (FactoryPassiveIoLayout.ROW_H - FactoryPassiveIoLayout.SLOT) / 2;
        FactoryNineSlice.slot(graphics, slotX, slotY, FactoryPassiveIoLayout.SLOT,
                FactoryPassiveIoLayout.SLOT);
        FactoryResourceIconRender.draw(graphics, this.font, rule.spec().key(), slotX + 1,
                slotY + 1);
        int nameX = FactoryPassiveIoLayout.nameX(viewport);
        int chipX = FactoryPassiveIoLayout.chipX(viewport, 0);
        int textY = y + (FactoryPassiveIoLayout.ROW_H - 8) / 2;
        graphics.drawString(this.font,
                ellipsize(FactoryLabels.name(rule.spec().key()), chipX - 2 - nameX), nameX,
                textY, this.theme.textPrimary(), false);
        drawChips(graphics, viewport, block, rule, y, chipIndex(hover, box.index(), index));
        drawRate(graphics, rule, textY,
                FactoryPassiveIoLayout.rateX(viewport) + FactoryPassiveIoLayout.RATE_W,
                FactoryPassiveIoLayout.RATE_W);
    }

    private void drawChips(GuiGraphics graphics, Viewport viewport, BlockView block, RuleRow rule,
            int y, int hoveredChip) {
        int cy = y + (FactoryPassiveIoLayout.ROW_H - FactoryPassiveIoLayout.CHIP) / 2;
        drawChip(graphics, FactoryPassiveIoLayout.chipX(viewport, 0), cy,
                directionLabel(rule), directionColor(rule), 0xFFFFFFFF, true,
                hoveredChip == 0);
        drawChip(graphics, FactoryPassiveIoLayout.chipX(viewport, 1), cy, faceLabel(rule.spec()),
                this.theme.slotBg(), this.theme.textPrimary(), true, hoveredChip == 1);
        boolean item = rule.spec().key() instanceof AEItemKey;
        drawChip(graphics, FactoryPassiveIoLayout.chipX(viewport, 2), cy, modeLabel(rule.spec()),
                item ? modeColor(rule.spec()) : this.theme.slotBg(),
                item ? modeTextColor(rule.spec()) : this.theme.textDim(), item, hoveredChip == 2);
    }

    /**
     * 画一枚 chip。
     *
     * @param graphics  画布
     * @param x         左边缘
     * @param y         上边缘
     * @param label     单字标签
     * @param background 底色
     * @param textColor 字色
     * @param enabled   是否可点
     * @param hovered   是否悬停
     */
    private void drawChip(GuiGraphics graphics, int x, int y, String label, int background,
            int textColor, boolean enabled, boolean hovered) {
        int chip = FactoryPassiveIoLayout.CHIP;
        graphics.fill(x, y, x + chip, y + chip, this.theme.io().border());
        graphics.fill(x + 1, y + 1, x + chip - 1, y + chip - 1, background);
        if (enabled && hovered) {
            // 高亮垫在文字下面，避免半透明填充把字盖灰；位置严格按 chip 矩形，不另算。
            graphics.fill(x + 1, y + 1, x + chip - 1, y + chip - 1, CHIP_HOVER);
        }
        int textX = x + (chip - this.font.width(label)) / 2;
        graphics.drawString(this.font, label, textX, y + 2, textColor, false);
        if (!enabled) {
            graphics.fill(x + 1, y + 1, x + chip - 1, y + chip - 1, 0x55FFFFFF);
        }
    }

    private void drawRate(GuiGraphics graphics, RuleRow rule, int textY, int right,
            int maxWidth) {
        IoResult.Reason reason = FactoryLabels.effectiveReason(rule.rate(), rule.reason());
        int color = rule.rate() <= 0L ? this.theme.statusIdle()
                : FactoryLabels.rateColorUi(rule.rate(), reason, this.theme);
        String clipped = ellipsize(rateText(rule), maxWidth);
        graphics.drawString(this.font, clipped, right - this.font.width(clipped), textY, color,
                false);
    }

    /**
     * 终端里的速率文案：FE 去掉「 FE」后缀，单位留给 tooltip。
     *
     * <p>速率列只有 38px，{@code 28.1G FE/s} 放不下；FE 的类型已经由图标和颜色表达。</p>
     *
     * @param rule 规则
     * @return 速率文案
     */
    private static String rateText(RuleRow rule) {
        CapabilityKind kind = AeKeySupport.kind(rule.spec().key());
        if (kind == CapabilityKind.ENERGY) {
            return FactoryLabels.count(rule.rate()) + "/s";
        }
        return FactoryLabels.rateText(rule.rate(), kind);
    }

    private void drawAddRow(GuiGraphics graphics, Viewport viewport, BlockBox box, int scroll,
            @Nullable Hit hover) {
        int x = FactoryPassiveIoLayout.bodyX(viewport) + FactoryPassiveIoLayout.PAD;
        int y = FactoryPassiveIoLayout.screenY(viewport, box.addTop(), scroll);
        int width = FactoryPassiveIoLayout.contentRight(viewport) - x;
        boolean hovered = hover != null && hover.kind() == FactoryPassiveIoLayout.Kind.ADD
                && hover.block() == box.index();
        dashedRect(graphics, x, y, width, FactoryPassiveIoLayout.ADD_ROW_H,
                hovered ? this.theme.textValue() : this.theme.textDim());
        graphics.drawString(this.font, "+ 拖到这里新建行", x + 4,
                y + (FactoryPassiveIoLayout.ADD_ROW_H - 8) / 2,
                hovered ? this.theme.textValue() : this.theme.textDim(), false);
    }

    private void drawTileBody(GuiGraphics graphics, Viewport viewport, BlockBox box,
            BlockView block, int scroll, @Nullable Hit hover, boolean dragging) {
        int x = FactoryPassiveIoLayout.bodyX(viewport);
        int width = viewport.width();
        int bodyTop = FactoryPassiveIoLayout.screenY(viewport, box.bodyTop(), scroll);
        int bottom = FactoryPassiveIoLayout.screenY(viewport, box.bottom(), scroll);
        FactoryNineSlice.card(graphics, x, bodyTop, width, bottom - bodyTop);
        // 卡片外 1px 阴影，和亮边一起把块从终端底板上抬起来。
        graphics.fill(x + 1, bottom, x + width, bottom + 1, CARD_SHADOW);
        graphics.fill(x + width, bodyTop + 1, x + width + 1, bottom + 1, CARD_SHADOW);
        int count = block.rules().size();
        for (int i = 0; i <= count; i++) {
            int row = i / 2;
            int col = i % 2;
            int tileX = FactoryPassiveIoLayout.tileX(viewport, col);
            int tileY = FactoryPassiveIoLayout.tileRowY(viewport, box, row, scroll);
            if (i == count) {
                drawAddTile(graphics, tileX, tileY, hover != null
                        && hover.kind() == FactoryPassiveIoLayout.Kind.ADD
                        && hover.block() == box.index(), dragging);
                continue;
            }
            drawTile(graphics, viewport, block, block.rules().get(i), box.index(), i, tileX,
                    tileY, hover);
        }
    }

    private void drawTile(GuiGraphics graphics, Viewport viewport, BlockView block, RuleRow rule,
            int blockIndex, int index, int x, int y, @Nullable Hit hover) {
        FactoryNineSlice.card(graphics, x, y, FactoryPassiveIoLayout.TILE_W,
                FactoryPassiveIoLayout.TILE_H);
        graphics.fill(x + 1, y + 1, x + 1 + TILE_DIR_STRIPE_W, y + 1 + TILE_DIR_STRIPE_H,
                directionColor(rule));
        int slotX = x + FactoryPassiveIoLayout.TILE_INSET;
        FactoryNineSlice.slot(graphics, slotX, y + 2, FactoryPassiveIoLayout.SLOT,
                FactoryPassiveIoLayout.SLOT);
        FactoryResourceIconRender.draw(graphics, this.font, rule.spec().key(), slotX + 1, y + 3);
        int nameX = x + FactoryPassiveIoLayout.TILE_NAME_X;
        graphics.drawString(this.font,
                ellipsize(FactoryLabels.name(rule.spec().key()), TILE_NAME_W), nameX, y + 7,
                this.theme.textPrimary(), false);
        int chipY = y + FactoryPassiveIoLayout.TILE_CHIP_Y;
        int hoveredChip = chipIndex(hover, blockIndex, index);
        drawChip(graphics, slotX, chipY, directionLabel(rule), directionColor(rule), 0xFFFFFFFF,
                true, hoveredChip == 0);
        drawChip(graphics, slotX + FactoryPassiveIoLayout.CHIP + FactoryPassiveIoLayout.GAP, chipY,
                faceLabel(rule.spec()), this.theme.slotBg(), this.theme.textPrimary(), true,
                hoveredChip == 1);
        boolean item = rule.spec().key() instanceof AEItemKey;
        drawChip(graphics, slotX + (FactoryPassiveIoLayout.CHIP + FactoryPassiveIoLayout.GAP) * 2,
                chipY, modeLabel(rule.spec()),
                item ? modeColor(rule.spec()) : this.theme.slotBg(),
                item ? modeTextColor(rule.spec()) : this.theme.textDim(), item, hoveredChip == 2);
        String rate = ellipsize(rateText(rule), TILE_RATE_W);
        int rateRight = x + FactoryPassiveIoLayout.TILE_W - 2;
        IoResult.Reason reason = FactoryLabels.effectiveReason(rule.rate(), rule.reason());
        int color = rule.rate() <= 0L ? this.theme.statusIdle()
                : FactoryLabels.rateColorUi(rule.rate(), reason, this.theme);
        graphics.drawString(this.font, rate, rateRight - this.font.width(rate), chipY + 1, color,
                false);
    }

    private void drawAddTile(GuiGraphics graphics, int x, int y, boolean hovered,
            boolean dragging) {
        dashedRect(graphics, x, y, FactoryPassiveIoLayout.TILE_W, FactoryPassiveIoLayout.TILE_H,
                hovered || dragging ? this.theme.textValue() : this.theme.textDim());
        String plus = "+";
        int plusX = x + (FactoryPassiveIoLayout.TILE_W - this.font.width(plus)) / 2;
        graphics.drawString(this.font, plus, plusX, y + 12,
                hovered || dragging ? this.theme.textValue() : this.theme.textDim(), false);
    }

    /**
     * 悬停的 chip 序号；必须同时匹配机器块与规则下标，否则不同机器的同序号 chip 会一起亮。
     *
     * @param hover      命中
     * @param blockIndex 机器块下标
     * @param ruleIndex  规则下标
     * @return 0/1/2；没有命中 chip 时为 -1
     */
    private static int chipIndex(@Nullable Hit hover, int blockIndex, int ruleIndex) {
        if (hover == null || hover.block() != blockIndex || hover.rule() != ruleIndex) {
            return -1;
        }
        return switch (hover.kind()) {
            case CHIP_DIR -> 0;
            case CHIP_FACE -> 1;
            case CHIP_MODE -> 2;
            default -> -1;
        };
    }

    private void drawEmpty(GuiGraphics graphics, Viewport viewport, String text) {
        int x = viewport.x() + (viewport.width() - this.font.width(text)) / 2;
        graphics.drawString(this.font, text, x, viewport.y() + 12, this.theme.textDim(), false);
    }

    /**
     * 按宽度截断并加省略号。
     *
     * @param text  原文
     * @param width 可用宽度
     * @return 不超过宽度的文本
     */
    private String ellipsize(String text, int width) {
        if (width <= 0) {
            return "";
        }
        if (this.font.width(text) <= width) {
            return text;
        }
        String ellipsis = "…";
        int body = width - this.font.width(ellipsis);
        return body <= 0 ? "" : this.font.plainSubstrByWidth(text, body) + ellipsis;
    }

    /**
     * 画 1px 虚线框（2 实 2 空）。
     *
     * <p>虚线是放置目标的语义，不是立体边框；不生成贴图，按像素循环画，任意宽度都不会被拉伸糊掉。</p>
     */
    private static void dashedRect(GuiGraphics graphics, int x, int y, int width, int height,
            int color) {
        for (int i = 0; i < width; i++) {
            if ((i / 2) % 2 != 0) {
                continue;
            }
            graphics.fill(x + i, y, x + i + 1, y + 1, color);
            graphics.fill(x + i, y + height - 1, x + i + 1, y + height, color);
        }
        for (int j = 0; j < height; j++) {
            if ((j / 2) % 2 != 0) {
                continue;
            }
            graphics.fill(x, y + j, x + 1, y + j + 1, color);
            graphics.fill(x + width - 1, y + j, x + width, y + j + 1, color);
        }
    }

    private int directionColor(RuleRow rule) {
        return rule.input() ? this.theme.io().dirIn() : this.theme.io().dirOut();
    }

    private static String directionLabel(RuleRow rule) {
        return rule.input() ? "入" : "出";
    }

    private static String faceLabel(ResourceSpec spec) {
        return spec.face() == null ? "任" : FactoryLabels.face(spec);
    }

    private static String modeLabel(ResourceSpec spec) {
        return spec.mode() == io.github.crafteve.idlefactory.factory.machine.MatchMode.EXACT
                ? "精" : "模";
    }

    private int modeColor(ResourceSpec spec) {
        return spec.mode() == io.github.crafteve.idlefactory.factory.machine.MatchMode.EXACT
                ? this.theme.io().modeExact() : this.theme.io().modeFuzzy();
    }

    private int modeTextColor(ResourceSpec spec) {
        return spec.mode() == io.github.crafteve.idlefactory.factory.machine.MatchMode.EXACT
                ? this.theme.io().modeExactText() : this.theme.io().modeFuzzyText();
    }
}
