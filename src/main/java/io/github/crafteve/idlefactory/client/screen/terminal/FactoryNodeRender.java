package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.client.screen.FactoryCard;
import io.github.crafteve.idlefactory.client.screen.FactoryGuiTheme;
import io.github.crafteve.idlefactory.client.screen.FactoryKeycap;
import io.github.crafteve.idlefactory.client.screen.FactoryLabels;
import io.github.crafteve.idlefactory.client.screen.FactoryNineSlice;
import io.github.crafteve.idlefactory.client.screen.FactoryLamp;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryNodeLayout.Hit;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryNodeLayout.Kind;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryNodeLayout.NodeBox;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryNodeLayout.Viewport;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryNodeModel.MemberRow;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryNodeModel.NodeRow;
import io.github.crafteve.idlefactory.factory.FactoryNodeSnapshotBuilder;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 节点管理内容区的绘制。
 *
 * <p>只读模型与排版，不写状态：悬停由屏幕算好 {@link Hit} 传进来。节点行复用被动 IO 的
 * {@link FactoryCard} 层级、{@link FactoryKeycap} 键帽与 {@link FactoryLamp} 状态灯，不另造
 * 一套卡片外观。</p>
 */
final class FactoryNodeRender {
    /** 悬停底色（白色 12%）。 */
    private static final int HOVER_FILL = 0x1FFFFFFF;
    /** 待确认删除的键帽底色。 */
    private static final int DELETE_ARMED = 0xFFFF5A5A;

    private final Font font;
    private final FactoryGuiTheme theme;

    FactoryNodeRender(Font font, FactoryGuiTheme theme) {
        this.font = font;
        this.theme = theme;
    }

    /**
     * 画整个内容区。
     */
    void draw(GuiGraphics graphics, FactoryNodeLayout layout, List<NodeRow> rows, int scroll,
            @Nullable Hit hover, @Nullable java.util.UUID pendingDelete, String emptyText) {
        Viewport viewport = layout.viewport();
        if (rows.isEmpty()) {
            graphics.drawString(this.font, emptyText,
                    viewport.x() + (viewport.width() - this.font.width(emptyText)) / 2,
                    viewport.y() + 12, this.theme.textDim(), false);
            return;
        }
        for (NodeBox box : layout.boxes()) {
            NodeRow row = rows.get(box.index());
            int top = FactoryNodeLayout.screenY(viewport, box.top(), scroll);
            int bottom = FactoryNodeLayout.screenY(viewport, box.bottom(), scroll);
            if (bottom <= viewport.y() || top >= viewport.y() + viewport.height()) {
                continue;
            }
            FactoryCard.draw(graphics, FactoryNodeLayout.cardX(viewport), top,
                    FactoryNodeLayout.cardWidth(viewport), box.bottom() - box.top(),
                    FactoryCard.Level.MACHINE, true);
            drawNodeRow(graphics, viewport, box, row, top, hover, pendingDelete);
            if (box.expanded()) {
                drawMembers(graphics, viewport, box, row, scroll, hover);
            }
        }
    }

