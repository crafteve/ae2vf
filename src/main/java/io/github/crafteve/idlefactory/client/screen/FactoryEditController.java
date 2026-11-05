package io.github.crafteve.idlefactory.client.screen;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.factory.io.IoSide;
import io.github.crafteve.idlefactory.factory.machine.MatchMode;
import appeng.api.stacks.AEKey;
import io.github.crafteve.idlefactory.factory.machine.ResourceSpec;
import io.github.crafteve.idlefactory.network.payload.FactoryEditFeedbackS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryMachineActionC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryRuleActionC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryStorageActionC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryStorageS2C;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import appeng.api.stacks.AEItemKey;

/**
 * 工作台编辑态的总控：确认浮层、底部反馈和拖拽。
 *
 * <p>它把界面动作翻译成 C2S 包，并且只根据服务端反馈与下一份快照更新界面；不做乐观写入。
 * 绘制与命中仍由各组件自己负责，本类只管顺序和状态。</p>
 *
 * <p>P1 删除产线后本类只剩两类编辑：仓库资源拖到 IO 空卡新增规则、规则拖回仓库删除，
 * 以及删除机器前的确认浮层。原来「把机器拖进产线 / 改堆叠台数 / 命名产线」的交互随产线一并删除。</p>
 *
 * <p>输入优先级：确认浮层 → 拖拽 → 交给 {@link FactoryScreen} 的原有逻辑。</p>
 */
public final class FactoryEditController implements FactoryScreenInteraction {
    /** 底部反馈停留 tick。 */
    private static final int FEEDBACK_TICKS = 80;

    private final FactoryScreen screen;
    private final Font font;
    private final FactoryGuiTheme theme;
    private final FactoryConfirmDialog confirmDialog;

    @Nullable
    private FactoryLayout layout;
    @Nullable
    private String feedbackText;
    private boolean feedbackOk;
    private int feedbackTicks;

    /** 从仓库拖出的资源；与规则拖拽互斥。 */
    @Nullable
    private AEKey dragResourceKey;
    /** 左键/右键在仓库格上按下但还没确定是点击还是拖拽。 */
    @Nullable
    private AEKey pendingStorageKey;
    private int pendingStorageButton;
    private boolean pendingStorageShift;
    private boolean dragActive;
    private double dragStartX;
    private double dragStartY;
    private double dragMouseX;
    private double dragMouseY;

    /** 从 IO 规则卡拖出的规则；用于拖回仓库删除。 */
    @Nullable
    private FactoryMachineStack dragRuleStack;
    @Nullable
    private IoSide dragRuleSide;
    @Nullable
    private ResourceSpec dragRuleSpec;

    /**
     * @param screen 工作台
     * @param font   字体
     * @param theme  主题
     */
    public FactoryEditController(FactoryScreen screen, Font font, FactoryGuiTheme theme) {
        this.screen = screen;
        this.font = font;
        this.theme = theme;
        this.confirmDialog = new FactoryConfirmDialog(font, theme);
    }

    /**
     * 布局变化时重新绑定。
     *
     * @param layout 新布局
     */
    public void rebind(FactoryLayout layout) {
        this.layout = layout;
        this.confirmDialog.rebind(layout);
    }

    /**
     * 快照更新后刷新确认浮层并让拖拽源跟上新对象。
     *
     * @param hasFactory 玩家是否已有工厂
     */
    public void onTable(boolean hasFactory) {
        if (!hasFactory) {
            this.confirmDialog.close();
        }
        if (this.dragRuleStack != null && this.dragRuleSpec != null) {
            FactoryMachineStack refreshed = findStack(this.dragRuleStack.anchorId());
            if (refreshed == null) {
                cancelDrag();
            } else {
                this.dragRuleStack = refreshed;
            }
        }
    }

