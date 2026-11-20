package io.github.crafteve.idlefactory.client.screen.terminal.passiveio;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEItemKey;

import io.github.crafteve.idlefactory.client.screen.paint.Card;
import io.github.crafteve.idlefactory.client.screen.FactoryGuiTheme;
import io.github.crafteve.idlefactory.client.screen.paint.KeycapButton;
import io.github.crafteve.idlefactory.client.screen.FactoryLabels;
import io.github.crafteve.idlefactory.client.screen.paint.StatusBar;
import io.github.crafteve.idlefactory.client.screen.NineSliceRenderer;
import io.github.crafteve.idlefactory.client.screen.ResourceIconRenderer;
import io.github.crafteve.idlefactory.client.screen.terminal.passiveio.PassiveIoLayout.BlockBox;
import io.github.crafteve.idlefactory.client.screen.terminal.passiveio.PassiveIoLayout.Hit;
import io.github.crafteve.idlefactory.client.screen.terminal.passiveio.PassiveIoLayout.Viewport;
import io.github.crafteve.idlefactory.client.screen.terminal.passiveio.PassiveIoModel.BlockView;
import io.github.crafteve.idlefactory.client.screen.terminal.passiveio.PassiveIoModel.RuleRow;
import io.github.crafteve.idlefactory.io.IoReason;
import io.github.crafteve.idlefactory.observation.NodeHealth;
import io.github.crafteve.idlefactory.resource.AeKeyAdapter;
import io.github.crafteve.idlefactory.menu.FactoryPassiveIoView;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 被动 IO 内容区的绘制。
 *
 * <p>只读模型与排版，不写任何状态：鼠标悬停由屏幕算好 {@link Hit} 传进来，滚动只影响
 * {@code scroll}，不改排版。所有静态外观来自 {@link NineSliceRenderer} 贴图与
 * {@link FactoryGuiTheme.Io} 色值。</p>
 *
 * <p>列表式为机器行 + 规则行；磁贴式为机器行 + 77×34 磁贴两列。每台机器只有一圈机器大卡片，
 * 机器行是它顶部的一条深色带；状态灯只画在机器行，走 {@link StatusBar}，落在卡片轮廓之内。
 * 没有虚线新增行 / 磁贴：整张卡片就是拖入目标。</p>
 */
final class PassiveIoRender {
    /** 滚动到中途时，内容在视口上 / 下沿被切掉处的内阴影；由外向内逐像素变淡。 */
    private static final int[] SCROLL_EDGE_SHADE = {0x45000000, 0x26000000, 0x10000000};
    /** 悬停底色（白色 12%）。 */
    private static final int HOVER_FILL = 0x1FFFFFFF;
    /** 磁贴里速率列宽：chip 行之后剩下的宽度，不能和 chip 抢位置。 */
    private static final int TILE_RATE_W = PassiveIoLayout.TILE_W
            - (PassiveIoLayout.TILE_INSET + PassiveIoLayout.CHIP * 3
                    + PassiveIoLayout.GAP * 2) - 2;
    /** 磁贴名字宽。 */
    private static final int TILE_NAME_W = PassiveIoLayout.TILE_W
            - PassiveIoLayout.TILE_NAME_X - 2;

    private final Font font;
    private final FactoryGuiTheme theme;

