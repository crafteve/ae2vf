package io.github.crafteve.idlefactory.client.screen;

import java.util.List;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 顶栏与右区静态底板：标题、工厂状态、统计、关闭、tab、搜索框和排序按钮。
 *
 * <p>只画静态 chrome 与搜索/排序控件，不画仓库物品、机器卡片或 tooltip；这些动态内容由
 * {@link FactoryRightContentRender} / {@link FactoryTooltipRender} 负责。所有坐标从
 * {@link FactoryLayout} 和 {@link FactoryScreenGeometry} 读，避免和命中各算一份。</p>
 */
final class FactoryChromeRender {
    /** 顶栏关闭按钮的边长。 */
    static final int CLOSE_SIZE = 14;
    /** 标题文字与后面状态文字之间的水平间距。 */
    static final int TITLE_GAP = 10;

    private final FactoryScreenContext context;

    /**
     * @param context 界面只读视图
     */
    FactoryChromeRender(FactoryScreenContext context) {
        this.context = context;
    }

    /**
     * 右区的静态底板：tab、搜索框、排序按钮、仓库/合成网格和结果槽。
     *
     * @param graphics 画布
     */
    void drawRightChrome(GuiGraphics graphics) {
        FactoryLayout l = this.context.layout();
        FactoryScreenState state = this.context.state();

        // tab 条占据顶栏右端的完整宽度；最后一个 tab 吸收除不尽的多余像素。
        int tabWidth = l.rightW / FactoryLayout.TAB_COUNT;
        for (int i = 0; i < FactoryLayout.TAB_COUNT; i++) {
            int x = l.rightX + i * tabWidth;
            int w = i == FactoryLayout.TAB_COUNT - 1 ? l.rightW - i * tabWidth : tabWidth;
            FactoryNineSlice.button(graphics, x, l.tabY, w, FactoryLayout.HEADER_H);
        }
        // 选中 tab 用一条 2px 深色上沿强调；宽度跟随当前 tab 的实际宽度。
        int activeWidth = state.activeTab == FactoryLayout.TAB_COUNT - 1
                ? l.rightW - state.activeTab * tabWidth
                : tabWidth;
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.DIVIDER_DARK,
                l.rightX + state.activeTab * tabWidth, l.tabY, activeWidth, 2);

        drawSearchField(graphics);
        FactoryNineSlice.icon(graphics, FactoryGuiTextures.SEARCH, l.rightX + 9, l.searchY + 2,
                FactoryGuiTextures.SEARCH_SIZE);
        FactorySearchEditBox searchBox = this.context.searchBox();
        if (searchBox != null && !searchBox.getValue().isEmpty()) {
            FactoryNineSlice.icon(graphics, FactoryGuiTextures.SEARCH_CLEAR,
                    this.context.geometry().clearButtonX(), l.searchY + 4,
                    FactoryGuiTextures.SEARCH_CLEAR_SIZE);
        }
        drawSortButtons(graphics);
        if (state.activeTab == FactoryScreen.TAB_STORAGE) {
            drawGrid(graphics, l.gridX, l.gridY, FactoryLayout.GRID_COLS, l.gridRows,
                    FactoryLayout.CELL);
        }

        // 合成占位目前还是 P0 视觉：两个 tab 都保留，避免切 tab 时布局跳动。
        drawGrid(graphics, l.craftX, l.craftY, FactoryLayout.CRAFT, FactoryLayout.CRAFT,
                FactoryLayout.CRAFT_CELL);

