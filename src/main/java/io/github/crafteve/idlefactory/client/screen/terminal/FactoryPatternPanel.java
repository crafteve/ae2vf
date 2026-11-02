package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEKey;

import io.github.crafteve.idlefactory.client.screen.FactoryGuiTextures;
import io.github.crafteve.idlefactory.client.screen.FactoryLabels;
import io.github.crafteve.idlefactory.client.screen.FactoryNineSlice;
import io.github.crafteve.idlefactory.client.screen.FactoryScreen;
import io.github.crafteve.idlefactory.menu.FactoryTerminalTargets;
import io.github.crafteve.idlefactory.network.payload.FactoryEditFeedbackS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryPatternSlotActionC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryTableRequestC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryTerminalTargetS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTerminalTargetsS2C;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 主动样板面板：照着 AE2 样板管理终端的列表做——一台机器一段，段头是机器描述行，
 * 下面按样板池容量铺 9 列 × n 行样板槽；整块可滚动。
 *
 * <p>不分区：没有「目标列表 / 详情 / 翻页」这些层，列表本身就是全部内容。槽位是自绘的
 * （格子 + 图标），点击与拖拽由面板自己按「机器 + 槽位下标」换算成动作，不挂菜单槽位。</p>
 */
public final class FactoryPatternPanel extends FactoryTerminalPanel {
    /** 机器描述行高：和槽位格同高，机器图标正好压在第一列槽位上。 */
    private static final int HEADER_ROW = 18;
    /** 槽位格边长：16 格 + 2 间距。 */
    private static final int CELL = 18;
    /** 每行槽位数。 */
    private static final int COLUMNS = 9;
    /** 段与段之间的间距。 */
    private static final int SECTION_GAP = 3;
    /** 9 列槽位的总宽。 */
    private static final int GRID_WIDTH = COLUMNS * CELL;
    /** 反馈停留时间（毫秒）。 */
    private static final long FEEDBACK_MILLIS = 3000L;

    private List<FactoryTerminalTargetsS2C.Entry> targets = List.of();
    private int scroll;
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
    protected void requestSnapshot() {
        // 样板视图复用机器表请求：服务端会顺手回主动目标列表（带样板槽内容）。
        PacketDistributor.sendToServer(new FactoryTableRequestC2S(this.containerId));
    }

    @Override
    public void acceptTargets(FactoryTerminalTargetsS2C payload) {
        this.targets = payload.targets();
        this.scroll = Mth.clamp(this.scroll, 0,
                Math.max(0, this.contentHeight() - this.viewHeight()));
    }

    @Override
    public void acceptTargetDetail(FactoryTerminalTargetS2C payload) {
        // 列表直接带样板槽内容，详情包不再参与渲染。
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
        this.drawWorkArea(graphics);
        this.renderList(graphics, mouseX, mouseY);
        this.renderFeedback(graphics);
    }

