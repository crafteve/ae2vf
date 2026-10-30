package io.github.crafteve.idlefactory.client.screen;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.factory.line.IoSide;

/**
 * 工厂界面的几何与命中。
 *
 * <p>左表行高可变，绘制、命中、JEI 必须共用同一套计算；所有坐标都从这里出，不把
 * {@code (mouseY - rowTop) / ROW_H} 一类的推导散到输入/渲染里。这里只读界面状态与
 * {@link FactoryLayout}，不画、不发包。</p>
 */
final class FactoryScreenGeometry {
    private final FactoryScreen screen;

    /**
     * @param screen 界面壳
     */
    FactoryScreenGeometry(FactoryScreen screen) {
        this.screen = screen;
    }

    /**
     * 一个 IO 空卡的放置目标。
     *
     * @param stack 机器组；规则写到整组
     * @param side  输入/输出
     */
    record IoDropTarget(FactoryMachineStack stack, IoSide side) {
    }

    /**
     * 顶栏产线按钮的 x。
     *
     * @return 按钮左边缘
     */
    int lineButtonX() {
        return this.screen.layout.headerX + 6
                + this.screen.state.font.width("放置工厂")
                + FactoryChromeRender.TITLE_GAP;
    }

    /**
     * 顶栏产线按钮的宽度。
     *
     * @return 按钮宽度
     */
    int lineButtonW() {
        return Math.max(24, Math.min(140,
                this.screen.state.font.width(this.screen.state.lineButtonText()) + 14));
    }

    /**
     * 搜索框清空按钮的 x。
     *
     * @return 清空按钮左边缘
     */
    int clearButtonX() {
        return this.screen.layout.rightX + 4 + this.screen.layout.searchWidth() - 14;
    }

