package io.github.crafteve.idlefactory.client.wand.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.AE2Button;

import io.github.crafteve.idlefactory.client.screen.FactoryGuiTheme;
import io.github.crafteve.idlefactory.client.wand.WandCoordinator;
import io.github.crafteve.idlefactory.client.wand.WandLinks;
import io.github.crafteve.idlefactory.client.wand.WandMessages;
import io.github.crafteve.idlefactory.client.wand.WandPrecheck;
import io.github.crafteve.idlefactory.client.wand.WandSession;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.wand.WandDraft;
import io.github.crafteve.idlefactory.factory.wand.WandIssue;
import io.github.crafteve.idlefactory.menu.wand.WandMenu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 草稿确认面板：AE2 对话框样式，逐行列出草稿并提交。
 *
 * <p>行来自客户端草稿，问题以服务端预校验为准（{@link WandCoordinator#pendingIssues()}），
 * 服务端还没回包时退回客户端预检结果。面板只是监视器：确定发提交意图，取消丢草稿，Esc /
 * 关闭保留草稿回到编辑态。</p>
 *
 * <p>不建任何槽位；标题与背包标签都按对话框惯例隐藏背包标签。</p>
 */
public final class WandDraftScreen extends AEBaseScreen<WandMenu> {
    /** 摘要行顶边。 */
    private static final int SUMMARY_Y = 19;
    /** 行区顶边。 */
    private static final int CONTENT_TOP = 32;
    /** 行区底边。 */
    private static final int CONTENT_BOTTOM = 150;
    /** 行区左边界。 */
    private static final int CONTENT_LEFT = 8;
    /** 行区右边界。 */
    private static final int CONTENT_RIGHT = 214;
    /** 状态行顶边。 */
    private static final int STATUS_Y = 153;

    private final List<Row> rows = new ArrayList<>();
    private int scroll;
    @Nullable
    private AE2Button confirm;
    @Nullable
    private AE2Button cancel;

    /**
     * @param menu      菜单
     * @param inventory 玩家物品栏
     * @param title     标题
     * @param style     AE2 对话框样式
     */
    public WandDraftScreen(WandMenu menu, Inventory inventory, Component title,
            ScreenStyle style) {
        super(menu, inventory, title, style);
        // 没有背包槽位：把原版的背包标签藏到屏幕外，免得压在草稿行上
        this.inventoryLabelY = -1000;
        this.confirm = this.widgets.addButton("start", WandMessages.panelConfirm(), this::confirm);
        this.cancel = this.widgets.addButton("cancel", WandMessages.panelCancel(), this::cancel);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        refreshRows();
        updateButtons();
        super.render(graphics, mouseX, mouseY, partialTick);
        drawPanel(graphics);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = Math.max(0, this.rows.size() * WandRowRender.ROW_HEIGHT
                - (CONTENT_BOTTOM - CONTENT_TOP));
        if (max > 0) {
            this.scroll = (int) Math.clamp(this.scroll - scrollY * WandRowRender.ROW_HEIGHT,
                    0, max);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /**
     * Esc：回编辑态并关闭面板，草稿保留。
     *
     * @param keyCode   键码
     * @param scanCode  扫描码
     * @param modifiers 修饰键
     * @return 是否已处理
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            this.minecraft.setScreen(null);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        WandCoordinator.clearPendingIssues();
        super.removed();
    }

    /**
     * 提交草稿；确定灰显时忽略。
     */
    private void confirm() {
        if (!canConfirm()) {
            return;
        }
        WandCoordinator.submit(this.menu.containerId);
    }

    /**
     * 取消：丢草稿并关闭面板。
     */
    private void cancel() {
        if (WandSession.state() == WandSession.State.SUBMITTING) {
            return;
        }
        WandSession.clear();
        this.onClose();
        this.minecraft.setScreen(null);
    }

    /**
     * 重建行列表：先待加入，再待删除。
     */
    private void refreshRows() {
        this.rows.clear();
        WandDraft draft = WandSession.draft();
        for (BlockKey block : draft.added()) {
            this.rows.add(new Row(block, false));
        }
        for (UUID machineId : draft.removed()) {
            Optional<BlockKey> block = WandLinks.blockOfMachine(machineId);
            block.ifPresent(value -> this.rows.add(new Row(value, true)));
        }
        int max = Math.max(0, this.rows.size() * WandRowRender.ROW_HEIGHT
                - (CONTENT_BOTTOM - CONTENT_TOP));
        this.scroll = Math.min(this.scroll, max);
    }

    /** 按提交态与校验结果更新两个按钮。 */
    private void updateButtons() {
        boolean submitting = WandSession.state() == WandSession.State.SUBMITTING;
        if (this.confirm != null) {
            this.confirm.active = canConfirm();
        }
        if (this.cancel != null) {
            this.cancel.active = !submitting;
        }
    }

    /**
     * 是否可以提交：编辑态、有行、没有校验问题。
     *
     * @return 可以提交时为 {@code true}
     */
    private boolean canConfirm() {
        return WandSession.state() == WandSession.State.EDITING
                && !this.rows.isEmpty()
                && issues().isEmpty();
    }

    /**
     * 画摘要、行、滚动条与状态行。
     *
     * @param graphics 绘制上下文
     */
    private void drawPanel(GuiGraphics graphics) {
        FactoryGuiTheme theme = FactoryGuiTheme.current();
        graphics.drawString(this.font, summary(), this.leftPos + CONTENT_LEFT,
                this.topPos + SUMMARY_Y, theme.textPrimary(), false);
        graphics.enableScissor(this.leftPos + CONTENT_LEFT, this.topPos + CONTENT_TOP,
                this.leftPos + CONTENT_RIGHT, this.topPos + CONTENT_BOTTOM);
        try {
            drawRows(graphics, theme);
        } finally {
            graphics.disableScissor();
        }
        if (this.rows.isEmpty()) {
            Component empty = WandMessages.panelEmpty();
            graphics.drawString(this.font, empty,
                    this.leftPos + (this.imageWidth - this.font.width(empty)) / 2,
                    this.topPos + (CONTENT_TOP + CONTENT_BOTTOM) / 2 - 4,
                    theme.textDim(), false);
        }
        drawStatus(graphics, theme);
    }

    private void drawRows(GuiGraphics graphics, FactoryGuiTheme theme) {
        ResourceLocation fallback = WandPrecheck.baseType(this.minecraft, WandSession.draft());
        for (int index = 0; index < this.rows.size(); index++) {
            int y = this.topPos + CONTENT_TOP + index * WandRowRender.ROW_HEIGHT - this.scroll;
            if (y + WandRowRender.ROW_HEIGHT < this.topPos + CONTENT_TOP
                    || y > this.topPos + CONTENT_BOTTOM) {
                continue;
            }
            Row row = this.rows.get(index);
            ResourceLocation blockId = WandPrecheck.blockId(this.minecraft, row.block());
            if (blockId == null) {
                blockId = fallback;
            }
            if (blockId == null) {
                continue;
            }
            WandRowRender.draw(graphics, this.font, theme, this.leftPos + CONTENT_LEFT, y,
                    CONTENT_RIGHT - CONTENT_LEFT, row.block(), blockId, row.removed(),
                    isConflicted(row.block()));
        }
    }

    /**
     * 状态行：有校验问题报条数，其次报提交超时 / 失败，最后报提交中。
     *
     * @param graphics 绘制上下文
     * @param theme    主题
     */
    private void drawStatus(GuiGraphics graphics, FactoryGuiTheme theme) {
        List<WandIssue> issues = issues();
        Component status;
        if (!issues.isEmpty()) {
            status = WandMessages.panelIssues(issues.size());
        } else if (WandSession.state() == WandSession.State.SUBMITTING) {
            status = WandMessages.submitting();
        } else {
            status = WandCoordinator.panelStatus();
        }
        if (status == null) {
            return;
        }
        graphics.drawString(this.font, status, this.leftPos + CONTENT_LEFT,
                this.topPos + STATUS_Y, theme.error(), false);
    }

    /**
     * 摘要文案。
     *
     * @return 摘要
     */
    private Component summary() {
        WandDraft draft = WandSession.draft();
        if (draft.nodeId() != null) {
            return WandMessages.panelSummaryEdit(WandMessages.nodeLabel(draft.nodeId()),
                    draft.added().size(), draft.removed().size());
        }
        return WandMessages.panelSummaryNew(draft.added().size());
    }

    /**
     * 当前要显示的问题：服务端预校验优先，没回包时用客户端预检。
     *
     * @return 问题列表
     */
    private List<WandIssue> issues() {
        if (WandCoordinator.openResultReceived()) {
            return WandCoordinator.pendingIssues();
        }
        return WandSession.issues();
    }

    /**
     * 一行是否被校验判为冲突。
     *
     * @param block 方块
     * @return 冲突时为 {@code true}
     */
    private boolean isConflicted(BlockKey block) {
        for (WandIssue issue : issues()) {
            if (block.equals(issue.block())) {
                return true;
            }
        }
        return false;
    }

    /** 一行草稿：方块与是否待删除。 */
    private record Row(BlockKey block, boolean removed) {
    }
}