    private void drawNodeRow(GuiGraphics graphics, Viewport viewport, NodeBox box, NodeRow row,
            int top, @Nullable Hit hover, @Nullable java.util.UUID pendingDelete) {
        int left = FactoryNodeLayout.cardX(viewport) + 1;
        int right = FactoryNodeLayout.cardRight(viewport) - 1;
        int bandTop = top + 1;
        int bandBottom = top + FactoryNodeLayout.NODE_ROW_H - 1;
        graphics.fill(left, bandTop, right, bandBottom, this.theme.io().machineRow());
        FactoryLamp.draw(graphics, left, bandTop, bandBottom - bandTop, row.status().color());

        int slotX = FactoryNodeLayout.nameX(viewport) - FactoryNodeLayout.SLOT - 1;
        FactoryNineSlice.slot(graphics, slotX, top + 1, FactoryNodeLayout.SLOT,
                FactoryNodeLayout.SLOT);
        ItemStack icon = row.view().machineMissing()
                ? new ItemStack(Items.BARRIER) : FactoryLabels.blockStack(row.view().blockId());
        graphics.renderItem(icon.isEmpty() ? new ItemStack(Items.BARRIER) : icon,
                slotX + 1, top + 2);
        if (row.view().machineCount() > 1) {
            drawBadge(graphics, slotX + FactoryNodeLayout.SLOT - FactoryNodeLayout.BADGE_W + 1,
                    top + FactoryNodeLayout.NODE_ROW_H - FactoryNodeLayout.BADGE_H,
                    row.view().machineCount());
        }

        String name = ellipsize(row.name(), FactoryNodeLayout.nameWidth(viewport));
        if (hover != null && (hover.kind() == Kind.NODE || hover.kind() == Kind.NAME)) {
            graphics.fill(FactoryNodeLayout.nameX(viewport) - 1, top + 4,
                    FactoryNodeLayout.troughX(viewport) - 2, top + 16, HOVER_FILL);
        }
        graphics.drawString(this.font, name, FactoryNodeLayout.nameX(viewport), top + 6,
                this.theme.textPrimary(), false);
        drawStatusHints(graphics, viewport, row, top);

        drawProvider(graphics, viewport, top, hover);
        int keyY = top + 1;
        int keyCenterY = keyY + FactoryNodeLayout.KEY_H / 2;
        boolean provider = row.view().provider().available();
        FactoryKeycap.draw(graphics, this.theme, FactoryNodeLayout.keyX(viewport, 0), keyY,
                FactoryNodeLayout.KEY_W, FactoryNodeLayout.KEY_H,
                provider && row.view().provider().blocking().isOn()
                        ? this.theme.statusRun() : this.theme.bevelLight(),
                provider, hovered(hover, Kind.BLOCKING));
        FactoryNodeIcons.blocking(graphics,
                FactoryNodeLayout.keyX(viewport, 0) + FactoryNodeLayout.KEY_W / 2, keyCenterY,
                provider && row.view().provider().blocking().isOn()
                        ? 0xFFFFFFFF : this.theme.textPrimary());

        for (int i = 1; i <= 2; i++) {
            FactoryKeycap.draw(graphics, this.theme, FactoryNodeLayout.keyX(viewport, i), keyY,
                    FactoryNodeLayout.KEY_W, FactoryNodeLayout.KEY_H, this.theme.bevelLight(),
                    false, false);
        }
        FactoryNodeIcons.multiplier(graphics,
                FactoryNodeLayout.keyX(viewport, 1) + FactoryNodeLayout.KEY_W / 2, keyCenterY,
                this.theme.textDim());
        FactoryNodeIcons.dispatch(graphics,
                FactoryNodeLayout.keyX(viewport, 2) + FactoryNodeLayout.KEY_W / 2, keyCenterY,
                this.theme.textDim());

        int actionX = FactoryNodeLayout.actionX(viewport);
        FactoryKeycap.draw(graphics, this.theme, actionX, top + FactoryNodeLayout.PLUS_Y,
                FactoryNodeLayout.ACTION_W, FactoryNodeLayout.ACTION_H, this.theme.bevelLight(),
                true, hovered(hover, Kind.EDIT));
        FactoryNodeIcons.plus(graphics, actionX + FactoryNodeLayout.ACTION_W / 2,
                top + FactoryNodeLayout.PLUS_Y + FactoryNodeLayout.ACTION_H / 2,
                this.theme.statusWarn());

        boolean armed = pendingDelete != null && pendingDelete.equals(row.nodeId());
        FactoryKeycap.draw(graphics, this.theme, actionX, top + FactoryNodeLayout.CROSS_Y,
                FactoryNodeLayout.ACTION_W, FactoryNodeLayout.ACTION_H,
                armed ? DELETE_ARMED : this.theme.bevelLight(), true,
                hovered(hover, Kind.DELETE));
        FactoryNodeIcons.cross(graphics, actionX + FactoryNodeLayout.ACTION_W / 2,
                top + FactoryNodeLayout.CROSS_Y + FactoryNodeLayout.ACTION_H / 2,
                armed ? 0xFFFFFFFF : this.theme.error());
    }

    private void drawBadge(GuiGraphics graphics, int x, int y, int count) {
        graphics.fill(x, y, x + FactoryNodeLayout.BADGE_W, y + FactoryNodeLayout.BADGE_H,
                0xB4000000);
        String text = Integer.toString(count);
        graphics.drawString(this.font, text, x + 1, y + 1, 0xFFFFFFFF, false);
    }

    private void drawStatusHints(GuiGraphics graphics, Viewport viewport, NodeRow row, int top) {
        String hint = statusHint(row);
        int nameX = FactoryNodeLayout.nameX(viewport);
        int nameWidth = FactoryNodeLayout.nameWidth(viewport);
        int used = this.font.width(row.name());
        if (hint.isEmpty() || used + 2 + this.font.width(hint) > nameWidth) {
            return;
        }
        graphics.drawString(this.font, hint, nameX + used + 2, top + 6, row.status().color(),
                false);
    }

