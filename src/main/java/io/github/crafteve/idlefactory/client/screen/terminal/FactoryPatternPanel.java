package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEKey;

import io.github.crafteve.idlefactory.client.screen.FactoryNineSlice;
import io.github.crafteve.idlefactory.client.screen.FactoryScreen;
import io.github.crafteve.idlefactory.menu.FactoryTerminalTargetKind;
import io.github.crafteve.idlefactory.menu.FactoryTerminalTargets;
import io.github.crafteve.idlefactory.menu.FactoryPatternSlotView;
import io.github.crafteve.idlefactory.factory.machine.MachineMode;
import io.github.crafteve.idlefactory.network.payload.FactoryEditFeedbackS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryMachineModeC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryTableRequestC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryTerminalSelectC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryTerminalTargetS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTerminalTargetsS2C;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 主动样板面板：目标列表、选中目标的模式 / 翻页，以及 9 格样板槽。
 *
 * <p>样板槽不是自己画的：它们是菜单里的真实 {@code AppEngSlot}，屏幕按本面板给出的
 * 格子坐标摆位。面板只负责画槽底、目标列表与翻页控件，物品交互全部交给 AE2 的槽位。</p>
 */
public final class FactoryPatternPanel extends FactoryTerminalPanel {
    /** 目标行高。 */
    private static final int TARGET_ROW = 16;
    /** 样板格边长。 */
    private static final int CELL = 18;
    /** 页控件宽度。 */
    private static final int PAGE_WIDTH = 54;
    /** 模式按钮宽度。 */
    private static final int MODE_WIDTH = 58;
    /** 反馈停留时间（毫秒）。 */
    private static final long FEEDBACK_MILLIS = 3000L;

    private List<FactoryTerminalTargetsS2C.Entry> targets = List.of();
    @Nullable
    private FactoryTerminalTargetS2C detail;
    private String selectedKey = "";
    private int page;
    private int firstRow;
    @Nullable
    private String feedback;
    private boolean feedbackOk = true;
    private long feedbackUntil;

    /**
     * @param containerId 所属菜单容器 id
     */
    public FactoryPatternPanel(int containerId) {
        super(containerId, "合成");
    }

    @Override
    protected String title() {
        return "主动样板";
    }

    @Override
    protected String subtitle() {
        return "选择目标后管理样板与模式";
    }

    @Override
    protected void requestSnapshot() {
        // 样板视图复用机器表请求：服务端会顺手回主动目标列表。
        PacketDistributor.sendToServer(new FactoryTableRequestC2S(this.containerId));
    }

    @Override
    public void acceptTargets(FactoryTerminalTargetsS2C payload) {
        this.targets = payload.targets();
        if (!this.selectedKey.isEmpty() && this.targetRow(this.selectedKey) < 0) {
            this.selectedKey = "";
            this.detail = null;
        }
        this.firstRow = Mth.clamp(this.firstRow, 0,
                Math.max(0, this.targets.size() - this.targetRows()));
    }

    @Override
    public void acceptTargetDetail(FactoryTerminalTargetS2C payload) {
        this.detail = payload;
        this.selectedKey = payload.key();
        this.page = payload.page();
    }