        // 3×3 与结果格之间的箭头：一条细横线和右侧结果凹槽。
        int arrowY = l.resultY + FactoryLayout.CRAFT_RESULT / 2;
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.DIVIDER_DARK,
                l.craftX + FactoryLayout.CRAFT * FactoryLayout.CRAFT_CELL + 4, arrowY,
                FactoryLayout.CRAFT_ARROW - 8, 1);

        FactoryNineSlice.slot(graphics, l.resultX, l.resultY,
                FactoryLayout.CRAFT_RESULT, FactoryLayout.CRAFT_RESULT);
    }

    /**
     * 顶栏：标题、工厂状态、统计、关闭按钮。
     *
     * @param graphics 画布
     */
    void drawHeader(GuiGraphics graphics) {
        FactoryLayout l = this.context.layout();
        FactoryScreenState state = this.context.state();
        int x = l.headerX + 6;
        int textY = l.headerY + (FactoryLayout.HEADER_H - 9) / 2;

        String title = "放置工厂";
        graphics.drawString(this.context.font(), FactoryLabels.coloredBold(title, 0xFF000000),
                x, textY, 0xFFFFFFFF, false);
        x += this.context.font().width(title) + TITLE_GAP;

        String status;
        int statusColor;
        if (!state.hasFactory) {
            status = "未创建";
            statusColor = this.context.theme().statusIdle();
        } else {
            // P1 删除产线后没有「暂停」概念：有工厂就跑，没规则才是空闲。
            status = state.machines.isEmpty() ? "没有机器" : "运行中";
            statusColor = state.machines.isEmpty()
                    ? this.context.theme().statusIdle()
                    : this.context.theme().statusRun();
        }
        graphics.drawString(this.context.font(), status, x + 4, textY, statusColor, false);
        int statusEnd = x + 4 + this.context.font().width(status);

        int storageColor = storageStatusColor();
        List<FactoryUiText.TextPart> stats = List.of(
                new FactoryUiText.TextPart(Integer.toString(state.machines.size()),
                        this.context.theme().textValue()),
                new FactoryUiText.TextPart(" 台", this.context.theme().textPrimary()),
                new FactoryUiText.TextPart(" · ", this.context.theme().textPrimary()),
                new FactoryUiText.TextPart("仓库", storageColor),
                new FactoryUiText.TextPart(storageStatusText(), storageColor));
        int closeX = l.rightX - 6 - CLOSE_SIZE;
        int statsX = closeX - 8 - FactoryUiText.partsWidth(this.context.font(), stats);
        if (statsX > statusEnd + 8) {
            FactoryUiText.drawParts(graphics, this.context.font(), stats, statsX, textY);
        }

        FactoryNineSlice.button(graphics, closeX, l.headerY + 4, CLOSE_SIZE, CLOSE_SIZE);
        graphics.drawString(this.context.font(), "x",
                closeX + (CLOSE_SIZE - this.context.font().width("x")) / 2, textY,
                this.context.theme().textPrimary(), false);
    }

    /**
     * 顶栏统计里的仓库状态。
     *
     * @return 状态文字
     */
    private String storageStatusText() {
        FactoryScreenState state = this.context.state();
        if (!state.hasFactory) {
            return "未创建";
        }
        if (!state.storageReceived) {
            return "同步中";
        }
        return state.storageOnline ? "在线" : "离线";
    }

    /**
     * 顶栏仓库状态颜色。
     *
     * @return 主题颜色
     */
    private int storageStatusColor() {
        FactoryScreenState state = this.context.state();
        if (!state.hasFactory || !state.storageReceived) {
            return this.context.theme().statusIdle();
        }
        return state.storageOnline ? this.context.theme().statusRun()
                : this.context.theme().error();
    }

    /**
     * 搜索框底板：纯色填充 + 1px 外框 + 1px 内凹阴影。
     *
     * @param graphics 画布
     */
    private void drawSearchField(GuiGraphics graphics) {
        FactoryLayout l = this.context.layout();
        int x = l.rightX + 4;
        int y = l.searchY;
        int w = l.searchWidth();
        int h = FactoryLayout.SEARCH_H;

        FactoryNineSlice.fill(graphics, FactoryGuiTextures.SEARCH_BG, x + 2, y + 2, w - 4, h - 4);

        FactoryNineSlice.fill(graphics, FactoryGuiTextures.SEARCH_BORDER, x, y, w, 1);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.SEARCH_BORDER, x, y + h - 1, w, 1);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.SEARCH_BORDER, x, y, 1, h);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.SEARCH_BORDER, x + w - 1, y, 1, h);

        FactoryNineSlice.fill(graphics, FactoryGuiTextures.SEARCH_SHADOW, x + 1, y + 1, w - 2, 1);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.SEARCH_SHADOW, x + 1, y + 1, 1, h - 2);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.SEARCH_HIGHLIGHT, x + 1, y + h - 2,
                w - 2, 1);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.SEARCH_HIGHLIGHT, x + w - 2, y + 1,
                1, h - 2);
    }

    /**
     * 搜索行右侧的三个排序按钮：键、方向、范围。
     *
     * @param graphics 画布
     */
    private void drawSortButtons(GuiGraphics graphics) {
        FactoryLayout l = this.context.layout();
        FactorySortButtons buttons = this.context.sortButtons();
        if (buttons == null) {
            return;
        }
        boolean storageTab = this.context.state().activeTab == FactoryScreen.TAB_STORAGE;
        int hovered = this.context.geometry().sortButtonAt(
                this.context.mouseX(), this.context.mouseY());
        for (int index = 0; index < FactoryLayout.SORT_BTN_COUNT; index++) {
            int x = l.sortButtonX(index);
            int y = l.searchY;
            int size = FactoryLayout.SORT_BTN_SIZE;
            FactoryNineSlice.sortButton(graphics, x, y, size, size);
            FactoryNineSlice.icon(graphics, buttons.icon(index, storageTab), x + 1, y + 1,
                    FactoryGuiTextures.SORT_ICON_SIZE);
            if (hovered == index) {
                graphics.fill(x, y, x + size, y + size, 0x22FFFFFF);
            }
            if (!buttons.usable(index, storageTab)) {
                graphics.fill(x + 1, y + 1, x + size - 1, y + size - 1, 0x66000000);
            }
        }
    }

    /**
     * 铺一片固定大小的凹槽网格。
     *
     * @param graphics 画布
     * @param x        网格左边缘
     * @param y        网格上边缘
     * @param columns  列数
     * @param rows     行数
     * @param cell     单格边长
     */
    static void drawGrid(GuiGraphics graphics, int x, int y, int columns, int rows, int cell) {
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                FactoryNineSlice.slot(graphics, x + col * cell, y + row * cell, cell, cell);
            }
        }
    }
}
