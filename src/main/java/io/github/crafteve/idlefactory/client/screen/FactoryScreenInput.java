package io.github.crafteve.idlefactory.client.screen;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.resource.MatchMode;
import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
import io.github.crafteve.idlefactory.factory.resource.ResourceKind;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;
import io.github.crafteve.idlefactory.network.payload.FactoryStorageActionC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryStorageActionC2S.Gesture;
import io.github.crafteve.idlefactory.network.payload.FactoryStorageS2C;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 工厂界面的输入层：鼠标点击/拖动/滚轮、键盘、仓库手势和 IO 卡片操作。
 *
 * <p>只负责把原版输入翻译成编辑控制器动作或 C2S 手势；未消费时返回 {@code false}，
 * 由 {@link FactoryScreen} 回落给原版。仓库格不能走原版 Slot 逻辑，否则会落到玩家背包槽位。</p>
 */
final class FactoryScreenInput {
    private final FactoryScreen screen;

    /**
     * @param screen 界面壳
     */
    FactoryScreenInput(FactoryScreen screen) {
        this.screen = screen;
    }

    /**
     * 当前鼠标位置对应的仓库条目。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 条目；空气格、非仓库 tab 或不在网格内时为 {@code null}
     */
    @Nullable
    FactoryStorageS2C.Entry storageEntryAt(double mouseX, double mouseY) {
        FactoryLayout l = this.screen.layout;
        FactoryScreenState state = this.screen.state;
        if (state.activeTab != FactoryScreen.TAB_STORAGE
                || mouseX < l.gridX
                || mouseX >= l.gridX + FactoryLayout.GRID_COLS * FactoryLayout.CELL
                || mouseY < l.gridY
                || mouseY >= l.gridY + l.gridRows * FactoryLayout.CELL) {
            return null;
        }
        int col = (int) ((mouseX - l.gridX) / FactoryLayout.CELL);
        int row = (int) ((mouseY - l.gridY) / FactoryLayout.CELL);
        int index = (state.storageFirstRow + row) * FactoryLayout.GRID_COLS + col;
        return index >= 0 && index < state.filteredStorageEntries.size()
                ? state.filteredStorageEntries.get(index)
                : null;
    }

    /**
     * 把一个仓库手势发给服务端。
     *
     * @param key     资源；空格为 {@code null}
     * @param gesture 手势
     */
    private void sendStorageGesture(@Nullable ResourceKey key, Gesture gesture) {
        PacketDistributor.sendToServer(new FactoryStorageActionC2S(
                this.screen.getMenu().containerId, key, gesture));
    }

