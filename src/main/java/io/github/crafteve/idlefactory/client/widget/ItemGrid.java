package io.github.crafteve.idlefactory.client.widget;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.client.theme.GuiTheme;
import io.github.crafteve.idlefactory.network.payload.StorageSnapshotS2C;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;

import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * 存储物品网格。
 *
 * <p>9 列 × 18px 的内凹格子，带 200ms 悬停插值、青色选中外发光、右侧细滚动条。
 * 数据来自服务端快照（只读），滚动/选中/悬停都是纯客户端状态；所有颜色来自 {@link GuiTheme}。</p>
 */
public final class ItemGrid {
    /** 格子边长。 */
    public static final int CELL_SIZE = 18;
    /** 每行格子数。 */
    public static final int COLUMNS = 9;

    /** 悬停过渡时长（毫秒）。 */
    private static final float HOVER_DURATION_MS = 200.0F;
    /** 滚动条宽度。 */
    private static final int SCROLLBAR_WIDTH = 3;

    /** 网格左上角（绝对坐标）。 */
    private int x;
    private int y;
    /** 可见行数。 */
    private int rows = 1;

    private List<StorageSnapshotS2C.Entry> entries = List.of();

    /** 每个条目当前的悬停进度（0..1），按条目下标存。 */
    private final Map<Integer, Float> hoverProgress = new HashMap<>();
    private int scrollRow;
    private long lastFrameMs;

    private boolean draggingScrollbar;

    @Nullable
    private AEKey selectedKey;

    /**
     * 更新网格区域。由屏幕在 {@code init()} 里按当前面板尺寸调用。
     *
     * @param x    左上角 x
     * @param y    左上角 y
     * @param rows 可见行数（至少 1）
     */
    public void setBounds(int x, int y, int rows) {
        this.x = x;
        this.y = y;
        this.rows = Math.max(1, rows);
        this.scrollRow = Mth.clamp(this.scrollRow, 0, maxScrollRow());
    }

    /** 替换条目（过滤后的结果），滚动与悬停状态重置。 */
    public void setEntries(List<StorageSnapshotS2C.Entry> entries) {
        this.entries = entries;
        this.hoverProgress.clear();
        this.scrollRow = 0;
        this.lastFrameMs = 0L;
    }

    public boolean isEmpty() {
        return this.entries.isEmpty();
    }

    /** 可见区域宽度（不含滚动条）。 */
    public int width() {
        return COLUMNS * CELL_SIZE;
    }

    /** 可见区域高度。 */
    public int height() {
        return this.rows * CELL_SIZE;
    }

    /**
     * 渲染网格。
     *
     * @param guiGraphics 绘制上下文
     * @param font        字体
     * @param theme       当前主题
     * @param mouseX      鼠标 x
     * @param mouseY      鼠标 y
     */
    public void render(GuiGraphics guiGraphics, Font font, GuiTheme theme, int mouseX, int mouseY) {
        long now = Util.getMillis();
        float step = this.lastFrameMs == 0L
                ? 1.0F
                : Mth.clamp((now - this.lastFrameMs) / HOVER_DURATION_MS, 0.0F, 1.0F);
        this.lastFrameMs = now;

        this.scrollRow = Mth.clamp(this.scrollRow, 0, maxScrollRow());
        updateHover(indexAt(mouseX, mouseY), step);

        if (!this.entries.isEmpty()) {
            int start = this.scrollRow * COLUMNS;
            guiGraphics.enableScissor(this.x, this.y, this.x + width(), this.y + height());
            for (int i = 0; i < this.rows * COLUMNS; i++) {
                int cellX = this.x + (i % COLUMNS) * CELL_SIZE;
                int cellY = this.y + (i / COLUMNS) * CELL_SIZE;
                int index = start + i;

                if (index >= this.entries.size()) {
                    // 空槽位：只画内凹底
                    drawSlotBackground(guiGraphics, theme, cellX, cellY);
                    continue;
                }

                drawCell(guiGraphics, theme, cellX, cellY, this.hoverProgress.getOrDefault(index, 0.0F));
                StorageSnapshotS2C.Entry entry = this.entries.get(index);
                ItemStack stack = toDisplayStack(entry.key());
                guiGraphics.renderItem(stack, cellX + 1, cellY + 1);
                guiGraphics.renderItemDecorations(font, stack, cellX + 1, cellY + 1, formatAmount(entry.amount()));
            }
            guiGraphics.disableScissor();
            renderSelection(guiGraphics, theme);
        }

        renderScrollbar(guiGraphics, theme, mouseX, mouseY);
    }

