package io.github.crafteve.ae2vf.client.screen.wand;

import io.github.crafteve.ae2vf.client.screen.wand.WandRowRender;


import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.AE2Button;

import io.github.crafteve.ae2vf.client.screen.NineSliceRenderer;
import io.github.crafteve.ae2vf.client.wand.WandSubmitter;
import io.github.crafteve.ae2vf.client.wand.WandLinkCache;
import io.github.crafteve.ae2vf.client.screen.text.WandText;
import io.github.crafteve.ae2vf.client.wand.WandPrecheck;
import io.github.crafteve.ae2vf.client.wand.WandDraftState;
import io.github.crafteve.ae2vf.key.BlockKey;
import io.github.crafteve.ae2vf.command.WandDraft;
import io.github.crafteve.ae2vf.menu.wand.WandDraftMenu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 草稿确认面板：AE2 对话框样式，逐行列出草稿并提交。
 *
 * <p>背景与两个按钮的几何来自 {@code assets/ae2/screens/ae2vf_wand_draft.json} 的
 * {@code generatedBackground}：那是 AE2 自己按尺寸生成的浅色边框底，不带任何烤死的表格线。
 * 面板内容（标题、摘要、草稿行、滚动条、状态行）全部自绘，颜色取自本类常量与文字色与 {@code NineSliceRenderer} 的凹槽贴图，和节点终端保持同一套外观。</p>
 *
 * <p>行来自客户端草稿，问题以服务端预校验为准（{@link WandSubmitter#pendingIssues()}），
 * 服务端还没回包时退回客户端预检结果。面板只是监视器：确定发提交意图，取消丢草稿，Esc /
 * 关闭保留草稿回到编辑态。</p>
 *
 * <p>不建任何槽位；背包标签按对话框惯例藏到屏幕外。</p>
 */
public final class WandDraftScreen extends AEBaseScreen<WandDraftMenu> {

    /** 面板底色。 */
    private static final int PANEL_BG = 0xFFCBCCD4;
    /** 正文色。 */
    private static final int TEXT_PRIMARY = 0xFF000000;
    /** 次要文字。 */
    private static final int TEXT_DIM = 0xFF5F5F5F;
    /** 亮边。 */
    private static final int BEVEL_LIGHT = 0xFFD8D9DF;
    /** 内凹阴影。 */
    private static final int BEVEL_DARK = 0xFFBFC0C9;
    /** 面板最外圈深色。 */
    private static final int PANEL_BORDER = 0xFF413F54;
    /** 错误色。 */
    private static final int ERROR = 0xFFD63A3A;
    /** 标题行顶边。 */
    private static final int TITLE_Y = 8;
    /** 标题行左边距。 */
    private static final int TITLE_X = 10;
    /** 摘要行顶边。 */
    private static final int SUMMARY_Y = 21;
    /** 行区外框：左、上、宽、高；框内是凹槽底。 */
    private static final int FRAME_X = 8;
    private static final int FRAME_Y = 34;
    private static final int FRAME_W = 224;
    private static final int FRAME_H = 124;
    /** 行区内边距。 */
    private static final int INNER_PAD = 4;
    /** 行区右边界（给滚动条留 3px 加 2px 间隙）。 */
    private static final int INNER_RIGHT = FRAME_X + FRAME_W - 9;
    /** 滚动条左边界与宽度。 */
    private static final int SCROLL_X = FRAME_X + FRAME_W - 6;
    private static final int SCROLL_W = 3;
    /** 状态行顶边。 */
    private static final int STATUS_Y = 161;

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
    public WandDraftScreen(WandDraftMenu menu, Inventory inventory, Component title,
            ScreenStyle style) {
        super(menu, inventory, title, style);
        // 没有背包槽位：把原版的背包标签藏到屏幕外，免得压在草稿行上
        this.inventoryLabelY = -1000;
        this.confirm = this.widgets.addButton("start", WandText.panelConfirm(), this::confirm);
        this.cancel = this.widgets.addButton("cancel", WandText.panelCancel(), this::cancel);
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
        int max = maxScroll();
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
        WandSubmitter.clearPendingIssues();
        super.removed();
    }

    /**
     * 提交草稿；确定灰显时忽略。
     */
    private void confirm() {
        if (!canConfirm()) {
            return;
        }
        WandSubmitter.submit(this.menu.containerId);
    }

    /**
     * 取消：丢草稿并关闭面板。
     */
    private void cancel() {
        if (WandDraftState.state() == WandDraftState.State.SUBMITTING) {
            return;
        }
        WandDraftState.clear();
        this.onClose();
        this.minecraft.setScreen(null);
    }

    /**
     * 重建行列表：先待加入，再待删除。
     */
    private void refreshRows() {
        this.rows.clear();
        WandDraft draft = WandDraftState.draft();
        for (BlockKey block : draft.added()) {
            this.rows.add(new Row(block, false));
        }
        for (UUID machineId : draft.removed()) {
            Optional<BlockKey> block = WandLinkCache.blockOfMachine(machineId);
            block.ifPresent(value -> this.rows.add(new Row(value, true)));
        }
        this.scroll = Math.min(this.scroll, maxScroll());
    }

    /** 按提交态与校验结果更新两个按钮。 */
    private void updateButtons() {
        boolean submitting = WandDraftState.state() == WandDraftState.State.SUBMITTING;
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
        return WandDraftState.state() == WandDraftState.State.EDITING
                && !this.rows.isEmpty()
                && conflicts().isEmpty();
    }

    /** 行区可显示高度。 */
    private static int viewportHeight() {
        return FRAME_H - INNER_PAD * 2;
    }

    /** 最大滚动量；行数不足一屏时为 0。 */
    private int maxScroll() {
        return Math.max(0, this.rows.size() * WandRowRender.ROW_HEIGHT - viewportHeight());
    }

    /**
     * 画标题、摘要、行区外框、草稿行、滚动条与状态行。
     *
     * @param graphics 绘制上下文
     */
    private void drawPanel(GuiGraphics graphics) {
        int left = this.leftPos;
        int top = this.topPos;

        graphics.drawString(this.font, WandText.panelTitle(), left + TITLE_X,
                top + TITLE_Y, TEXT_PRIMARY, false);
        graphics.drawString(this.font, summary(), left + TITLE_X, top + SUMMARY_Y,
                TEXT_DIM, false);

        NineSliceRenderer.slot(graphics, left + FRAME_X, top + FRAME_Y, FRAME_W, FRAME_H);
        int innerLeft = left + FRAME_X + INNER_PAD;
        int innerTop = top + FRAME_Y + INNER_PAD;
        int innerRight = left + INNER_RIGHT;
        int innerBottom = top + FRAME_Y + FRAME_H - INNER_PAD;
        graphics.enableScissor(innerLeft, innerTop, innerRight, innerBottom);
        try {
            drawRows(graphics);
        } finally {
            graphics.disableScissor();
        }
        if (this.rows.isEmpty()) {
            Component empty = WandText.panelEmpty();
            graphics.drawString(this.font, empty,
                    left + FRAME_X + (FRAME_W - this.font.width(empty)) / 2,
                    (innerTop + innerBottom) / 2 - 4, TEXT_DIM, false);
        }
        drawScrollbar(graphics, innerTop, innerBottom);
        drawStatus(graphics);
    }

    private void drawRows(GuiGraphics graphics) {
        ResourceLocation fallback = WandPrecheck.baseType(this.minecraft, WandDraftState.draft());
        int innerLeft = this.leftPos + FRAME_X + INNER_PAD;
        int rowWidth = INNER_RIGHT - FRAME_X - INNER_PAD;
        for (int index = 0; index < this.rows.size(); index++) {
            int y = this.topPos + FRAME_Y + INNER_PAD + index * WandRowRender.ROW_HEIGHT
                    - this.scroll;
            if (y + WandRowRender.ROW_HEIGHT <= this.topPos + FRAME_Y + INNER_PAD
                    || y >= this.topPos + FRAME_Y + FRAME_H - INNER_PAD) {
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
            WandRowRender.draw(graphics, this.font, innerLeft, y, rowWidth,
                    row.block(), blockId, row.removed(), isConflicted(row.block()));
        }
    }

    /**
     * 滚动条：只在行数超出一屏时画，轨道与滑块都用主题色。
     *
     * @param graphics   绘制上下文
     * @param theme      主题
     * @param innerTop   轨道顶边（屏幕坐标）
     * @param innerBottom 轨道底边（屏幕坐标）
     */
    private void drawScrollbar(GuiGraphics graphics, int innerTop,
            int innerBottom) {
        int viewport = viewportHeight();
        int total = this.rows.size() * WandRowRender.ROW_HEIGHT;
        if (total <= viewport) {
            return;
        }
        int x = this.leftPos + SCROLL_X;
        graphics.fill(x, innerTop, x + SCROLL_W, innerBottom, BEVEL_DARK);
        int thumbHeight = Math.max(10, viewport * viewport / total);
        int travel = viewport - thumbHeight;
        int thumbY = innerTop + travel * this.scroll / Math.max(1, maxScroll());
        graphics.fill(x, thumbY, x + SCROLL_W, thumbY + thumbHeight, PANEL_BORDER);
    }

    /**
     * 状态行：校验问题 &gt; 提交中 &gt; 提交失败 / 超时 &gt; 普通操作提示。
     *
     * @param graphics 绘制上下文
     * @param theme    主题
     */
    private void drawStatus(GuiGraphics graphics) {
        List<io.github.crafteve.ae2vf.key.BlockKey> conflicts = conflicts();
        Component status;
        int color;
        if (!conflicts.isEmpty()) {
            status = WandText.panelIssues(conflicts.size());
            color = ERROR;
        } else if (WandDraftState.state() == WandDraftState.State.SUBMITTING) {
            status = WandText.submitting();
            color = TEXT_DIM;
        } else if (WandSubmitter.panelStatus() != null) {
            status = WandSubmitter.panelStatus();
            color = ERROR;
        } else {
            status = WandText.panelHint();
            color = TEXT_DIM;
        }
        graphics.drawString(this.font, status, this.leftPos + FRAME_X + 2,
                this.topPos + STATUS_Y, color, false);
    }

    /**
     * 摘要文案。
     *
     * @return 摘要
     */
    private Component summary() {
        WandDraft draft = WandDraftState.draft();
        if (draft.nodeId() != null) {
            return WandText.panelSummaryEdit(WandText.nodeLabel(draft.nodeId()),
                    draft.added().size(), draft.removed().size());
        }
        return WandText.panelSummaryNew(draft.added().size());
    }

    /**
     * 当前要显示的问题：服务端预校验优先，没回包时用客户端预检。
     *
     * @return 问题列表
     */
    private List<io.github.crafteve.ae2vf.key.BlockKey> conflicts() {
        if (WandSubmitter.openResultReceived()) {
            return WandSubmitter.pendingConflicts();
        }
        return WandDraftState.conflicts();
    }

    /**
     * 一行是否被校验判为冲突。
     *
     * @param block 方块
     * @return 冲突时为 {@code true}
     */
    private boolean isConflicted(BlockKey block) {
        return conflicts().contains(block);
    }

    /** 一行草稿：方块与是否待删除。 */
    private record Row(BlockKey block, boolean removed) {
    }
}