    /**
     * 按锚点 id 找当前的行。
     *
     * <p>快照刷新后旧对象就过期了，拖拽源必须换成新对象，否则读到的是旧成员列表。</p>
     *
     * @param anchorId 锚点机器 id
     * @return 行；已经被删时不在这里时为 {@code null}
     */
    @Nullable
    private FactoryMachineStack findStack(java.util.UUID anchorId) {
        for (FactoryMachineStack stack : this.screen.state.tableStacks) {
            if (stack.anchorId().equals(anchorId)) {
                return stack;
            }
        }
        for (FactoryMachineStack stack : this.screen.state.libraryStacks) {
            if (stack.anchorId().equals(anchorId)) {
                return stack;
            }
        }
        return null;
    }

    /** 关闭浮层与拖拽。 */
    public void closeAll() {
        this.confirmDialog.close();
        cancelDrag();
    }

    /** 是否有关闭优先的浮层/拖拽，供 tooltip 与 JEI 判断遮挡。 */
    @Override
    public boolean isOverlayActive() {
        return this.confirmDialog.isOpen() || hasDragSource()
                || this.pendingStorageKey != null;
    }

    /** 是否正在拖资源（超过阈值）。 */
    @Override
    public boolean isDraggingResource() {
        return this.dragActive && this.dragResourceKey != null;
    }

    /** 有没有拖拽源（含尚未超过阈值的按下）。 */
    private boolean hasDragSource() {
        return this.dragResourceKey != null || this.dragRuleSpec != null;
    }