    @Override
    public void acceptEditFeedback(FactoryEditFeedbackS2C payload) {
        this.feedback = payload.message();
        this.feedbackOk = payload.ok();
        this.feedbackUntil = Util.getMillis() + FEEDBACK_MILLIS;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderBackground(graphics, mouseX, mouseY);
        this.renderTargets(graphics, mouseX, mouseY);
        this.renderDetail(graphics, mouseX, mouseY);
        this.renderSlots(graphics);
        this.renderFeedback(graphics);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        int row = this.targetRowAt(mouseY);
        if (row >= 0) {
            int index = this.firstRow + row;
            if (index < this.targets.size()) {
                this.select(this.targets.get(index).key(), 0);
            }
            return true;
        }
        if (this.detail != null && this.detail.kind() == FactoryTerminalTargetKind.MACHINE
                && this.inModeButton(mouseX, mouseY)) {
            MachineMode next = this.detail.modeOrdinal() == MachineMode.ACTIVE.ordinal()
                    ? MachineMode.PASSIVE : MachineMode.ACTIVE;
            PacketDistributor.sendToServer(new FactoryMachineModeC2S(this.containerId,
                    machineId(this.detail.key()), next));
            return true;
        }
        if (!this.selectedKey.isEmpty() && this.inPageButton(mouseX, mouseY)) {
            int pages = this.pageCount();
            int next = mouseX < this.pageLeft() + PAGE_WIDTH / 2
                    ? Math.max(0, this.page - 1) : Math.min(pages - 1, this.page + 1);
            this.select(this.selectedKey, next);
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaY) {
        if (deltaY == 0.0D || mouseY < this.listTop() || mouseY >= this.listBottom()) {
            return true;
        }
        int max = Math.max(0, this.targets.size() - this.targetRows());
        this.firstRow = Mth.clamp(this.firstRow - (int) Math.signum(deltaY), 0, max);
        return true;
    }

    /**
     * 目标格子的屏幕 x；屏幕用它给真实槽位摆位。
     *
     * @param index 槽位下标 0..8
     * @return 绝对 x
     */
    public int patternSlotX(int index) {
        return this.contentLeft() + 2 + (index % 3) * CELL;
    }

    /**
     * 目标格子的屏幕 y；屏幕用它给真实槽位摆位。
     *
     * @param index 槽位下标 0..8
     * @return 绝对 y
     */
    public int patternSlotY(int index) {
        return this.gridTop() + (index / 3) * CELL;
    }

    /**
     * 发送选中请求。
     *
     * @param key  目标键
     * @param page 页号
     */
    private void select(String key, int page) {
        this.selectedKey = key;
        this.page = Math.max(0, page);
        PacketDistributor.sendToServer(new FactoryTerminalSelectC2S(this.containerId, key, page));
    }

    /** 当前是否选中了目标；屏幕据此启用 / 禁用样板槽。 */
    public boolean hasSelection() {
        return this.detail != null;
    }

    /**
     * 鼠标是否落在 3×3 样板格上；屏幕据此把事件让给真实槽位。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 命中格子时为 {@code true}
     */
    public boolean inPatternGrid(double mouseX, double mouseY) {
        if (!this.hasSelection()) {
            return false;
        }
        int left = this.patternSlotX(0);
        int top = this.patternSlotY(0);
        return mouseX >= left && mouseX < left + CELL * 3
                && mouseY >= top && mouseY < top + CELL * 3;
    }

    private void renderTargets(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = this.contentLeft();
        int width = this.contentWidth();
        if (this.targets.isEmpty()) {
            graphics.drawString(this.font, "没有可用目标", x + 2, this.listTop() + 2,
                    this.theme.textDim(), false);
            return;
        }
        int rows = this.targetRows();
        for (int row = 0; row < rows; row++) {
            int index = this.firstRow + row;
            if (index >= this.targets.size()) {
                break;
            }
            FactoryTerminalTargetsS2C.Entry entry = this.targets.get(index);
            int y = this.listTop() + row * TARGET_ROW;
            boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y
                    && mouseY < y + TARGET_ROW;
            if (entry.key().equals(this.selectedKey)) {
                graphics.fill(x, y, x + width, y + TARGET_ROW,
                        FactoryTerminalGeometry.ROW_SELECTED);
            } else if (hovered) {
                graphics.fill(x, y, x + width, y + TARGET_ROW,
                        FactoryTerminalGeometry.ROW_HOVER);
            }
            if (!entry.icon().isEmpty()) {
                graphics.renderItem(entry.icon(), x, y);
            }
            int countWidth = 34;
            String count = entry.used() + "/" + entry.capacity();
            graphics.drawString(this.font, this.trim(entry.name(), width - 20 - countWidth - 2),
                    x + 18, y + 4, this.theme.textPrimary(), false);
            graphics.drawString(this.font, count, x + width - this.font.width(count), y + 4,
                    this.theme.textDim(), false);
        }
    }

    private void renderDetail(GuiGraphics graphics, int mouseX, int mouseY) {
        if (this.detail == null) {
            graphics.drawString(this.font, "选中目标后显示样板槽", this.contentLeft() + 2,
                    this.detailTop() + 3, this.theme.textDim(), false);
            return;
        }
        if (this.detail.kind() == FactoryTerminalTargetKind.MACHINE) {
            boolean active = this.detail.modeOrdinal() == MachineMode.ACTIVE.ordinal();
            FactoryNineSlice.button(graphics, this.modeLeft(), this.detailTop(), MODE_WIDTH,
                    FactoryTerminalGeometry.SEARCH_HEIGHT);
            String label = active ? "主动" : "被动";
            graphics.drawString(this.font, label,
                    this.modeLeft() + (MODE_WIDTH - this.font.width(label)) / 2,
                    this.detailTop() + 3, active ? this.theme.statusRun() : this.theme.textDim(),
                    false);
        }
        if (this.pageCount() > 1) {
            FactoryNineSlice.button(graphics, this.pageLeft(), this.detailTop(), PAGE_WIDTH,
                    FactoryTerminalGeometry.SEARCH_HEIGHT);
            String label = "◀ " + (this.page + 1) + "/" + this.pageCount() + " ▶";
            graphics.drawString(this.font, label,
                    this.pageLeft() + (PAGE_WIDTH - this.font.width(label)) / 2,
                    this.detailTop() + 3, this.theme.textPrimary(), false);
        }
    }

    private void renderSlots(GuiGraphics graphics) {
        if (!this.hasSelection()) {
            return;
        }
        for (int i = 0; i < FactoryPatternSlotView.PAGE_SIZE; i++) {
            FactoryNineSlice.slot(graphics, this.patternSlotX(i), this.patternSlotY(i), CELL, CELL);
        }
    }

    private void renderFeedback(GuiGraphics graphics) {
        if (this.feedback == null || Util.getMillis() > this.feedbackUntil) {
            return;
        }
        int color = this.feedbackOk ? this.theme.statusRun() : this.theme.error();
        graphics.drawString(this.font, this.trim(this.feedback, this.contentWidth() - 4),
                this.contentLeft() + 2, this.bottom() - 12, color, false);
    }

    @Override
    public FactoryScreen.JeiTarget jeiTargetAt(double mouseX, double mouseY) {
        int row = this.targetRowAt(mouseY);
        if (row < 0) {
            return null;
        }
        int index = this.firstRow + row;
        if (index >= this.targets.size()
                || mouseX >= this.contentLeft() + FactoryTerminalGeometry.ICON_SIZE) {
            return null;
        }
        FactoryTerminalTargetsS2C.Entry entry = this.targets.get(index);
        return this.itemTarget(entry.icon(), this.contentLeft(),
                this.listTop() + row * TARGET_ROW);
    }

    @Override
    public boolean dropJeiKey(AEKey key, double mouseX, double mouseY) {
        // 样板槽是真实槽位，JEI 拖拽走 AE2 自己的槽位交互，不需要额外区域。
        return false;
    }

    @Override
    public List<FactoryTerminalDropArea> jeiDropAreas() {
        return List.of();
    }

    private int contentLeft() {
        return this.left() + FactoryTerminalGeometry.CONTENT_PADDING;
    }

    private int contentWidth() {
        return FactoryTerminalGeometry.contentWidth();
    }

    private int listTop() {
        return this.top() + FactoryTerminalGeometry.HEADER_HEIGHT + 15;
    }

    private int targetRows() {
        return Mth.clamp((this.height() - 150) / TARGET_ROW, 2, 6);
    }

    private int listBottom() {
        return this.listTop() + this.targetRows() * TARGET_ROW;
    }

    private int detailTop() {
        return this.listBottom() + 2;
    }

    private int gridTop() {
        return this.detailTop() + FactoryTerminalGeometry.SEARCH_HEIGHT + 4;
    }

    private int modeLeft() {
        return this.contentLeft();
    }

    private int pageLeft() {
        return this.contentLeft() + this.contentWidth() - PAGE_WIDTH;
    }

    private int pageCount() {
        if (this.detail == null) {
            return 1;
        }
        return Math.max(1, (this.detail.capacity() + FactoryPatternSlotView.PAGE_SIZE - 1)
                / FactoryPatternSlotView.PAGE_SIZE);
    }

    private int targetRowAt(double mouseY) {
        if (mouseY < this.listTop() || mouseY >= this.listBottom()) {
            return -1;
        }
        return (int) ((mouseY - this.listTop()) / TARGET_ROW);
    }

    private int targetRow(String key) {
        for (int i = 0; i < this.targets.size(); i++) {
            if (this.targets.get(i).key().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private boolean inModeButton(double mouseX, double mouseY) {
        return mouseX >= this.modeLeft() && mouseX < this.modeLeft() + MODE_WIDTH
                && mouseY >= this.detailTop()
                && mouseY < this.detailTop() + FactoryTerminalGeometry.SEARCH_HEIGHT;
    }

    private boolean inPageButton(double mouseX, double mouseY) {
        return this.pageCount() > 1 && mouseX >= this.pageLeft()
                && mouseX < this.pageLeft() + PAGE_WIDTH
                && mouseY >= this.detailTop()
                && mouseY < this.detailTop() + FactoryTerminalGeometry.SEARCH_HEIGHT;
    }

    /**
     * 从机器目标键里取机器 id。
     *
     * @param key 目标键
     * @return 机器 id；格式不对时返回全零 uuid
     */
    private static UUID machineId(String key) {
        if (!key.startsWith(FactoryTerminalTargets.MACHINE_PREFIX)) {
            return new UUID(0L, 0L);
        }
        try {
            return UUID.fromString(key.substring(FactoryTerminalTargets.MACHINE_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return new UUID(0L, 0L);
        }
    }

    /**
     * 按像素宽度截断文字，末尾加省略号。
     *
     * @param text  原文
     * @param width 最大宽度
     * @return 截断后的文字
     */
    private String trim(String text, int width) {
        if (width <= 0) {
            return "";
        }
        if (this.font.width(text) <= width) {
            return text;
        }
        String ellipsis = "…";
        int budget = width - this.font.width(ellipsis);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (this.font.width(builder.toString() + c) > budget) {
                break;
            }
            builder.append(c);
        }
        return builder + ellipsis;
    }
}