    /**
     * 统一处理鼠标点击。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @param button 鼠标键
     * @return 消费为 {@code true}
     */
    boolean mouseClicked(double mouseX, double mouseY, int button) {
        FactoryLayout l = this.screen.layout;
        FactoryScreenState state = this.screen.state;
        FactorySearchEditBox searchBox = this.screen.searchBox();
        FactorySortButtons sortButtons = this.screen.sortButtons();
        FactoryEditController editController = this.screen.editController();
        if (searchBox != null && searchBox.isFocused()
                && !searchBox.isMouseOver(mouseX, mouseY)) {
            // 点击别处先退出搜索焦点，否则 R/U 会被 EditBox 当成输入吞掉。
            searchBox.setFocused(false);
        }
        boolean onLineButton = button == 0
                && mouseY >= l.headerY + 3
                && mouseY < l.headerY + 3 + FactoryLayout.TAB_H
                && mouseX >= this.screen.geometry().lineButtonX()
                && mouseX < this.screen.geometry().lineButtonX()
                        + this.screen.geometry().lineButtonW();
        boolean lineMenuWasOpen = editController != null
                && editController.isLineMenuOpen();
        int closeX = l.rightX - 6 - FactoryChromeRender.CLOSE_SIZE;
        boolean onCloseButton = mouseX >= closeX
                && mouseX < closeX + FactoryChromeRender.CLOSE_SIZE
                && mouseY >= l.headerY + 4
                && mouseY < l.headerY + 4 + FactoryChromeRender.CLOSE_SIZE;
        boolean onTabBar = isTabBar(mouseX, mouseY);
        int sortButton = this.screen.geometry().sortButtonAt(mouseX, mouseY);

        if (button == 0 && editController != null
                && !editController.isOverlayActive()) {
            var libraryDelete = this.screen.geometry()
                    .libraryDeleteButtonAt(mouseX, mouseY);
            if (libraryDelete != null) {
                editController.confirmDeleteStack(libraryDelete);
                return true;
            }
        }
        if (editController != null && editController.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (sortButton >= 0) {
            if (sortButtons != null && (button == 0 || button == 1)) {
                sortButtons.click(sortButton, state.activeTab == FactoryScreen.TAB_STORAGE,
                        button == 1);
            }
            return true;
        }
        if (onLineButton) {
            if (!lineMenuWasOpen && editController != null) {
                editController.toggleLineMenu(this.screen.geometry().lineButtonX(),
                        l.headerY + FactoryLayout.HEADER_H + 2);
            }
            return true;
        }
        if (onCloseButton) {
            this.screen.closeScreen();
            return true;
        }
        int tabWidth = l.rightW / FactoryLayout.TAB_COUNT;
        if (onTabBar) {
            int tab = Math.min(FactoryLayout.TAB_COUNT - 1,
                    (int) ((mouseX - l.rightX) / tabWidth));
            state.activeTab = tab;
            if (tab == FactoryScreen.TAB_STORAGE) {
                this.screen.requestStorage();
            }
            return true;
        }
        if (button == 0 && searchBox != null
                && !searchBox.getValue().isEmpty()
                && mouseX >= this.screen.geometry().clearButtonX() - 1
                && mouseX < this.screen.geometry().clearButtonX()
                        + FactoryGuiTextures.SEARCH_CLEAR_SIZE + 1
                && mouseY >= l.searchY && mouseY < l.searchY + FactoryLayout.SEARCH_H) {
            searchBox.setValue("");
            searchBox.setFocused(true);
            return true;
        }
        if (button == 0 && editController != null) {
            var removeTarget = this.screen.geometry().lineRemoveButtonAt(mouseX, mouseY);
            if (removeTarget != null) {
                editController.removeStackFromLine(removeTarget);
                return true;
            }
        }
        if (clickMachineExpand(mouseX, mouseY)) {
            return true;
        }
        if (handleFactoryRuleStripMouse(mouseX, mouseY, button)) {
            return true;
        }
        FactoryScreenGeometry.StackHit ioHit = this.screen.geometry().tableHitAt(mouseY);
        if (ioHit != null && ioHit.detailHeight() > 0
                && mouseY >= ioHit.y() + FactoryLayout.ROW_H) {
            FactoryIoDetailLayout.Card card = this.screen.geometry()
                    .detailCardAt(ioHit, mouseX, mouseY);
            if (card != null) {
                handleIoCardMouse(ioHit.stack(), card, mouseX, mouseY, button);
                return true;
            }
        }
        if (state.activeTab == FactoryScreen.TAB_STORAGE
                && mouseX >= l.gridX
                && mouseX < l.gridX + FactoryLayout.GRID_COLS * FactoryLayout.CELL
                && mouseY >= l.gridY
                && mouseY < l.gridY + l.gridRows * FactoryLayout.CELL) {
            // 仓库格不能走原版 Slot 逻辑：非物品资源、空格且手上为空时直接吞掉；
            // 其余情况只发手势，背包扣减与 carried 由服务端决定。
            FactoryStorageS2C.Entry entry = storageEntryAt(mouseX, mouseY);
            ResourceKey key = entry == null ? null : entry.key();
            if (button == 0 || button == 1) {
                if (key != null && key.kind() != ResourceKind.ITEM) {
                    return true;
                }
                if (key == null && this.screen.getMenu().getCarried().isEmpty()) {
                    return true;
                }
                Gesture gesture;
                if (Screen.hasShiftDown()) {
                    gesture = button == 0 ? Gesture.SHIFT_LEFT : Gesture.SHIFT_RIGHT;
                } else {
                    gesture = button == 0 ? Gesture.LEFT : Gesture.RIGHT;
                }
                sendStorageGesture(key, gesture);
                return true;
            }
            return true;
        }
        return false;
    }

    /**
     * 鼠标是否落在右区 tab 条上。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 在 tab 条内为 {@code true}
     */
    private boolean isTabBar(double mouseX, double mouseY) {
        FactoryLayout l = this.screen.layout;
        return mouseY >= l.tabY && mouseY < l.tabY + FactoryLayout.HEADER_H
                && mouseX >= l.rightX && mouseX < l.rightX + l.rightW;
    }

    /**
     * 点击行尾按钮时切换展开状态。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 命中并切换为 {@code true}
     */
    private boolean clickMachineExpand(double mouseX, double mouseY) {
        FactoryScreenGeometry.StackHit hit = this.screen.geometry().tableHitAt(mouseY);
        if (hit == null
                || !this.screen.geometry().inExpandButton(hit, mouseX, mouseY)) {
            return false;
        }
        UUID anchorId = hit.stack().anchorId();
        FactoryScreenState state = this.screen.state;
        if (state.expandedStacks.contains(anchorId)) {
            state.expandedStacks.remove(anchorId);
        } else {
            // 一次只展开一组：展开区很高，多开会把表格挤没。
            state.expandedStacks.clear();
            state.expandedStacks.add(anchorId);
            ensureExpansionVisible(anchorId);
        }
        state.ioScroll = 0;
        state.firstRow = Math.max(0, Math.min(state.firstRow, state.maxFirstRow()));
        return true;
    }

    /**
     * 展开行需要的高度超过当前剩余空间时，把它滚到表格顶部。
     *
     * @param anchorId 要保证可见的组锚点
     */
    private void ensureExpansionVisible(UUID anchorId) {
        FactoryLayout l = this.screen.layout;
        FactoryScreenState state = this.screen.state;
        var table = state.tableStacks;
        int index = -1;
        for (int i = 0; i < table.size(); i++) {
            if (table.get(i).anchorId().equals(anchorId)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return;
        }
        if (index < state.firstRow) {
            state.firstRow = index;
            return;
        }
        int y = l.rowTop;
        for (int i = state.firstRow; i < index; i++) {
            y += FactoryLayout.ROW_H + this.screen.geometry().detailHeight(table.get(i), y);
            if (y >= l.footerY) {
                state.firstRow = index;
                return;
            }
        }
        int required = FactoryIoDetailLayout.requiredHeight(
                l, table.get(index).anchor(), this.screen.state.font);
        int available = Math.max(0, l.footerY - y - FactoryLayout.ROW_H);
        if (required > available) {
            state.firstRow = index;
        }
    }

    /**
     * 滚轮落在展开 IO 面板上时，滚动卡片而不是表格。
     *
     * @param mouseX  屏幕 x
     * @param mouseY  屏幕 y
     * @param scrollY 滚轮量
     * @return 是否消费
     */
    private boolean ioScrollAt(double mouseX, double mouseY, double scrollY) {
        FactoryLayout l = this.screen.layout;
        FactoryScreenState state = this.screen.state;
        FactoryScreenGeometry.StackHit hit = this.screen.geometry().tableHitAt(mouseY);
        if (hit == null || hit.detailHeight() <= 0
                || mouseY < hit.y() + FactoryLayout.ROW_H) {
            return false;
        }
        if (mouseX < l.leftX || mouseX >= l.leftX + l.leftW) {
            return false;
        }
        FactoryIoDetailLayout.Detail detail = FactoryIoDetailLayout.layout(l, hit.anchor(),
                hit.y() + FactoryLayout.ROW_H, hit.detailHeight(),
                state.font, state.ioScroll);
        if (detail.maxScroll() <= 0) {
            return true;
        }
        int step = (int) Math.round(scrollY * FactoryLayout.IO_SCROLL_STEP);
        state.ioScroll = Math.max(0, Math.min(detail.maxScroll(), state.ioScroll - step));
        return true;
    }

    /**
     * 点击展开区卡片上的控件。
     *
     * <p>规则改动作用于整组：控制器只传锚点 id，成员由服务端现推。</p>
     *
     * @param stack   机器组
     * @param card    卡片
     * @param mouseX  屏幕 x
     * @param mouseY  屏幕 y
     * @param button  鼠标键
     * @return 是否消费
     */
    boolean handleIoCardMouse(FactoryMachineStack stack,
            FactoryIoDetailLayout.Card card, double mouseX, double mouseY, int button) {
        FactoryEditController editController = this.screen.editController();
        var side = card.input()
                ? io.github.crafteve.idlefactory.factory.io.IoSide.INPUT
                : io.github.crafteve.idlefactory.factory.io.IoSide.OUTPUT;
        if (card.empty() || editController == null) {
            return true;
        }
        var rules = card.input() ? stack.inputs() : stack.outputs();
        if (card.ruleIndex() >= rules.size()) {
            return true;
        }
        ResourceSpec spec = rules.get(card.ruleIndex());
        FactoryIoCardControls.Hit hit = FactoryIoCardControls.hit(card.x(), card.y(),
                card.width(), false, spec, mouseX, mouseY);
        switch (hit.kind()) {
            case FACE -> {
                if (button == 0 || button == 1) {
                    Direction next = FactoryIoCardControls.nextFace(spec.face(),
                            button == 0 ? 1 : -1);
                    editController.updateRule(stack, side, spec, next, spec.mode());
                }
            }
            case MATCH -> {
                if (button == 0) {
                    MatchMode next = spec.mode() == MatchMode.IGNORE_COMPONENTS
                            ? MatchMode.EXACT : MatchMode.IGNORE_COMPONENTS;
                    editController.updateRule(stack, side, spec, spec.face(), next);
                }
            }
            case BODY -> {
                if (button == 0) {
                    editController.beginRuleDrag(stack, side, spec, mouseX, mouseY);
                }
            }
            default -> {
                // 空卡/未命中：不动作。
            }
        }
        return true;
    }

    /**
     * 点击紧凑输入/输出列的规则图标：开始一次“拖回仓库删除”的拖拽。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @param button 鼠标键
     * @return 是否消费
     */
    boolean handleFactoryRuleStripMouse(double mouseX, double mouseY, int button) {
        FactoryEditController editController = this.screen.editController();
        if (button != 0 || editController == null) {
            return false;
        }
        FactoryLayout l = this.screen.layout;
        FactoryScreenGeometry.StackHit hit = this.screen.geometry().tableHitAt(mouseY);
        if (hit == null || mouseY >= hit.y() + FactoryLayout.ROW_H) {
            return false;
        }
        if (l.hasColumn(FactoryLayout.COL_INPUT)
                && this.screen.geometry().inColumn(l, FactoryLayout.COL_INPUT, mouseX)) {
            FactoryRuleStrip.Layout strip = FactoryRuleStrip.layout(this.screen.state.font,
                    hit.stack().inputs(), hit.stack().inputRates(),
                    l.columnX(FactoryLayout.COL_INPUT) + 2, hit.y() + 4,
                    l.columnW(FactoryLayout.COL_INPUT) - 4, true);
            FactoryRuleStrip.Entry entry = FactoryRuleStrip.hitRule(strip, mouseX, mouseY);
            if (entry != null && entry.ruleIndex() < hit.stack().inputs().size()) {
                editController.beginRuleDrag(hit.stack(),
                        io.github.crafteve.idlefactory.factory.io.IoSide.INPUT,
                        hit.stack().inputs().get(entry.ruleIndex()), mouseX, mouseY);
                return true;
            }
            return FactoryRuleStrip.hitAdd(strip, mouseX, mouseY);
        }
        if (l.hasColumn(FactoryLayout.COL_OUTPUT)
                && this.screen.geometry().inColumn(l, FactoryLayout.COL_OUTPUT, mouseX)) {
            FactoryRuleStrip.Layout strip = FactoryRuleStrip.layout(this.screen.state.font,
                    hit.stack().outputs(), hit.stack().outputRates(),
                    l.columnX(FactoryLayout.COL_OUTPUT) + 2, hit.y() + 4,
                    l.columnW(FactoryLayout.COL_OUTPUT) - 4 - FactoryLayout.ROW_ACTIONS_W, true);
            FactoryRuleStrip.Entry entry = FactoryRuleStrip.hitRule(strip, mouseX, mouseY);
            if (entry != null && entry.ruleIndex() < hit.stack().outputs().size()) {
                editController.beginRuleDrag(hit.stack(),
                        io.github.crafteve.idlefactory.factory.io.IoSide.OUTPUT,
                        hit.stack().outputs().get(entry.ruleIndex()), mouseX, mouseY);
                return true;
            }
            return FactoryRuleStrip.hitAdd(strip, mouseX, mouseY);
        }
        return false;
    }

    /**
     * 键盘按下：先给编辑浮层/搜索框。
     *
     * @param keyCode   GLFW 键码
     * @param scanCode  扫描码
     * @param modifiers 修饰键
     * @return 消费为 {@code true}
     */
    boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        FactoryEditController editController = this.screen.editController();
        if (editController != null && editController.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        FactorySearchEditBox searchBox = this.screen.searchBox();
        if (searchBox != null && searchBox.isFocused()) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                searchBox.setFocused(false);
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER) {
                searchBox.setFocused(false);
                return true;
            }
            if (searchBox.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
            return true;
        }
        return false;
    }

    /**
     * 搜索框为空时吞掉开头的空格。
     *
     * @param codePoint 字符
     * @param modifiers 修饰键
     * @return 消费为 {@code true}
     */
    boolean charTyped(char codePoint, int modifiers) {
        FactoryEditController editController = this.screen.editController();
        if (editController != null && editController.charTyped(codePoint, modifiers)) {
            return true;
        }
        FactorySearchEditBox searchBox = this.screen.searchBox();
        return searchBox != null && searchBox.isFocused()
                && codePoint == ' ' && searchBox.getValue().isEmpty();
    }

    /**
     * 鼠标拖动：优先交给编辑控制器。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @param button 鼠标键
     * @return 是否消费
     */
    boolean mouseDragged(double mouseX, double mouseY, int button) {
        FactoryEditController editController = this.screen.editController();
        return editController != null
                && editController.mouseDragged(mouseX, mouseY, button);
    }

    /**
     * 鼠标松开：优先交给编辑控制器结算拖放。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @param button 鼠标键
     * @return 是否消费
     */
    boolean mouseReleased(double mouseX, double mouseY, int button) {
        FactoryEditController editController = this.screen.editController();
        return editController != null
                && editController.mouseReleased(mouseX, mouseY, button);
    }

    /**
     * 滚轮：仓库格上的 Shift+滚轮是「移动一个」，其余位置是列表滚动。
     *
     * @param mouseX  屏幕 x
     * @param mouseY  屏幕 y
     * @param scrollY 垂直滚轮量
     * @return 消费为 {@code true}
     */
    boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        FactoryEditController editController = this.screen.editController();
        if (editController != null && editController.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        if (ioScrollAt(mouseX, mouseY, scrollY)) {
            return true;
        }
        // 左表图标槽位上滚轮改台数；其余区域仍翻行。
        FactoryMachineStack scrollStack = this.screen.geometry().stackIconAt(mouseX, mouseY);
        if (scrollStack != null && this.screen.selectedLineId() != null) {
            int step = scrollY > 0 ? 1 : -1;
            if (Screen.hasShiftDown()) {
                step *= 10;
            }
            this.screen.state.scrollStackCount(scrollStack, step);
            return true;
        }
        FactoryLayout l = this.screen.layout;
        FactoryScreenState state = this.screen.state;
        if (state.activeTab == FactoryScreen.TAB_STORAGE
                && Screen.hasShiftDown()
                && mouseX >= l.gridX
                && mouseX < l.gridX + FactoryLayout.GRID_COLS * FactoryLayout.CELL
                && mouseY >= l.gridY
                && mouseY < l.gridY + l.gridRows * FactoryLayout.CELL) {
            FactoryStorageS2C.Entry entry = storageEntryAt(mouseX, mouseY);
            if (entry != null && entry.key().kind() == ResourceKind.ITEM) {
                sendStorageGesture(entry.key(),
                        scrollY > 0 ? Gesture.SCROLL_UP : Gesture.SCROLL_DOWN);
                return true;
            }
        }
        if (mouseX >= l.leftX && mouseX < l.rightX - FactoryLayout.GAP
                && mouseY >= l.rowTop && mouseY < l.footerY) {
            int max = state.maxFirstRow();
            if (max > 0) {
                int step = scrollY > 0 ? -1 : 1;
                state.firstRow = Math.max(0, Math.min(max, state.firstRow + step));
                return true;
            }
        }
        if (mouseX >= l.gridX
                && mouseX < l.gridX + FactoryLayout.GRID_COLS * FactoryLayout.CELL
                && mouseY >= l.gridY
                && mouseY < l.gridY + l.gridRows * FactoryLayout.CELL) {
            int step = scrollY > 0 ? -1 : 1;
            if (state.activeTab == FactoryScreen.TAB_STORAGE) {
                int max = state.maxStorageFirstRow();
                if (max > 0) {
                    state.storageFirstRow = Math.max(0,
                            Math.min(max, state.storageFirstRow + step));
                    return true;
                }
            } else {
                int max = state.maxMachineFirstRow();
                if (max > 0) {
                    state.machineFirstRow = Math.max(0,
                            Math.min(max, state.machineFirstRow + step));
                    return true;
                }
            }
        }
        return false;
    }
}