    /** 画一个不含悬停效果的空槽位（玩家背包也复用）。 */
    public static void drawSlotBackground(GuiGraphics guiGraphics, GuiTheme theme, int cellX, int cellY) {
        guiGraphics.fill(cellX, cellY, cellX + CELL_SIZE, cellY + CELL_SIZE, theme.slotBg());
        guiGraphics.fill(cellX, cellY, cellX + CELL_SIZE, cellY + 1, theme.slotBorderDark());
        guiGraphics.fill(cellX, cellY, cellX + 1, cellY + CELL_SIZE, theme.slotBorderDark());
        guiGraphics.fill(cellX, cellY + CELL_SIZE - 1, cellX + CELL_SIZE, cellY + CELL_SIZE, theme.slotBorderLight());
        guiGraphics.fill(cellX + CELL_SIZE - 1, cellY, cellX + CELL_SIZE, cellY + CELL_SIZE, theme.slotBorderLight());
    }

    /** 把当前悬停的条目转换为显示用 ItemStack；没悬停时为 null。 */
    @Nullable
    public StorageSnapshotS2C.Entry hoveredEntry(double mouseX, double mouseY) {
        int index = indexAt(mouseX, mouseY);
        return index >= 0 ? this.entries.get(index) : null;
    }

    /** 滚轮：只在网格 / 滚动条上响应。 */
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (!isOverGrid(mouseX, mouseY) && !isOverScrollbar(mouseX, mouseY)) {
            return false;
        }
        int maxScroll = maxScrollRow();
        if (maxScroll <= 0) {
            return true;
        }
        int delta = scrollY > 0 ? -1 : 1;
        this.scrollRow = Mth.clamp(this.scrollRow + delta, 0, maxScroll);
        return true;
    }

    /** 左键：滚动条拖拽 / 选中格子。 */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        if (isOverScrollbar(mouseX, mouseY)) {
            this.draggingScrollbar = true;
            scrollToMouse(mouseY);
            return true;
        }
        int index = indexAt(mouseX, mouseY);
        if (index >= 0) {
            this.selectedKey = this.entries.get(index).key();
            return true;
        }
        if (isOverGrid(mouseX, mouseY)) {
            this.selectedKey = null;
            return true;
        }
        return false;
    }

    /** 拖拽滚动条。 */
    public boolean mouseDragged(double mouseY) {
        if (!this.draggingScrollbar) {
            return false;
        }
        scrollToMouse(mouseY);
        return true;
    }

    /** 结束滚动条拖拽。 */
    public boolean mouseReleased() {
        boolean wasDragging = this.draggingScrollbar;
        this.draggingScrollbar = false;
        return wasDragging;
    }

    // ------------------------------------------------------------------
    // 内部绘制
    // ------------------------------------------------------------------

    private static void drawCell(GuiGraphics guiGraphics, GuiTheme theme, int cellX, int cellY, float hover) {
        guiGraphics.fill(cellX, cellY, cellX + CELL_SIZE, cellY + CELL_SIZE,
                lerpColor(theme.slotBg(), theme.hoverBg(), hover));

        int edgeDark = lerpColor(theme.slotBorderDark(), theme.slotBorderHover(), hover);
        int edgeLight = lerpColor(theme.slotBorderLight(), theme.slotBorderHover(), hover);

        // 上 / 左 1px 暗边
        guiGraphics.fill(cellX, cellY, cellX + CELL_SIZE, cellY + 1, edgeDark);
        guiGraphics.fill(cellX, cellY, cellX + 1, cellY + CELL_SIZE, edgeDark);
        // 下 / 右 1px 亮边
        guiGraphics.fill(cellX, cellY + CELL_SIZE - 1, cellX + CELL_SIZE, cellY + CELL_SIZE, edgeLight);
        guiGraphics.fill(cellX + CELL_SIZE - 1, cellY, cellX + CELL_SIZE, cellY + CELL_SIZE, edgeLight);
    }

    private void renderSelection(GuiGraphics guiGraphics, GuiTheme theme) {
        if (this.selectedKey == null) {
            return;
        }
        int index = indexOfSelected();
        if (index < 0) {
            return;
        }
        int visible = index - this.scrollRow * COLUMNS;
        if (visible < 0 || visible >= this.rows * COLUMNS) {
            return;
        }

        int cellX = this.x + (visible % COLUMNS) * CELL_SIZE;
        int cellY = this.y + (visible / COLUMNS) * CELL_SIZE;

        // 外发光：2px 半透明强调色
        guiGraphics.fill(cellX - 2, cellY - 2, cellX + CELL_SIZE + 2, cellY, theme.selectedGlow());
        guiGraphics.fill(cellX - 2, cellY + CELL_SIZE, cellX + CELL_SIZE + 2, cellY + CELL_SIZE + 2,
                theme.selectedGlow());
        guiGraphics.fill(cellX - 2, cellY, cellX, cellY + CELL_SIZE, theme.selectedGlow());
        guiGraphics.fill(cellX + CELL_SIZE, cellY, cellX + CELL_SIZE + 2, cellY + CELL_SIZE,
                theme.selectedGlow());

        // 实线边框
        guiGraphics.fill(cellX, cellY, cellX + CELL_SIZE, cellY + 1, theme.selectedBorder());
        guiGraphics.fill(cellX, cellY + CELL_SIZE - 1, cellX + CELL_SIZE, cellY + CELL_SIZE,
                theme.selectedBorder());
        guiGraphics.fill(cellX, cellY, cellX + 1, cellY + CELL_SIZE, theme.selectedBorder());
        guiGraphics.fill(cellX + CELL_SIZE - 1, cellY, cellX + CELL_SIZE, cellY + CELL_SIZE,
                theme.selectedBorder());
    }

    private void renderScrollbar(GuiGraphics guiGraphics, GuiTheme theme, int mouseX, int mouseY) {
        int trackX = scrollbarX();
        int trackY = this.y;
        int trackHeight = height();
        guiGraphics.fill(trackX, trackY, trackX + SCROLLBAR_WIDTH, trackY + trackHeight,
                theme.slotBorderDark());

        int maxScroll = maxScrollRow();
        if (maxScroll <= 0) {
            return;
        }

        int thumbHeight = thumbHeight();
        int thumbY = thumbY();
        boolean active = this.draggingScrollbar || isOverScrollbar(mouseX, mouseY);
        guiGraphics.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbHeight,
                active ? theme.accent() : theme.textDim());
    }

    // ------------------------------------------------------------------
    // 交互 / 几何
    // ------------------------------------------------------------------

    private void updateHover(int hoveredIndex, float step) {
        if (hoveredIndex >= 0) {
            this.hoverProgress.putIfAbsent(hoveredIndex, 0.0F);
        }
        var iterator = this.hoverProgress.entrySet().iterator();
        while (iterator.hasNext()) {
            var progress = iterator.next();
            float target = progress.getKey() == hoveredIndex ? 1.0F : 0.0F;
            float next = Mth.approach(progress.getValue(), target, step);
            if (next <= 0.0F && target <= 0.0F) {
                iterator.remove();
            } else {
                progress.setValue(next);
            }
        }
    }

    private int indexAt(double mouseX, double mouseY) {
        if (!isOverGrid(mouseX, mouseY)) {
            return -1;
        }
        int column = (int) ((mouseX - this.x) / CELL_SIZE);
        int row = (int) ((mouseY - this.y) / CELL_SIZE);
        int index = (this.scrollRow + row) * COLUMNS + column;
        return index < this.entries.size() ? index : -1;
    }

    private boolean isOverGrid(double mouseX, double mouseY) {
        return mouseX >= this.x && mouseX < this.x + width()
                && mouseY >= this.y && mouseY < this.y + height();
    }

    private int scrollbarX() {
        return this.x + width() + 3;
    }

    private boolean isOverScrollbar(double mouseX, double mouseY) {
        int trackX = scrollbarX();
        return mouseX >= trackX - 2 && mouseX < trackX + SCROLLBAR_WIDTH + 2
                && mouseY >= this.y && mouseY < this.y + height();
    }

    private int maxScrollRow() {
        int usedRows = (this.entries.size() + COLUMNS - 1) / COLUMNS;
        return Math.max(0, usedRows - this.rows);
    }

    private int thumbHeight() {
        int maxScroll = maxScrollRow();
        int trackHeight = height();
        if (maxScroll <= 0) {
            return trackHeight;
        }
        int usedRows = maxScroll + this.rows;
        return Math.max(12, trackHeight * this.rows / usedRows);
    }

    private int thumbY() {
        int maxScroll = maxScrollRow();
        int trackHeight = height();
        int thumbHeight = thumbHeight();
        int travel = trackHeight - thumbHeight;
        if (maxScroll <= 0 || travel <= 0) {
            return this.y;
        }
        return this.y + travel * this.scrollRow / maxScroll;
    }

    private void scrollToMouse(double mouseY) {
        int maxScroll = maxScrollRow();
        if (maxScroll <= 0) {
            this.scrollRow = 0;
            return;
        }
        int thumbHeight = thumbHeight();
        int travel = height() - thumbHeight;
        if (travel <= 0) {
            this.scrollRow = 0;
            return;
        }
        double ratio = (mouseY - this.y - thumbHeight / 2.0) / travel;
        this.scrollRow = Mth.clamp((int) Math.round(ratio * maxScroll), 0, maxScroll);
    }

    private int indexOfSelected() {
        for (int i = 0; i < this.entries.size(); i++) {
            if (this.entries.get(i).key().equals(this.selectedKey)) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 库存 key → 显示用 ItemStack。 */
    public static ItemStack toDisplayStack(AEKey key) {
        return key instanceof AEItemKey itemKey ? itemKey.toStack(1) : key.wrapForDisplayOrFilter();
    }

    /** 数量简写：<1000 原样；1000–9999 千分位；再大用 k / M / B。 */
    public static String formatAmount(long amount) {
        if (amount < 1_000L) {
            return Long.toString(amount);
        }
        if (amount < 10_000L) {
            return String.format(Locale.ROOT, "%,d", amount);
        }
        if (amount < 1_000_000L) {
            return compact(amount / 1_000.0) + "k";
        }
        if (amount < 1_000_000_000L) {
            return compact(amount / 1_000_000.0) + "M";
        }
        return compact(amount / 1_000_000_000.0) + "B";
    }

    private static String compact(double value) {
        String text = String.format(Locale.ROOT, "%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    private static int lerpColor(int from, int to, float delta) {
        // 用 255 而不是十六进制掩码，保证颜色字面量只出现在 GuiTheme
        int a = Mth.lerpInt(delta, from >>> 24 & 255, to >>> 24 & 255);
        int r = Mth.lerpInt(delta, from >> 16 & 255, to >> 16 & 255);
        int g = Mth.lerpInt(delta, from >> 8 & 255, to >> 8 & 255);
        int b = Mth.lerpInt(delta, from & 255, to & 255);
        return a << 24 | r << 16 | g << 8 | b;
    }
}
