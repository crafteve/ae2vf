package io.github.crafteve.idlefactory.client.widget;

import io.github.crafteve.idlefactory.client.theme.GuiTheme;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 槽位绘制工具。
 *
 * <p>存储网格和玩家背包现在都是真实 {@link net.minecraft.world.inventory.Slot}（存储用 AE2 的
 * {@code RepoSlot}），这里只保留统一的 18×18 内凹格子底图。</p>
 */
public final class ItemGrid {
    /** 格子边长。 */
    public static final int CELL_SIZE = 18;
    /** 每行格子数。 */
    public static final int COLUMNS = 9;

    private ItemGrid() {
        // 纯静态工具类
    }

    /** 画一个普通槽位底图（内凹边）。 */
    public static void drawSlotBackground(GuiGraphics guiGraphics, GuiTheme theme, int cellX, int cellY) {
        drawSlotBackground(guiGraphics, theme, cellX, cellY, false);
    }

    /**
     * 画槽位底图：普通态用内凹边，悬停态切到主题的 hoverBg / slotBorderHover。
     *
     * @param hovered 是否悬停（由屏幕用当前鼠标位置判断）
     */
    public static void drawSlotBackground(GuiGraphics guiGraphics, GuiTheme theme, int cellX, int cellY,
            boolean hovered) {
        if (hovered) {
            guiGraphics.fill(cellX, cellY, cellX + CELL_SIZE, cellY + CELL_SIZE, theme.hoverBg());
            int edge = theme.slotBorderHover();
            guiGraphics.fill(cellX, cellY, cellX + CELL_SIZE, cellY + 1, edge);
            guiGraphics.fill(cellX, cellY, cellX + 1, cellY + CELL_SIZE, edge);
            guiGraphics.fill(cellX, cellY + CELL_SIZE - 1, cellX + CELL_SIZE, cellY + CELL_SIZE, edge);
            guiGraphics.fill(cellX + CELL_SIZE - 1, cellY, cellX + CELL_SIZE, cellY + CELL_SIZE, edge);
            return;
        }

        guiGraphics.fill(cellX, cellY, cellX + CELL_SIZE, cellY + CELL_SIZE, theme.slotBg());
        guiGraphics.fill(cellX, cellY, cellX + CELL_SIZE, cellY + 1, theme.slotBorderDark());
        guiGraphics.fill(cellX, cellY, cellX + 1, cellY + CELL_SIZE, theme.slotBorderDark());
        guiGraphics.fill(cellX, cellY + CELL_SIZE - 1, cellX + CELL_SIZE, cellY + CELL_SIZE, theme.slotBorderLight());
        guiGraphics.fill(cellX + CELL_SIZE - 1, cellY, cellX + CELL_SIZE, cellY + CELL_SIZE, theme.slotBorderLight());
    }
}