    /**
     * 状态小字：失联 / 未载 / 非 ME。只放得下时画，完整状态始终在 tooltip 里。
     */
    private String statusHint(NodeRow row) {
        if (row.view().machineMissing()) {
            return "失联";
        }
        return switch (row.view().health()) {
            case MISSING, NO_DIMENSION -> "失联";
            case UNLOADED -> "未载";
            default -> row.view().provider().available() ? "" : "非 ME";
        };
    }

    private void drawProvider(GuiGraphics graphics, Viewport viewport, int top,
            @Nullable Hit hover) {
        int x = FactoryNodeLayout.troughX(viewport);
        int y = top + 1;
        int right = FactoryNodeLayout.troughRight(viewport);
        int bottom = y + FactoryNodeLayout.TROUGH_H;
        graphics.fill(x, y, right, bottom, this.theme.slotBg());
        graphics.fill(x, y, right, y + 1, this.theme.bevelDark());
        graphics.fill(x, y, x + 1, bottom, this.theme.bevelDark());
        graphics.fill(x, bottom - 1, right, bottom, this.theme.panelBorderLight());
        graphics.fill(right - 1, y, right, bottom, this.theme.panelBorderLight());
    }

    private void drawMembers(GuiGraphics graphics, Viewport viewport, NodeBox box, NodeRow row,
            int scroll, @Nullable Hit hover) {
        for (int i = 0; i < box.shownMembers(); i++) {
            MemberRow member = row.members().get(i);
            int y = FactoryNodeLayout.memberRowY(viewport, box, i, scroll);
            int x = FactoryNodeLayout.memberX(viewport);
            int width = FactoryNodeLayout.memberWidth(viewport);
            FactoryCard.draw(graphics, x, y, width, FactoryNodeLayout.MEMBER_ROW_H,
                    FactoryCard.Level.IO, true);
            if (hover != null && hover.kind() == Kind.MEMBER && hover.node() == box.index()
                    && hover.member() == i) {
                graphics.fill(x + 1, y + 1, x + width - 1, y + FactoryNodeLayout.MEMBER_ROW_H - 1,
                        HOVER_FILL);
            }
            int iconX = FactoryNodeLayout.memberIconX(viewport);
            int iconY = y + (FactoryNodeLayout.MEMBER_ROW_H - FactoryNodeLayout.MEMBER_ICON) / 2;
            ItemStack icon = FactoryLabels.blockStack(member.member().blockId());
            float scale = FactoryNodeLayout.MEMBER_ICON / 16.0F;
            graphics.pose().pushPose();
            graphics.pose().translate(iconX, iconY, 0.0F);
            graphics.pose().scale(scale, scale, 1.0F);
            graphics.renderItem(icon.isEmpty() ? new ItemStack(Items.BARRIER) : icon, 0, 0);
            graphics.pose().popPose();
            graphics.drawString(this.font, member.label(), FactoryNodeLayout.memberTextX(viewport),
                    y + 4, this.theme.textPrimary(), false);
            FactoryNineSlice.icon(graphics, member.status().lamp(),
                    FactoryNodeLayout.memberDotX(viewport), y + 6, 3);
            int locateX = FactoryNodeLayout.locateX(viewport);
            FactoryKeycap.draw(graphics, this.theme, locateX, y + 1, FactoryNodeLayout.LOCATE,
                    FactoryNodeLayout.MEMBER_ROW_H - 2, this.theme.bevelLight(), true,
                    hover != null && hover.kind() == Kind.LOCATE && hover.node() == box.index()
                            && hover.member() == i);
            FactoryNodeIcons.locate(graphics, locateX + FactoryNodeLayout.LOCATE / 2, y + 8,
                    this.theme.textPrimary());
        }
        if (box.totalMembers() > box.shownMembers()) {
            int y = FactoryNodeLayout.moreRowY(viewport, box, scroll);
            int x = FactoryNodeLayout.memberX(viewport);
            graphics.drawString(this.font,
                    "… " + (box.totalMembers() - box.shownMembers()) + " 台", x + 4, y + 3,
                    this.theme.textDim(), false);
        }
    }

    private boolean hovered(@Nullable Hit hover, Kind kind) {
        return hover != null && hover.kind() == kind;
    }

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
}
