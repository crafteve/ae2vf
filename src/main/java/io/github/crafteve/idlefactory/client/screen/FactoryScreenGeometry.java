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
     * @param machine 机器
     * @param side    输入/输出
     */
    record IoDropTarget(FactorySnapshotBuilder.MachineView machine, IoSide side) {
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
     * @param machine      机器视图
     * @param y            行顶部 y
     * @param height       该行当前实际高度（基础行 + 展开区）
     * @param detailHeight 展开区高度；0 表示未展开
     */
    record TableHit(FactorySnapshotBuilder.MachineView machine, int y, int height,
            int detailHeight) {
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
        List<FactorySnapshotBuilder.MachineView> table = this.screen.state.tableMachines();
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
     * 鼠标下的机器库卡片（机器 tab）。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 机器视图；没命中为 {@code null}
     */
    @Nullable
    FactorySnapshotBuilder.MachineView machineAt(double mouseX, double mouseY) {
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
                || index < 0 || index >= this.screen.state.filteredMachines.size()) {
            return null;
        }
        return this.screen.state.filteredMachines.get(index);
    }

    /**
     * 鼠标下的“移出产线”按钮。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 机器视图；没命中为 {@code null}
     */
    @Nullable
    FactorySnapshotBuilder.MachineView lineRemoveButtonAt(double mouseX, double mouseY) {
        if (this.screen.state.activeTab != FactoryScreen.TAB_MACHINES) {
            return null;
        }
        TableHit hit = tableHitAt(mouseY);
        if (hit == null || mouseY >= hit.y() + FactoryLayout.ROW_H) {
            return null;
        }
        int x = lineRemoveButtonX();
        return inside(mouseX, mouseY, x, hit.y() + 2, 12, FactoryLayout.ROW_H - 4)
                ? hit.machine() : null;
    }

    /**
     * 左表机器行「移出产线」叉号的 x。
     *
     * @return 叉号左边缘
     */
    int lineRemoveButtonX() {
        return expandButtonX() - 16;
    }

    /**
     * 鼠标下的机器库删除按钮。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 机器视图；没命中为 {@code null}
     */
    @Nullable
    FactorySnapshotBuilder.MachineView libraryDeleteButtonAt(double mouseX, double mouseY) {
        FactorySnapshotBuilder.MachineView machine = machineAt(mouseX, mouseY);
        if (machine == null) {
            return null;
        }
        int row = (int) ((mouseY - this.screen.layout.gridY) / FactoryLayout.ROW_H);
        int cardX = this.screen.layout.gridX + 2;
        int cardW = FactoryLayout.GRID_COLS * FactoryLayout.CELL - 4;
        int cardY = this.screen.layout.gridY + row * FactoryLayout.ROW_H + 1;
        int x = cardX + cardW - 12;
        int y = cardY + 13 - 1;
        return inside(mouseX, mouseY, x, y, 10, 10) ? machine : null;
    }

    /**
     * 鼠标下的左表机器行（机器列），用作“拖回机器库”的源。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 机器视图；没命中为 {@code null}
     */
    @Nullable
    FactorySnapshotBuilder.MachineView machineIconAt(double mouseX, double mouseY) {
        if (this.screen.state.selectedLineId == null) {
            return null;
        }
        TableHit hit = tableHitAt(mouseY);
        if (hit == null || mouseY >= hit.y() + FactoryLayout.ROW_H) {
            return null;
        }
        int x = this.screen.layout.columnX(FactoryLayout.COL_NAME) + 2;
        int width = this.screen.layout.columnW(FactoryLayout.COL_NAME) - 4;
        return inside(mouseX, mouseY, x, hit.y() + 2,
                width, FactoryLayout.ROW_H - 4) ? hit.machine() : null;
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
        TableHit hit = tableHitAt(mouseY);
        if (hit == null) {
            return null;
        }
        FactorySnapshotBuilder.MachineView machine = hit.machine();
        if (hit.detailHeight() > 0 && mouseY >= hit.y() + FactoryLayout.ROW_H) {
            FactoryIoDetailLayout.Card card = detailCardAt(hit, mouseX, mouseY);
            if (card != null && card.empty()) {
                return new IoDropTarget(machine, card.input() ? IoSide.INPUT : IoSide.OUTPUT);
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
                    machine.inputs(), machine.inputRates(),
                    l.columnX(FactoryLayout.COL_INPUT) + 2,
                    hit.y() + 4, l.columnW(FactoryLayout.COL_INPUT) - 4, true);
            if (FactoryRuleStrip.hitAdd(strip, mouseX, mouseY)) {
                return new IoDropTarget(machine, IoSide.INPUT);
            }
        }
        if (l.hasColumn(FactoryLayout.COL_OUTPUT)
                && inColumn(l, FactoryLayout.COL_OUTPUT, mouseX)) {
            FactoryRuleStrip.Layout strip = FactoryRuleStrip.layout(this.screen.state.font,
                    machine.outputs(), machine.outputRates(),
                    l.columnX(FactoryLayout.COL_OUTPUT) + 2, hit.y() + 4,
                    l.columnW(FactoryLayout.COL_OUTPUT) - 4
                            - FactoryLayout.EXPAND_BUTTON - 2, true);
            if (FactoryRuleStrip.hitAdd(strip, mouseX, mouseY)) {
                return new IoDropTarget(machine, IoSide.OUTPUT);
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
     * 该机器在当前行位置上的展开高度。
     *
     * @param machine 机器视图
     * @param rowY    该行当前顶部
     * @return 像素高度；未展开为 0
     */
    int detailHeight(FactorySnapshotBuilder.MachineView machine, int rowY) {
        if (!this.screen.state.expandedMachines.contains(machine.ref())) {
            return 0;
        }
        int required = FactoryIoDetailLayout.requiredHeight(
                this.screen.layout, machine, this.screen.state.font);
        int available = Math.max(0,
                this.screen.layout.footerY - rowY - FactoryLayout.ROW_H);
        return Math.min(required, available);
    }

    /**
     * 该机器是否处于展开状态。
     *
     * @param machine 机器视图
     * @return 已展开为 {@code true}
     */
    boolean isOpen(FactorySnapshotBuilder.MachineView machine) {
        return this.screen.state.expandedMachines.contains(machine.ref());
    }

    /**
     * 行尾展开按钮的 x。
     *
     * @return 按钮左边缘
     */
    int expandButtonX() {
        FactoryLayout l = this.screen.layout;
        return l.leftX + l.leftW - FactoryLayout.EXPAND_BUTTON - 4;
    }

    /**
     * 按鼠标 y 找到可见机器行。
     *
     * @param mouseY 屏幕 y
     * @return 命中行；空白区或超出表格时为 {@code null}
     */
    @Nullable
    TableHit tableHitAt(double mouseY) {
        FactoryLayout l = this.screen.layout;
        if (mouseY < l.rowTop || mouseY >= l.footerY) {
            return null;
        }
        List<FactorySnapshotBuilder.MachineView> table = this.screen.state.tableMachines();
        int y = l.rowTop;
        for (int i = this.screen.state.firstRow; i < table.size(); i++) {
            if (y + FactoryLayout.ROW_H > l.footerY) {
                return null;
            }
            FactorySnapshotBuilder.MachineView machine = table.get(i);
            int detailHeight = detailHeight(machine, y);
            int height = FactoryLayout.ROW_H + detailHeight;
            if (mouseY >= y && mouseY < y + height) {
                return new TableHit(machine, y, height, detailHeight);
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
    boolean inExpandButton(TableHit hit, double mouseX, double mouseY) {
        return inside(mouseX, mouseY, expandButtonX(), hit.y() + 6,
                FactoryLayout.EXPAND_BUTTON, FactoryLayout.EXPAND_BUTTON);
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
    FactoryIoDetailLayout.Card detailCardAt(TableHit hit, double mouseX, double mouseY) {
        if (hit.detailHeight() <= 0) {
            return null;
        }
        FactoryIoDetailLayout.Detail detail = FactoryIoDetailLayout.layout(
                this.screen.layout, hit.machine(), hit.y() + FactoryLayout.ROW_H,
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