    /**
     * 处理点击。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @param button 鼠标键
     * @return 是否消费
     */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.confirmDialog.isOpen()) {
            return this.confirmDialog.mouseClicked(mouseX, mouseY, button);
        }
        // 拖拽进行中：后续点击只属于这次拖拽，不落到表格/仓库。
        if (hasDragSource() || this.pendingStorageKey != null) {
            return true;
        }
        // 仓库格：按下先记录，松开时再决定“普通存取”还是“拖到 IO 空卡”。
        if (button == 0 || button == 1) {
            FactoryStorageS2C.Entry entry = this.screen.storageEntryAt(mouseX, mouseY);
            if (entry != null) {
                beginStorageDrag(entry, button, mouseX, mouseY);
                return true;
            }
        }
        return false;
    }

    private void beginStorageDrag(FactoryStorageS2C.Entry entry, int button,
            double mouseX, double mouseY) {
        cancelDrag();
        this.pendingStorageKey = entry.key();
        this.pendingStorageButton = button;
        this.pendingStorageShift = Screen.hasShiftDown();
        // 只有左键拖拽表示“拿它去加规则”；右键仍按仓库手势处理。
        this.dragResourceKey = button == 0 ? entry.key() : null;
        this.dragActive = false;
        this.dragStartX = mouseX;
        this.dragStartY = mouseY;
        this.dragMouseX = mouseX;
        this.dragMouseY = mouseY;
    }

    /**
     * 拖拽中：更新坐标，超过 4px 阈值才显示“正在拖拽”。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @param button 鼠标键
     * @return 是否消费
     */
    public boolean mouseDragged(double mouseX, double mouseY, int button) {
        if (!hasDragSource() || button != 0) {
            return false;
        }
        this.dragMouseX = mouseX;
        this.dragMouseY = mouseY;
        if (!this.dragActive
                && (Math.abs(mouseX - this.dragStartX) > 4.0D
                        || Math.abs(mouseY - this.dragStartY) > 4.0D)) {
            this.dragActive = true;
        }
        return true;
    }

    /**
     * 松开鼠标：只有真正拖动过才结算；单击仓库格仍然执行原来的存取手势。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @param button 鼠标键
     * @return 是否消费
     */
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.dragResourceKey != null) {
            if (this.dragActive) {
                finishResourceDrag(mouseX, mouseY);
            } else {
                sendPendingStorageGesture();
            }
            cancelDrag();
            return true;
        }
        if (this.dragRuleSpec != null) {
            if (this.dragActive) {
                finishRuleDrag(mouseX, mouseY);
            }
            cancelDrag();
            return true;
        }
        if (this.pendingStorageKey != null && button == this.pendingStorageButton) {
            sendPendingStorageGesture();
            cancelDrag();
            return true;
        }
        return false;
    }

    private void finishResourceDrag(double mouseX, double mouseY) {
        FactoryScreenGeometry.IoDropTarget target = this.screen.ioDropTargetAt(mouseX, mouseY);
        if (target == null || this.dragResourceKey == null) {
            return;
        }
        sendRuleAction(FactoryRuleActionC2S.Action.ADD, target.stack(), target.side(),
                this.dragResourceKey, null, MatchMode.EXACT);
    }

    private void finishRuleDrag(double mouseX, double mouseY) {
        if (!this.screen.isInStorageGrid(mouseX, mouseY)
                || this.dragRuleStack == null || this.dragRuleSide == null
                || this.dragRuleSpec == null) {
            return;
        }
        sendRuleAction(FactoryRuleActionC2S.Action.REMOVE, this.dragRuleStack,
                this.dragRuleSide, this.dragRuleSpec.key(), null, null);
    }

    private void sendPendingStorageGesture() {
        if (this.pendingStorageKey == null
                || !(this.pendingStorageKey instanceof AEItemKey)) {
            return;
        }
        FactoryStorageActionC2S.Gesture gesture;
        if (this.pendingStorageShift) {
            gesture = this.pendingStorageButton == 0
                    ? FactoryStorageActionC2S.Gesture.SHIFT_LEFT
                    : FactoryStorageActionC2S.Gesture.SHIFT_RIGHT;
        } else {
            gesture = this.pendingStorageButton == 0
                    ? FactoryStorageActionC2S.Gesture.LEFT
                    : FactoryStorageActionC2S.Gesture.RIGHT;
        }
        PacketDistributor.sendToServer(new FactoryStorageActionC2S(
                this.screen.getMenu().containerId, this.pendingStorageKey, gesture));
    }

    /**
     * 开始拖一条 IO 规则；松开在仓库网格上时删除它。
     *
     * @param stack  机器行
     * @param side   输入/输出
     * @param spec   规则
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     */
    public void beginRuleDrag(FactoryMachineStack stack, IoSide side,
            ResourceSpec spec, double mouseX, double mouseY) {
        cancelDrag();
        this.dragRuleStack = stack;
        this.dragRuleSide = side;
        this.dragRuleSpec = spec;
        this.dragActive = false;
        this.dragStartX = mouseX;
        this.dragStartY = mouseY;
        this.dragMouseX = mouseX;
        this.dragMouseY = mouseY;
    }

    /**
     * 更新规则的面 / 匹配模式。
     *
     * @param stack 机器行
     * @param side  输入/输出
     * @param spec  规则
     * @param face  新面
     * @param mode  新匹配模式
     */
    public void updateRule(FactoryMachineStack stack, IoSide side, ResourceSpec spec,
            @Nullable Direction face, MatchMode mode) {
        sendRuleAction(FactoryRuleActionC2S.Action.UPDATE, stack, side, spec.key(), face, mode);
    }

    /**
     * 发一条规则动作。
     *
     * <p>只传锚点 id；规则写到哪台机器由服务端按 id 重新解析，客户端不传成员列表，
     * 避免「客户端说这组有 5 台」这种不可信输入。</p>
     *
     * @param action 动作
     * @param stack  机器行
     * @param side   输入/输出
     * @param key    资源
     * @param face   面，可为 {@code null}
     * @param mode   匹配模式，可为 {@code null}
     */
    private void sendRuleAction(FactoryRuleActionC2S.Action action, FactoryMachineStack stack,
            IoSide side, AEKey key, @Nullable Direction face, @Nullable MatchMode mode) {
        PacketDistributor.sendToServer(new FactoryRuleActionC2S(
                this.screen.getMenu().containerId, action, stack.anchorId(), side, key, face,
                mode == null ? MatchMode.EXACT : mode));
    }

    private void cancelDrag() {
        this.dragActive = false;
        this.dragResourceKey = null;
        this.pendingStorageKey = null;
        this.dragRuleStack = null;
        this.dragRuleSide = null;
        this.dragRuleSpec = null;
    }

    /**
     * 处理键盘。
     *
     * @param keyCode   键码
     * @param scanCode  扫描码
     * @param modifiers 修饰键
     * @return 是否消费
     */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.confirmDialog.isOpen()) {
            return this.confirmDialog.keyPressed(keyCode);
        }
        if ((hasDragSource() || this.pendingStorageKey != null)
                && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            cancelDrag();
            return true;
        }
        return false;
    }

    /**
     * 处理字符输入。
     *
     * @param codePoint 字符
     * @param modifiers 修饰键
     * @return 是否消费
     */
    public boolean charTyped(char codePoint, int modifiers) {
        return false;
    }

    /**
     * 处理滚轮。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @param delta  滚动量
     * @return 是否消费
     */
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        return hasDragSource() || this.pendingStorageKey != null
                || this.confirmDialog.isOpen();
    }

    /** 反馈计时。 */
    public void tick() {
        if (this.feedbackTicks > 0) {
            this.feedbackTicks--;
        }
    }

    /**
     * 画浮层和反馈；必须在表格/右区之后调用。
     *
     * @param graphics    画布
     * @param mouseX      鼠标 x
     * @param mouseY      鼠标 y
     * @param partialTick 渲染插值
     */
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.layout == null) {
            return;
        }
        drawFeedback(graphics);
        // 物品图标在 150、原版数量装饰在 200、仓库自定义数量在 300；浮层必须高于这些内容层。
        // 层级统一走 FactoryGuiLayer，tooltip 由原版画在 400，浮层打开时 tooltip 已被抑制。
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, FactoryGuiLayer.OVERLAY.z());
        this.confirmDialog.draw(graphics, mouseX, mouseY);
        graphics.pose().popPose();
        if (this.dragActive) {
            ItemStack icon = ItemStack.EMPTY;
            if (this.dragResourceKey != null) {
                icon = FactoryLabels.icon(this.dragResourceKey);
            } else if (this.dragRuleSpec != null) {
                icon = FactoryLabels.icon(this.dragRuleSpec.key());
            }
            if (icon.isEmpty()) {
                icon = new ItemStack(Items.BARRIER);
            }
            // renderItem 自带 150 偏移，这里再补差值，让幽灵净层级落在 DRAG。
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, FactoryGuiLayer.DRAG.z() - FactoryGuiLayer.ITEM.z());
            graphics.renderItem(icon, (int) this.dragMouseX - 8, (int) this.dragMouseY - 8);
            graphics.pose().popPose();
        }
    }

    /**
     * 处理服务端反馈。
     *
     * @param payload 反馈
     */
    public void onFeedback(FactoryEditFeedbackS2C payload) {
        this.showFeedback(payload.message(), payload.ok());
    }

    private void showFeedback(String message, boolean ok) {
        this.feedbackText = message;
        this.feedbackOk = ok;
        this.feedbackTicks = FEEDBACK_TICKS;
    }

    /**
     * 打开删除单台机器的确认框；有规则时写清规则数。
     *
     * @param machine 机器
     */
    public void confirmDeleteMachine(FactorySnapshotBuilder.MachineView machine) {
        if (this.layout == null) {
            return;
        }
        if (machine.ruleCount() == 0) {
            sendDeleteMachine(machine.machineId());
            return;
        }
        String message = "机器 [" + machine.machineId().toString().substring(0, 8) + "] 还有 " + machine.ruleCount()
                + " 条 IO 配置。\n删除后规则无法恢复。";
        this.confirmDialog.open("删除机器", message, "删除",
                () -> sendDeleteMachine(machine.machineId()),
                this.layout);
    }

    /**
     * 删除一台机器（界面确认后）。
     *
     * @param machineId 机器 id
     */
    private void sendDeleteMachine(java.util.UUID machineId) {
        PacketDistributor.sendToServer(new FactoryMachineActionC2S(
                this.screen.getMenu().containerId, machineId));
    }

    private void drawFeedback(GuiGraphics graphics) {
        if (this.feedbackText == null || this.feedbackTicks <= 0 || this.layout == null) {
            return;
        }
        FactoryLayout l = this.layout;
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.HEADER_BG, l.tableX, l.footerY, l.tableW,
                FactoryLayout.FOOTER_H);
        String text = this.font.plainSubstrByWidth(this.feedbackText, l.tableW - 4);
        graphics.drawString(this.font, text, l.tableX + 2, l.footerY + 3,
                this.feedbackOk ? this.theme.statusRun() : this.theme.error(), false);
    }
}