    /**
     * 深色工作区：和被动 IO 面板同一套底。
     *
     * @param graphics 绘制器
     */
    private void drawWorkArea(GuiGraphics graphics) {
        // 和被动 IO 面板完全同一套几何：x = 内容左边界，y = 列表顶，高 = 列表底 - 列表顶。
        int x = this.contentLeft();
        int y = this.listTop();
        int width = this.contentWidth();
        int height = this.listBottom() - y;
        if (width <= 0 || height <= 0) {
            return;
        }
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.WORK_BG, x, y, width, height);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.TABLE_LIGHT, x, y, width, 1);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.TABLE_LIGHT, x, y + height - 1,
                width, 1);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.TABLE_LIGHT, x, y, 1, height);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.TABLE_LIGHT, x + width - 1, y, 1,
                height);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.WORK_SHADOW, x + 1, y + 1,
                width - 2, FactoryTerminalGeometry.WORK_SHADOW_HEIGHT);
    }

    /**
     * 逐段画机器列表；段按滚动量上移，超出工作区的段整段跳过。
     *
     * @param graphics 绘制器
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     */
    private void renderList(GuiGraphics graphics, int mouseX, int mouseY) {
        int left = this.gridLeft();
        int right = left + GRID_WIDTH;
        int viewTop = this.listTop();
        int viewBottom = this.listBottom();
        int y = viewTop - this.scroll;
        for (FactoryTerminalTargetsS2C.Entry entry : this.targets) {
            int rows = rowsOf(entry);
            int sectionHeight = HEADER_ROW + rows * CELL + SECTION_GAP;
            if (y + sectionHeight < viewTop || y > viewBottom) {
                y += sectionHeight;
                continue;
            }
            this.drawHeader(graphics, entry, left, y, right);
            this.drawPatterns(graphics, entry, left, y + HEADER_ROW, viewTop, viewBottom,
                    mouseX, mouseY);
            y += sectionHeight;
        }
        if (this.targets.isEmpty()) {
            graphics.drawString(this.font, "没有可用机器", left + 2, viewTop + 2,
                    this.theme.textDim(), false);
        }
    }

    /**
     * 段头：机器图标 + 名字 + 用量。
     *
     * @param graphics 绘制器
     * @param entry    目标
     * @param x        左边界
     * @param y        上边界
     * @param right    右边界
     */
    private void drawHeader(GuiGraphics graphics, FactoryTerminalTargetsS2C.Entry entry, int x,
            int y, int right) {
        if (!entry.icon().isEmpty()) {
            // 机器图标按槽位格同尺寸（18×18）缩放，和下面第一列槽位严格对齐。
            graphics.pose().pushPose();
            graphics.pose().translate(x, y, 0);
            graphics.pose().scale((float) CELL / 16.0F, (float) CELL / 16.0F, 1.0F);
            graphics.renderItem(entry.icon(), 0, 0);
            graphics.pose().popPose();
        }
        String count = entry.used() + "/" + entry.capacity();
        int countWidth = this.font.width(count);
        graphics.drawString(this.font, this.trim(entry.name(), right - x - 22 - countWidth - 4),
                x + 20, y + 5, this.theme.textPrimary(), false);
        graphics.drawString(this.font, count, right - countWidth - 2, y + 5,
                this.theme.textDim(), false);
    }

    /**
     * 段内样板槽：9 列 × n 行，格子底 + 样板图标；只画可见行。
     *
     * @param graphics   绘制器
     * @param entry      目标
     * @param left       左边界
     * @param top        第一行槽位的上边界
     * @param viewTop    工作区上边界
     * @param viewBottom 工作区下边界
     * @param mouseX     鼠标 x
     * @param mouseY     鼠标 y
     */
    private void drawPatterns(GuiGraphics graphics, FactoryTerminalTargetsS2C.Entry entry,
            int left, int top, int viewTop, int viewBottom, int mouseX, int mouseY) {
        int slots = Math.max(COLUMNS, rowsOf(entry) * COLUMNS);
        Map<Integer, ItemStack> patterns = entry.patterns();
        for (int slot = 0; slot < slots; slot++) {
            int col = slot % COLUMNS;
            int row = slot / COLUMNS;
            int x = left + col * CELL;
            int y = top + row * CELL;
            if (y + CELL < viewTop || y > viewBottom) {
                continue;
            }
            FactoryNineSlice.terminalSlot(graphics, x, y, CELL, CELL);
            ItemStack stack = patterns.get(slot);
            if (stack != null && !stack.isEmpty()) {
                graphics.renderItem(stack, x + 1, y + 1);
            }
            boolean hovered = mouseX >= x && mouseX < x + CELL && mouseY >= y
                    && mouseY < y + CELL && mouseY >= viewTop && mouseY < viewBottom;
            if (hovered) {
                graphics.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, 0x40FFFFFF);
            }
        }
    }

    private void renderFeedback(GuiGraphics graphics) {
        if (this.feedback == null || Util.getMillis() > this.feedbackUntil) {
            return;
        }
        int color = this.feedbackOk ? this.theme.statusRun() : this.theme.error();
        graphics.drawString(this.font, this.trim(this.feedback, this.contentWidth() - 4),
                this.contentLeft(), this.bottom() - 12, color, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 0) {
            SlotHit hit = this.slotAt(mouseX, mouseY);
            if (hit != null) {
                this.sendSlotAction(hit);
            }
        }
        return true;
    }

    /**
     * 按鼠标位置找自绘样板槽。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @return 命中的槽；不在槽上时为 {@code null}
     */
    @Nullable
    private SlotHit slotAt(double mouseX, double mouseY) {
        int left = this.gridLeft();
        int y = this.listTop() - this.scroll;
        for (FactoryTerminalTargetsS2C.Entry entry : this.targets) {
            int rows = rowsOf(entry);
            int gridTop = y + HEADER_ROW;
            int gridHeight = rows * CELL;
            if (mouseY >= gridTop && mouseY < gridTop + gridHeight
                    && mouseX >= left && mouseX < left + COLUMNS * CELL) {
                int col = (int) ((mouseX - left) / CELL);
                int row = (int) ((mouseY - gridTop) / CELL);
                int slot = row * COLUMNS + col;
                if (slot < entry.capacity()) {
                    return new SlotHit(parseMachineId(entry.key()), slot,
                            entry.patterns().getOrDefault(slot, ItemStack.EMPTY));
                }
            }
            y += HEADER_ROW + gridHeight + SECTION_GAP;
        }
        return null;
    }

    /**
     * 按光标与槽位状态决定动作并发给服务端。
     *
     * @param hit 命中的槽
     */
    private void sendSlotAction(SlotHit hit) {
        if (this.minecraft.player == null) {
            return;
        }
        ItemStack carried = this.minecraft.player.containerMenu.getCarried();
        FactoryPatternSlotActionC2S.Action action;
        if (Screen.hasShiftDown()) {
            action = FactoryPatternSlotActionC2S.Action.QUICK_MOVE;
        } else if (carried.isEmpty()) {
            action = FactoryPatternSlotActionC2S.Action.PICKUP;
        } else if (hit.stack().isEmpty()) {
            action = FactoryPatternSlotActionC2S.Action.PLACE;
        } else {
            action = FactoryPatternSlotActionC2S.Action.SWAP;
        }
        PacketDistributor.sendToServer(new FactoryPatternSlotActionC2S(this.containerId,
                hit.machineId(), hit.slot(), action));
    }

    /**
     * 从目标键里取机器 id。
     *
     * @param key 目标键
     * @return 机器 id；格式不对时返回全零 uuid
     */
    private static UUID parseMachineId(String key) {
        if (!key.startsWith(FactoryTerminalTargets.MACHINE_PREFIX)) {
            return new UUID(0L, 0L);
        }
        try {
            return UUID.fromString(key.substring(FactoryTerminalTargets.MACHINE_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return new UUID(0L, 0L);
        }
    }

    /** 命中的自绘样板槽。 */
    private record SlotHit(UUID machineId, int slot, ItemStack stack) {
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaY) {
        if (deltaY == 0.0D || mouseY < this.listTop() || mouseY >= this.listBottom()) {
            return true;
        }
        int max = Math.max(0, this.contentHeight() - this.viewHeight());
        this.scroll = Mth.clamp(this.scroll - (int) Math.signum(deltaY) * CELL, 0, max);
        return true;
    }

    @Override
    public FactoryScreen.JeiTarget jeiTargetAt(double mouseX, double mouseY) {
        // 段头图标命中时返回该机器的图标，供 R/U 查配方。
        int y = this.listTop() - this.scroll;
        for (FactoryTerminalTargetsS2C.Entry entry : this.targets) {
            int rows = rowsOf(entry);
            int sectionHeight = HEADER_ROW + rows * CELL + SECTION_GAP;
            if (mouseY >= y && mouseY < y + HEADER_ROW && mouseX >= this.gridLeft()
                    && mouseX < this.gridLeft() + CELL) {
                return this.itemTarget(entry.icon(), this.gridLeft(), y);
            }
            y += sectionHeight;
        }
        return null;
    }

    @Override
    public boolean dropJeiKey(AEKey key, double mouseX, double mouseY) {
        // 样板槽是自绘的，JEI 拖拽下一步接动作包；先不吞。
        return false;
    }

    @Override
    public List<FactoryTerminalDropArea> jeiDropAreas() {
        return List.of();
    }

    /**
     * 槽位网格的左起点：在内容区里左右居中，避免左边顶着工作区、右边空一大块。
     *
     * @return 网格左边界
     */
    private int gridLeft() {
        return this.contentLeft() + Math.max(0, (this.contentWidth() - GRID_WIDTH) / 2);
    }

    /** 机器段占几行槽位；容量 0 也至少给一行。 */
    private static int rowsOf(FactoryTerminalTargetsS2C.Entry entry) {
        return Math.max(1, (entry.capacity() + COLUMNS - 1) / COLUMNS);
    }

    /** 列表总高（含段间距）。 */
    private int contentHeight() {
        int height = 0;
        for (FactoryTerminalTargetsS2C.Entry entry : this.targets) {
            height += HEADER_ROW + rowsOf(entry) * CELL + SECTION_GAP;
        }
        return height;
    }

    /** 工作区可视高度。 */
    private int viewHeight() {
        return this.listBottom() - this.listTop();
    }

    private int contentLeft() {
        return this.left() + FactoryTerminalGeometry.CONTENT_PADDING;
    }

    private int contentWidth() {
        return FactoryTerminalGeometry.contentWidth();
    }

    private int listTop() {
        // 和被动 IO 面板对齐：标题下 4px 就是工作区起点。
        return this.top() + FactoryTerminalGeometry.HEADER_HEIGHT + 4;
    }

    private int listBottom() {
        return this.bottom() - FactoryTerminalGeometry.CONTENT_PADDING;
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