    /**
     * @param font  字体
     * @param theme 当前主题
     */
    PassiveIoRender(Font font, FactoryGuiTheme theme) {
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
    void draw(GuiGraphics graphics, PassiveIoLayout layout,
            List<BlockView> blocks, int scroll, @Nullable Hit hover, boolean dragging,
            String emptyText) {
        Viewport viewport = layout.viewport();
        if (blocks.isEmpty()) {
            drawEmpty(graphics, viewport, emptyText);
            return;
        }
        for (BlockBox box : layout.boxes()) {
            BlockView block = blocks.get(box.index());
            int top = PassiveIoLayout.screenY(viewport, box.top(), scroll);
            int bottom = PassiveIoLayout.screenY(viewport, box.bottom(), scroll);
            if (bottom <= viewport.y() || top >= viewport.y() + viewport.height()) {
                continue;
            }
            // 宽度收到视口内 1px：外阴影画在卡片右侧，不收就会永远被视口裁剪。
            Card.draw(graphics, PassiveIoLayout.bodyX(viewport), top,
                    viewport.width() - 1, box.bottom() - box.top(),
                    Card.Level.MACHINE, true);
            drawMachineRow(graphics, viewport, block, box, top, bottom, hover);
            if (layout.view() == FactoryPassiveIoView.LIST) {
                drawListBody(graphics, viewport, box, block, scroll, hover);
            } else {
                drawTileBody(graphics, viewport, box, block, scroll, hover);
            }
        }
        if (!dragging) {
            drawHover(graphics, layout, hover, scroll);
        }
        drawScrollEdges(graphics, layout, scroll);
    }

    /**
     * 画视口上 / 下沿的滚动内阴影。
     *
     * <p>内容被裁在视口里，卡片滚到边缘时上亮边会被切掉、看起来变平；在确实还有内容的
     * 那一侧压 3px 由深到浅的内阴影，读作「压在视口边下继续」，不伪造卡片边线。
     * 只覆盖有溢出的一侧：滚到顶不画上沿，滚到底不画下沿。</p>
     *
     * @param graphics 画布
     * @param layout   排版
     * @param scroll   滚动像素
     */
    private static void drawScrollEdges(GuiGraphics graphics, PassiveIoLayout layout,
            int scroll) {
        Viewport viewport = layout.viewport();
        int maxScroll = layout.maxScroll(viewport.height());
        if (maxScroll <= 0) {
            return;
        }
        int left = viewport.x();
        int right = viewport.x() + viewport.width();
        for (int i = 0; i < SCROLL_EDGE_SHADE.length; i++) {
            if (scroll > 0) {
                graphics.fill(left, viewport.y() + i, right, viewport.y() + i + 1,
                        SCROLL_EDGE_SHADE[i]);
            }
            if (scroll < maxScroll) {
                int y = viewport.y() + viewport.height() - 1 - i;
                graphics.fill(left, y, right, y + 1, SCROLL_EDGE_SHADE[i]);
            }
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
    private void drawHover(GuiGraphics graphics, PassiveIoLayout layout,
            @Nullable Hit hover, int scroll) {
        if (hover == null) {
            return;
        }
        Viewport viewport = layout.viewport();
        BlockBox box = layout.boxes().get(hover.block());
        switch (hover.kind()) {
            // 没有规则的机器没有块体，高亮不能越过卡片下边缘压到块间缝里。
            case MACHINE -> graphics.fill(PassiveIoLayout.headerX(viewport),
                    PassiveIoLayout.screenY(viewport, box.top(), scroll),
                    PassiveIoLayout.contentRight(viewport),
                    PassiveIoLayout.screenY(viewport,
                            Math.min(box.bodyTop(), box.bottom()), scroll), HOVER_FILL);
            case RULE -> {
                if (layout.view() == FactoryPassiveIoView.LIST) {
                    int rowY = rowScreenY(layout, box, hover.rule(), scroll);
                    graphics.fill(PassiveIoLayout.bodyX(viewport), rowY,
                            PassiveIoLayout.contentRight(viewport),
                            rowY + PassiveIoLayout.ROW_H, HOVER_FILL);
                } else {
                    // 磁贴悬停必须用磁贴自己的几何；之前误用了列表行高，只亮了卡片的一截。
                    int row = hover.rule() / 2;
                    int col = hover.rule() % 2;
                    int tileX = PassiveIoLayout.tileX(viewport, col);
                    int tileY = PassiveIoLayout.tileRowY(viewport, box, row, scroll);
                    graphics.fill(tileX + 1, tileY + 1,
                            tileX + PassiveIoLayout.TILE_W - 1,
                            tileY + PassiveIoLayout.TILE_H - 1, HOVER_FILL);
                }
            }
            case ADD -> {
                // 卡片空白区是拖入目标，悬停不铺色：拖拽高亮由 JEI / EMI 自己画。
            }
            case ENERGY, CHIP_DIR, CHIP_FACE, CHIP_MODE -> {
                // 键帽的高亮在绘制时按自己的矩形画，这里不再叠第二层。
            }
        }
    }

    private int rowScreenY(PassiveIoLayout layout, BlockBox box, int rule, int scroll) {
        return PassiveIoLayout.rowY(layout.viewport(), box, rule, scroll);
    }

    /**
     * 画机器行：机器大卡片顶部的一条深色带，不再单独成卡。
     *
     * <p>机器行以前是一张独立卡片，它的底阴影从灯右侧起算，状态灯那一列下面没有阴影接着，
     * 看起来就像浮在卡片外。现在深色带直接铺在机器大卡片内部，最左 3px 交给状态灯，
     * 卡片的下 / 右外阴影把灯一起收进轮廓里。</p>
     *
     * <p>高度再按块高夹一次：没有规则的机器整块只有机器行高，深色带不能盖掉卡片自己的
     * 下暗边。</p>
     *
     * @param graphics    画布
     * @param viewport    视口
     * @param block       块
     * @param box         块几何（FE 按钮要和命中用同一份坐标）
     * @param top         机器行上边缘（屏幕坐标）
     * @param blockBottom 机器大卡片下边缘（屏幕坐标）
     * @param hover       当前悬停；FE 按钮自己提亮
     */
    private void drawMachineRow(GuiGraphics graphics, Viewport viewport, BlockView block,
            BlockBox box, int top, int blockBottom, @Nullable Hit hover) {
        int left = PassiveIoLayout.bodyX(viewport) + PassiveIoLayout.ROW_INSET;
        // 右边界排掉卡片最右 1px 竖边，深色带只铺在内区，卡片亮边 / 暗边保持完整。
        int right = PassiveIoLayout.bodyX(viewport) + viewport.width() - 2;
        int height = Math.min(PassiveIoLayout.MACHINE_ROW_H - 1, blockBottom - top - 2);
        if (height <= 0) {
            return;
        }
        graphics.fill(left, top + 1, right, top + 1 + height, this.theme.io().machineRow());
        StatusBar.draw(graphics, left, top + 1, height, block.status().color());
        int slotX = PassiveIoLayout.headerX(viewport);
        NineSliceRenderer.slot(graphics, slotX, top + 1, PassiveIoLayout.SLOT,
                PassiveIoLayout.SLOT);
        ItemStack icon = block.node().core().health() == NodeHealth.OFFLINE
                ? new ItemStack(Items.BARRIER) : FactoryLabels.blockStack(block.machine().blockId());
        graphics.renderItem(icon.isEmpty() ? new ItemStack(Items.BARRIER) : icon,
                slotX + 1, top + 2);
        int nameX = slotX + PassiveIoLayout.SLOT + PassiveIoLayout.GAP;
        int contentRight = PassiveIoLayout.contentRight(viewport);
        // 状态灯占最右 3px；数量用原版物品数量修饰器，叠在图标右下角，和背包一致。
        int lampX = contentRight - 5;
        boolean hasEnergy = AeKeyAdapter.energy() != null;
        // FE 按钮占住状态灯左侧；名字可用宽度要把按钮让出来，不能压到按钮上。
        int nameRight = hasEnergy ? PassiveIoLayout.feButtonX(viewport) - 2 : lampX - 4;
        if (block.machineCount() > 1) {
            graphics.renderItemDecorations(this.font,
                    icon.copyWithCount(block.machineCount()), slotX + 1, top + 2);
        }
        graphics.drawString(this.font,
                ellipsize(block.name(), nameRight - nameX), nameX, top + 6,
                this.theme.textPrimary(), false);
        NineSliceRenderer.icon(graphics, block.status().lamp(), lampX, top + 8, 3);
        if (hasEnergy) {
            drawEnergyButton(graphics, viewport, box, top, hover);
        }
    }

    /**
     * 画机器行右侧的 FE 快捷按钮：13px 键帽 + 缩小的「FE」两字。
     *
     * <p>两字放不进 9px 内区，横竖都缩到 0.75；按钮只在装了 Applied Flux 时出现，
     * 没装时这里根本没有 FE 这类资源，画出来只会误导。</p>
     *
     * @param graphics 画布
     * @param viewport 视口
     * @param box      块几何
     * @param top      机器行上边缘（屏幕坐标）
     * @param hover    当前悬停
     */
    private void drawEnergyButton(GuiGraphics graphics, Viewport viewport, BlockBox box, int top,
            @Nullable Hit hover) {
        int x = PassiveIoLayout.feButtonX(viewport);
        int y = PassiveIoLayout.feButtonY(top);
        boolean hovered = hover != null && hover.kind() == PassiveIoLayout.Kind.ENERGY
                && hover.block() == box.index();
        KeycapButton.draw(graphics, this.theme, x, y, PassiveIoLayout.FE_BUTTON,
                PassiveIoLayout.FE_BUTTON, this.theme.bevelLight(), true, hovered);
        graphics.pose().pushPose();
        graphics.pose().translate(x + 2, y + 4, 0.0F);
        graphics.pose().scale(0.75F, 0.75F, 1.0F);
        graphics.drawString(this.font, "FE", 0, 0, this.theme.textPrimary(), false);
        graphics.pose().popPose();
    }

    private void drawListBody(GuiGraphics graphics, Viewport viewport, BlockBox box,
            BlockView block, int scroll, @Nullable Hit hover) {
        if (block.rules().isEmpty()) {
            return;
        }
        // 行不再各自成卡，块体底铺一层面板色：和原来行卡片的底色一致，只是没有边和阴影。
        int left = PassiveIoLayout.bodyX(viewport) + PassiveIoLayout.ROW_INSET;
        int right = PassiveIoLayout.bodyX(viewport) + viewport.width() - 2;
        int top = PassiveIoLayout.screenY(viewport, box.bodyTop(), scroll);
        int bottom = PassiveIoLayout.screenY(viewport, box.bottom(), scroll) - 1;
        if (bottom > top) {
            graphics.fill(left, top, right, bottom, this.theme.panelBg());
        }
        for (int i = 0; i < block.rules().size(); i++) {
            drawRuleRow(graphics, viewport, box, block.rules().get(i), i, scroll, hover);
        }
    }

    private void drawRuleRow(GuiGraphics graphics, Viewport viewport, BlockBox box,
            RuleRow rule, int index, int scroll, @Nullable Hit hover) {
        int y = PassiveIoLayout.rowY(viewport, box, index, scroll);
        // 行不再套卡片：奇数行铺一层比块体底亮的底，和偶数行交替，行间不留缝。
        if (index % 2 == 1) {
            graphics.fill(PassiveIoLayout.bodyX(viewport) + PassiveIoLayout.ROW_INSET,
                    y, PassiveIoLayout.bodyX(viewport) + viewport.width() - 2,
                    y + PassiveIoLayout.ROW_H, this.theme.bevelLight());
        }
        int slotX = PassiveIoLayout.slotX(viewport);
        // 行不再套卡片，也不再画槽位底：图标直接落在机器卡片的浅色底上。
        int iconY = y + (PassiveIoLayout.ROW_H - PassiveIoLayout.RULE_ICON) / 2;
        drawRuleIcon(graphics, rule, slotX, iconY);
        int nameX = PassiveIoLayout.nameX(viewport);
        int chipX = PassiveIoLayout.chipX(viewport, 0);
        int textY = y + (PassiveIoLayout.ROW_H - 8) / 2;
        graphics.drawString(this.font,
                ellipsize(FactoryLabels.name(rule.key()), chipX - 2 - nameX), nameX,
                textY, this.theme.textPrimary(), false);
        drawChips(graphics, viewport, rule, y, chipIndex(hover, box.index(), index));
        drawRate(graphics, rule, textY,
                PassiveIoLayout.rateX(viewport) + PassiveIoLayout.RATE_W,
                PassiveIoLayout.RATE_W);
    }

    /**
     * 画规则行的资源图标：12px，直接落在机器卡片的浅色底上，不衬槽位。
     *
     * <p>原版图标与流体贴图固定 16px，这里用 pose 缩到 0.75 得到 12px；push / pop
     * 只包住图标，后面的文字、chip、速率都不受影响。</p>
     *
     * @param graphics 画布
     * @param rule     规则
     * @param x        图标左上角 x
     * @param y        图标左上角 y
     */
    private void drawRuleIcon(GuiGraphics graphics, RuleRow rule, int x, int y) {
        float scale = PassiveIoLayout.RULE_ICON / 16.0F;
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        ResourceIconRenderer.draw(graphics, this.font, rule.key(), 0, 0);
        graphics.pose().popPose();
    }

    private void drawChips(GuiGraphics graphics, Viewport viewport, RuleRow rule,
            int y, int hoveredChip) {
        int cy = y + PassiveIoLayout.CHIP_INSET_Y;
        drawChip(graphics, PassiveIoLayout.chipX(viewport, 0), cy,
                directionLabel(rule), directionColor(rule), 0xFFFFFFFF, true,
                hoveredChip == 0);
        drawChip(graphics, PassiveIoLayout.chipX(viewport, 1), cy, faceLabel(rule),
                this.theme.bevelLight(), this.theme.textPrimary(), true, hoveredChip == 1);
        boolean item = rule.key() instanceof AEItemKey;
        drawChip(graphics, PassiveIoLayout.chipX(viewport, 2), cy, modeLabel(rule),
                item ? modeColor(rule) : this.theme.bevelLight(),
                item ? modeTextColor(rule) : this.theme.textDim(), item, hoveredChip == 2);
    }

    /**
     * 画一枚 chip：1px 立体边 + 本色内区，和卡片同一套光照方向。
     *
     * <p>边框用「上 / 左亮、下 / 右暗」而不是四边同一个深灰：三枚 chip 连在一起时，
     * 平涂边框会让它们看起来像贴纸；有了明暗，chip 才像一颗按得下去的键。内区四周各留
     * 1px，9px 中文才不会顶到边框。</p>
     *
     * @param graphics   画布
     * @param x          左边缘
     * @param y          上边缘
     * @param label      单字标签
     * @param background 内区底色
     * @param textColor  字色
     * @param enabled    是否可点
     * @param hovered    是否悬停
     */
    private void drawChip(GuiGraphics graphics, int x, int y, String label, int background,
            int textColor, boolean enabled, boolean hovered) {
        int chip = PassiveIoLayout.CHIP;
        KeycapButton.draw(graphics, this.theme, x, y, chip, chip, background, enabled, hovered);
        int textX = x + (chip - this.font.width(label)) / 2;
        graphics.drawString(this.font, label, textX, y + 2, textColor, false);
    }

    private void drawRate(GuiGraphics graphics, RuleRow rule, int textY, int right,
            int maxWidth) {
        IoReason reason = FactoryLabels.effectiveReason(rule.rate(), rule.reason());
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
        if (AeKeyAdapter.isEnergy(rule.key())) {
            return FactoryLabels.count(rule.rate()) + "/s";
        }
        return FactoryLabels.rateText(rule.rate(), rule.key());
    }

    private void drawTileBody(GuiGraphics graphics, Viewport viewport, BlockBox box,
            BlockView block, int scroll, @Nullable Hit hover) {
        for (int i = 0; i < block.rules().size(); i++) {
            int tileX = PassiveIoLayout.tileX(viewport, i % 2);
            int tileY = PassiveIoLayout.tileRowY(viewport, box, i / 2, scroll);
            drawTile(graphics, viewport, block, block.rules().get(i), box.index(), i, tileX,
                    tileY, hover);
        }
    }

    private void drawTile(GuiGraphics graphics, Viewport viewport, BlockView block, RuleRow rule,
            int blockIndex, int index, int x, int y, @Nullable Hit hover) {
        Card.draw(graphics, x, y, PassiveIoLayout.TILE_W,
                PassiveIoLayout.TILE_H, Card.Level.IO, true);
        int slotX = x + PassiveIoLayout.TILE_INSET;
        NineSliceRenderer.slot(graphics, slotX, y + 2, PassiveIoLayout.SLOT,
                PassiveIoLayout.SLOT);
        ResourceIconRenderer.draw(graphics, this.font, rule.key(), slotX + 1, y + 3);
        int nameX = x + PassiveIoLayout.TILE_NAME_X;
        graphics.drawString(this.font,
                ellipsize(FactoryLabels.name(rule.key()), TILE_NAME_W), nameX, y + 7,
                this.theme.textPrimary(), false);
        int chipY = y + PassiveIoLayout.TILE_CHIP_Y;
        int hoveredChip = chipIndex(hover, blockIndex, index);
        drawChip(graphics, slotX, chipY, directionLabel(rule), directionColor(rule), 0xFFFFFFFF,
                true, hoveredChip == 0);
        drawChip(graphics, slotX + PassiveIoLayout.CHIP + PassiveIoLayout.GAP, chipY,
                faceLabel(rule), this.theme.bevelLight(), this.theme.textPrimary(), true,
                hoveredChip == 1);
        boolean item = rule.key() instanceof AEItemKey;
        drawChip(graphics, slotX + (PassiveIoLayout.CHIP + PassiveIoLayout.GAP) * 2,
                chipY, modeLabel(rule),
                item ? modeColor(rule) : this.theme.bevelLight(),
                item ? modeTextColor(rule) : this.theme.textDim(), item, hoveredChip == 2);
        String rate = ellipsize(rateText(rule), TILE_RATE_W);
        int rateRight = x + PassiveIoLayout.TILE_W - 2;
        IoReason reason = FactoryLabels.effectiveReason(rule.rate(), rule.reason());
        int color = rule.rate() <= 0L ? this.theme.statusIdle()
                : FactoryLabels.rateColorUi(rule.rate(), reason, this.theme);
        // 速率和 chip 行同一中线：13px chip 的正文在 +2。
        graphics.drawString(this.font, rate, rateRight - this.font.width(rate), chipY + 2, color,
                false);
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

    private int directionColor(RuleRow rule) {
        return rule.input() ? this.theme.io().dirIn() : this.theme.io().dirOut();
    }

    private static String directionLabel(RuleRow rule) {
        return rule.input() ? "入" : "出";
    }

    private static String faceLabel(RuleRow rule) {
        return rule.face() == null ? "任" : FactoryLabels.face(rule.face());
    }

    private static String modeLabel(RuleRow spec) {
        // 非物品固定精确，chip 仍然写「精」但灰显禁用，保持和物品规则同一套外形。
        return spec.mode() == io.github.crafteve.idlefactory.machine.MatchMode.EXACT
                ? "精" : "模";
    }

    private int modeColor(RuleRow spec) {
        return spec.mode() == io.github.crafteve.idlefactory.machine.MatchMode.EXACT
                ? this.theme.io().modeExact() : this.theme.io().modeFuzzy();
    }

    private int modeTextColor(RuleRow spec) {
        return spec.mode() == io.github.crafteve.idlefactory.machine.MatchMode.EXACT
                ? this.theme.io().modeExactText() : this.theme.io().modeFuzzyText();
    }
}