    /**
     * 鼠标落在第几个排序按钮上。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 0 排序键、1 方向、2 范围；不在按钮内返回 -1
     */
    int sortButtonAt(double mouseX, double mouseY) {
        FactoryLayout l = this.screen.layout;
        for (int index = 0; index < FactoryLayout.SORT_BTN_COUNT; index++) {
            int x = l.sortButtonX(index);
            if (mouseX >= x && mouseX < x + FactoryLayout.SORT_BTN_SIZE
                    && mouseY >= l.searchY
                    && mouseY < l.searchY + FactoryLayout.SORT_BTN_SIZE) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 左表中一次命中。
     *
     * @param stack        命中的机器组
     * @param y            行顶部 y
     * @param height       该行当前实际高度（基础行 + 展开区）
     * @param detailHeight 展开区高度；0 表示未展开
     */
    record StackHit(FactoryMachineStack stack, int y, int height, int detailHeight) {

        /** 组内第一台机器，取名字/图标/规则展示都用它。 */
        FactorySnapshotBuilder.MachineView anchor() {
            return this.stack.anchor();
        }
    }

    /**
     * “新增机器”行的顶部 y；放不下时为 {@code null}。
     *
     * @return 行顶部 y
     */
    @Nullable
    Integer addMachineRowY() {
        if (this.screen.state.selectedLineId == null) {
            return null;
        }
        List<FactoryMachineStack> table = this.screen.state.tableStacks;
        int y = this.screen.layout.rowTop;
        for (int i = this.screen.state.firstRow; i < table.size(); i++) {
            if (y + FactoryLayout.ROW_H > this.screen.layout.footerY) {
                return null;
            }
            y += FactoryLayout.ROW_H + detailHeight(table.get(i), y);
        }
        return y + FactoryLayout.ROW_H <= this.screen.layout.footerY ? y : null;
    }

    /**
     * 鼠标是否在“新增机器”行上。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 命中为 {@code true}
     */
    boolean addMachineRowContains(double mouseX, double mouseY) {
        Integer y = addMachineRowY();
        return y != null
                && mouseY >= y && mouseY < y + FactoryLayout.ROW_H
                && mouseX >= this.screen.layout.leftX + 2
                && mouseX < this.screen.layout.leftX + this.screen.layout.leftW - 2;
    }

    /**
     * 鼠标下的机器库组行（机器 tab）。
     *
     * <p>命中的是卡片实际矩形，不是整行 26px：卡片在行内上下各留 1px、左右各留 2px，
     * 缝隙和边缘空白不该触发点击、拖拽或 tooltip。</p>
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 机器组；没命中为 {@code null}
     */
    @Nullable
    FactoryMachineStack libraryStackAt(double mouseX, double mouseY) {
        if (this.screen.state.activeTab != FactoryScreen.TAB_MACHINES) {
            return null;
        }
        FactoryLayout l = this.screen.layout;
        int gridW = FactoryLayout.GRID_COLS * FactoryLayout.CELL;
        int gridH = l.gridRows * FactoryLayout.CELL;
        if (mouseX < l.gridX || mouseX >= l.gridX + gridW
                || mouseY < l.gridY || mouseY >= l.gridY + gridH) {
            return null;
        }
        int row = (int) ((mouseY - l.gridY) / FactoryLayout.ROW_H);
        int index = this.screen.state.machineFirstRow + row;
        if (row >= this.screen.state.machineRowsVisible()
                || index < 0 || index >= this.screen.state.libraryStacks.size()) {
            return null;
        }
        // 和 FactoryMachineLibraryRender 的卡片矩形保持一致：cardX = gridX + 2、cardY = 行顶 + 1、
        // cardW = 宽 - 4、cardH = ROW_H - 3。命中缝隙时返回 null，避免空白处也弹 tooltip。
        int cardX = l.gridX + 2;
        int cardW = gridW - 4;
        int cardY = l.gridY + row * FactoryLayout.ROW_H + 1;
        int cardH = FactoryLayout.ROW_H - 3;
        if (mouseX < cardX || mouseX >= cardX + cardW
                || mouseY < cardY || mouseY >= cardY + cardH) {
            return null;
        }
        return this.screen.state.libraryStacks.get(index);
    }

    /**
     * 鼠标下的“移出产线”按钮。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 命中的机器组；没命中为 {@code null}
     */
    @Nullable
    FactoryMachineStack lineRemoveButtonAt(double mouseX, double mouseY) {
        if (this.screen.state.activeTab != FactoryScreen.TAB_MACHINES) {
            return null;
        }
        StackHit hit = tableHitAt(mouseY);
        if (hit == null || mouseY >= hit.y() + FactoryLayout.ROW_H) {
            return null;
        }
        int x = removeButtonX();
        return inside(mouseX, mouseY, x, hit.y() + FactoryLayout.ROW_ACTION_TOP,
                FactoryLayout.ROW_ACTION_BUTTON, FactoryLayout.ROW_ACTION_BUTTON)
                ? hit.stack() : null;
    }

    /**
     * 左表机器行「移出产线」按钮的 x。
     *
     * @return 按钮左边缘
     */
    int removeButtonX() {
        return expandButtonX() - FactoryLayout.ROW_ACTION_BUTTON - FactoryLayout.ROW_ACTION_GAP;
    }

    /**
     * 鼠标下的机器库删除按钮。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 命中的机器组；没命中为 {@code null}
     */
    @Nullable
    FactoryMachineStack libraryDeleteButtonAt(double mouseX, double mouseY) {
        FactoryMachineStack stack = libraryStackAt(mouseX, mouseY);
        if (stack == null) {
            return null;
        }
        int row = (int) ((mouseY - this.screen.layout.gridY) / FactoryLayout.ROW_H);
        int cardX = this.screen.layout.gridX + 2;
        int cardW = FactoryLayout.GRID_COLS * FactoryLayout.CELL - 4;
        int cardY = this.screen.layout.gridY + row * FactoryLayout.ROW_H + 1;
        int x = cardX + cardW - 12;
        int y = cardY + 13 - 1;
        return inside(mouseX, mouseY, x, y, 10, 10) ? stack : null;
    }

    /**
     * 鼠标下的左表机器行（机器列），用作“拖回机器库”的源。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 命中的机器组；没命中为 {@code null}
     */
    @Nullable
    FactoryMachineStack machineIconAt(double mouseX, double mouseY) {
        if (this.screen.state.selectedLineId == null) {
            return null;
        }
        StackHit hit = tableHitAt(mouseY);
        if (hit == null || mouseY >= hit.y() + FactoryLayout.ROW_H) {
            return null;
        }
        int x = this.screen.layout.columnX(FactoryLayout.COL_NAME) + 2;
        int width = this.screen.layout.columnW(FactoryLayout.COL_NAME) - 4;
        return inside(mouseX, mouseY, x, hit.y() + 2,
                width, FactoryLayout.ROW_H - 4) ? hit.stack() : null;
    }

    /**
     * 鼠标是否落在机器库网格上。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 命中为 {@code true}
     */
    boolean isInMachineLibrary(double mouseX, double mouseY) {
        if (this.screen.state.activeTab != FactoryScreen.TAB_MACHINES) {
            return false;
        }
        FactoryLayout l = this.screen.layout;
        return mouseX >= l.gridX
                && mouseX < l.gridX + FactoryLayout.GRID_COLS * FactoryLayout.CELL
                && mouseY >= l.gridY
                && mouseY < l.gridY + l.gridRows * FactoryLayout.CELL;
    }

    /**
     * 鼠标下的 IO 空卡/空槽。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 目标；没命中为 {@code null}
     */
    @Nullable
    IoDropTarget ioDropTargetAt(double mouseX, double mouseY) {
        StackHit hit = tableHitAt(mouseY);
        if (hit == null) {
            return null;
        }
        FactoryMachineStack stack = hit.stack();
        if (hit.detailHeight() > 0 && mouseY >= hit.y() + FactoryLayout.ROW_H) {
            FactoryIoDetailLayout.Card card = detailCardAt(hit, mouseX, mouseY);
            if (card != null && card.empty()) {
                return new IoDropTarget(stack, card.input() ? IoSide.INPUT : IoSide.OUTPUT);
            }
            return null;
        }
        if (mouseY >= hit.y() + FactoryLayout.ROW_H) {
            return null;
        }
        FactoryLayout l = this.screen.layout;
        if (l.hasColumn(FactoryLayout.COL_INPUT)
                && inColumn(l, FactoryLayout.COL_INPUT, mouseX)) {
            FactoryRuleStrip.Layout strip = FactoryRuleStrip.layout(this.screen.state.font,
                    stack.inputs(), stack.inputRates(),
                    l.columnX(FactoryLayout.COL_INPUT) + 2,
                    hit.y() + 4, l.columnW(FactoryLayout.COL_INPUT) - 4, true);
            if (FactoryRuleStrip.hitAdd(strip, mouseX, mouseY)) {
                return new IoDropTarget(stack, IoSide.INPUT);
            }
        }
        if (l.hasColumn(FactoryLayout.COL_OUTPUT)
                && inColumn(l, FactoryLayout.COL_OUTPUT, mouseX)) {
            FactoryRuleStrip.Layout strip = FactoryRuleStrip.layout(this.screen.state.font,
                    stack.outputs(), stack.outputRates(),
                    l.columnX(FactoryLayout.COL_OUTPUT) + 2, hit.y() + 4,
                    l.columnW(FactoryLayout.COL_OUTPUT) - 4 - FactoryLayout.ROW_ACTIONS_W, true);
            if (FactoryRuleStrip.hitAdd(strip, mouseX, mouseY)) {
                return new IoDropTarget(stack, IoSide.OUTPUT);
            }
        }
        return null;
    }

    /**
     * 鼠标是否在仓库网格上。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 命中为 {@code true}
     */
    boolean isInStorageGrid(double mouseX, double mouseY) {
        if (this.screen.state.activeTab != FactoryScreen.TAB_STORAGE) {
            return false;
        }
        FactoryLayout l = this.screen.layout;
        return mouseX >= l.gridX
                && mouseX < l.gridX + FactoryLayout.GRID_COLS * FactoryLayout.CELL
                && mouseY >= l.gridY
                && mouseY < l.gridY + l.gridRows * FactoryLayout.CELL;
    }

    /**
     * 鼠标下的左表图标槽位（18×18），只在基础行有效。
     *
     * <p>滚轮改台数只认这块区域，行体其余位置仍然翻行；这是为了避免「想翻行却改了数量」。</p>
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 命中的组；没命中为 {@code null}
     */
    @Nullable
    FactoryMachineStack stackIconAt(double mouseX, double mouseY) {
        StackHit hit = tableHitAt(mouseY);
        if (hit == null || mouseY >= hit.y() + FactoryLayout.ROW_H) {
            return null;
        }
        int x = this.screen.layout.columnX(FactoryLayout.COL_NAME) + 4;
        int y = hit.y() + 4;
        return inside(mouseX, mouseY, x, y, FactoryLayout.CELL, FactoryLayout.CELL)
                ? hit.stack() : null;
    }

    /**
     * 鼠标是否落在左表行体上（改台数的滚轮区域之外）。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 在左表内为 {@code true}
     */
    boolean inTableBody(double mouseX, double mouseY) {
        FactoryLayout l = this.screen.layout;
        return mouseX >= l.leftX && mouseX < l.rightX - FactoryLayout.GAP
                && mouseY >= l.rowTop && mouseY < l.footerY;
    }

    /**
     * 鼠标下的左表行（含展开区），拖拽合并时用作落点。
     *
     * @param mouseY 屏幕 y
     * @return 命中的组；不在行上为 {@code null}
     */
    @Nullable
    FactoryMachineStack stackRowAt(double mouseY) {
        StackHit hit = tableHitAt(mouseY);
        return hit == null ? null : hit.stack();
    }

    /**
     * 该组在当前行位置上的展开高度。
     *
     * @param stack 机器组
     * @param rowY  该行当前顶部
     * @return 像素高度；未展开为 0
     */
    int detailHeight(FactoryMachineStack stack, int rowY) {
        if (!this.screen.state.expandedStacks.contains(stack.anchorId())) {
            return 0;
        }
        int required = FactoryIoDetailLayout.requiredHeight(
                this.screen.layout, stack.anchor(), this.screen.state.font);
        int available = Math.max(0,
                this.screen.layout.footerY - rowY - FactoryLayout.ROW_H);
        return Math.min(required, available);
    }

    /**
     * 该组是否处于展开状态。
     *
     * @param stack 机器组
     * @return 已展开为 {@code true}
     */
    boolean isOpen(FactoryMachineStack stack) {
        return this.screen.state.expandedStacks.contains(stack.anchorId());
    }

    /**
     * 行尾展开按钮的 x。
     *
     * @return 按钮左边缘
     */
    int expandButtonX() {
        FactoryLayout l = this.screen.layout;
        return l.leftX + l.leftW - FactoryLayout.ROW_ACTION_BUTTON - 4;
    }

    /**
     * 按鼠标 y 找到可见机器行。
     *
     * @param mouseY 屏幕 y
     * @return 命中行；空白区或超出表格时为 {@code null}
     */
    @Nullable
    StackHit tableHitAt(double mouseY) {
        FactoryLayout l = this.screen.layout;
        if (mouseY < l.rowTop || mouseY >= l.footerY) {
            return null;
        }
        List<FactoryMachineStack> table = this.screen.state.tableStacks;
        int y = l.rowTop;
        for (int i = this.screen.state.firstRow; i < table.size(); i++) {
            if (y + FactoryLayout.ROW_H > l.footerY) {
                return null;
            }
            FactoryMachineStack stack = table.get(i);
            int detailHeight = detailHeight(stack, y);
            int height = FactoryLayout.ROW_H + detailHeight;
            if (mouseY >= y && mouseY < y + height) {
                return new StackHit(stack, y, height, detailHeight);
            }
            y += height;
        }
        return null;
    }

    /**
     * 鼠标是否落在某行的展开按钮上。
     *
     * @param hit    命中行
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 命中为 {@code true}
     */
    boolean inExpandButton(StackHit hit, double mouseX, double mouseY) {
        return inside(mouseX, mouseY, expandButtonX(), hit.y() + FactoryLayout.ROW_ACTION_TOP,
                FactoryLayout.ROW_ACTION_BUTTON, FactoryLayout.ROW_ACTION_BUTTON);
    }

    /**
     * 鼠标是否落在某行的「移出产线」按钮上。
     *
     * @param hit    命中行
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 命中为 {@code true}
     */
    boolean inRemoveButton(StackHit hit, double mouseX, double mouseY) {
        return inside(mouseX, mouseY, removeButtonX(), hit.y() + FactoryLayout.ROW_ACTION_TOP,
                FactoryLayout.ROW_ACTION_BUTTON, FactoryLayout.ROW_ACTION_BUTTON);
    }

    /**
     * 鼠标命中的展开区卡片。
     *
     * @param hit    命中行
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 卡片；没展开或没命中为 {@code null}
     */
    @Nullable
    FactoryIoDetailLayout.Card detailCardAt(StackHit hit, double mouseX, double mouseY) {
        if (hit.detailHeight() <= 0) {
            return null;
        }
        FactoryIoDetailLayout.Detail detail = FactoryIoDetailLayout.layout(
                this.screen.layout, hit.anchor(), hit.y() + FactoryLayout.ROW_H,
                hit.detailHeight(), this.screen.state.font, this.screen.state.ioScroll);
        return FactoryIoDetailLayout.cardAt(detail, mouseX, mouseY);
    }

    /**
     * 鼠标是否落在某个逻辑列的横向范围内。
     *
     * @param layout 布局
     * @param column 逻辑列下标
     * @param mouseX 屏幕 x
     * @return 命中为 {@code true}
     */
    boolean inColumn(FactoryLayout layout, int column, double mouseX) {
        return mouseX >= layout.columnX(column)
                && mouseX < layout.columnX(column) + layout.columnW(column);
    }

    /**
     * 鼠标是否落在给定矩形内；所有自定义格子的命中共用。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @param x      左上角 x
     * @param y      左上角 y
     * @param width  宽
     * @param height 高
     * @return 命中为 {@code true}
     */
    static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
