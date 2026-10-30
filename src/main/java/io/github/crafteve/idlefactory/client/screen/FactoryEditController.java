package io.github.crafteve.idlefactory.client.screen;

import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.factory.line.IoSide;
import io.github.crafteve.idlefactory.factory.machine.MachineRef;
import io.github.crafteve.idlefactory.factory.resource.MatchMode;
import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;
import io.github.crafteve.idlefactory.network.payload.FactoryEditFeedbackS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryLineActionC2S;
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

/**
 * 工作台编辑态的总控：产线下拉、命名/确认浮层、底部反馈。
 *
 * <p>它把界面动作翻译成 C2S 包，并且只根据服务端反馈与下一份快照更新界面；不做乐观写入。
 * 绘制与命中仍由各组件自己负责，本类只管顺序和状态。</p>
 *
 * <p>输入优先级：命名浮层 → 确认浮层 → 产线下拉 → 交给 {@link FactoryScreen} 的原有逻辑。</p>
 */
public final class FactoryEditController implements FactoryScreenInteraction {
    /** 底部反馈停留 tick。 */
    private static final int FEEDBACK_TICKS = 80;

    private final FactoryScreen screen;
    private final Font font;
    private final FactoryGuiTheme theme;
    private final FactoryLineMenu lineMenu;
    private final FactoryLineNameDialog nameDialog;
    private final FactoryConfirmDialog confirmDialog;

    @Nullable
    private FactoryLayout layout;
    @Nullable
    private String feedbackText;
    private boolean feedbackOk;
    private int feedbackTicks;
    /** 正在等待命名浮层的回包。 */
    private boolean awaitingName;
    /** 发给服务端后等待回包的动作；只用于选中/清空选中和错误分流。 */
    @Nullable
    private FactoryLineActionC2S.Action pendingAction;
    @Nullable
    private UUID pendingLineId;
    @Nullable
    private UUID renameLineId;

    /** 正在拖拽的机器；按下即记录，超过阈值才算拖拽。 */
    @Nullable
    private FactoryMachineStack dragStack;
    private boolean dragFromLibrary;
    private boolean dragActive;
    private double dragStartX;
    private double dragStartY;
    private double dragMouseX;
    private double dragMouseY;

    /** 从仓库拖出的资源；与机器拖拽互斥。 */
    @Nullable
    private ResourceKey dragResourceKey;
    /** 左键/右键在仓库格上按下但还没确定是点击还是拖拽。 */
    @Nullable
    private ResourceKey pendingStorageKey;
    private int pendingStorageButton;
    private boolean pendingStorageShift;
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
        this.lineMenu = new FactoryLineMenu(new FactoryLineMenu.Callbacks() {
            @Override
            public void select(@Nullable UUID lineId) {
                screen.selectLine(lineId);
            }

            @Override
            public void create() {
                openNameDialog(FactoryLineNameDialog.Mode.CREATE, null, null);
            }

            @Override
            public void rename(FactorySnapshotBuilder.LineView line) {
                openNameDialog(FactoryLineNameDialog.Mode.RENAME, line.name(), line.id());
            }

            @Override
            public void delete(FactorySnapshotBuilder.LineView line) {
                confirmDelete(line);
            }

            @Override
            public void setRunning(FactorySnapshotBuilder.LineView line, boolean running) {
                sendLineAction(running ? FactoryLineActionC2S.Action.START_LINE
                        : FactoryLineActionC2S.Action.PAUSE_LINE, line.id(), null, null);
            }
        });
        this.nameDialog = new FactoryLineNameDialog(font, theme, this::confirmName);
        this.confirmDialog = new FactoryConfirmDialog(font, theme);
    }

    /**
     * 布局变化时重新绑定。
     *
     * @param layout 新布局
     */
    public void rebind(FactoryLayout layout) {
        this.layout = layout;
        this.nameDialog.rebind(layout);
        this.confirmDialog.rebind(layout);
    }

    /**
     * 快照更新后刷新菜单数据。
     *
     * @param lines           产线列表
     * @param selectedLineId  当前选中
     * @param hasFactory      是否有工厂
     */
    public void onTable(List<FactorySnapshotBuilder.LineView> lines,
            @Nullable UUID selectedLineId, boolean hasFactory) {
        this.lineMenu.update(lines, selectedLineId, hasFactory);
        if (!hasFactory) {
            this.nameDialog.close();
            this.confirmDialog.close();
        }
        if (this.dragStack != null) {
            FactoryMachineStack refreshed = findStack(this.dragStack.anchorId());
            if (refreshed == null
                    || (!this.dragFromLibrary && refreshed.anchor().lineIds().isEmpty())) {
                cancelDrag();
            } else {
                this.dragStack = refreshed;
            }
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
     * 按锚点 id 在机器库组行里找当前的组。
     *
     * <p>快照刷新后旧的组对象就过期了，拖拽源必须换成新对象，否则拖拽里读的是旧成员列表。</p>
     *
     * @param anchorId 锚点机器 id
     * @return 组；已经被删或重新分组后不在这里时为 {@code null}
     */
    @Nullable
    private FactoryMachineStack findStack(UUID anchorId) {
        for (FactoryMachineStack stack : this.screen.state.libraryStacks) {
            if (stack.anchorId().equals(anchorId)) {
                return stack;
            }
        }
        return null;
    }

    /**
     * 打开/收起产线下拉。
     *
     * @param anchorX 下拉左边缘
     * @param anchorY 下拉上边缘
     */
    public void toggleLineMenu(int anchorX, int anchorY) {
        if (this.lineMenu.isOpen()) {
            this.lineMenu.close();
            return;
        }
        if (this.layout == null) {
            return;
        }
        this.lineMenu.open(this.layout, anchorX, anchorY, this.screen.lines(),
                this.screen.selectedLineId(), this.screen.hasFactory());
    }

    /** 关闭下拉与浮层。 */
    public void closeAll() {
        this.lineMenu.close();
        this.nameDialog.close();
        this.confirmDialog.close();
        this.awaitingName = false;
        this.pendingAction = null;
        this.pendingLineId = null;
        cancelDrag();
    }

    /** 产线下拉是否打开。 */
    public boolean isLineMenuOpen() {
        return this.lineMenu.isOpen();
    }

    /** 是否有关闭优先的浮层/下拉/拖拽，供 tooltip 与 JEI 判断遮挡。 */
    public boolean isOverlayActive() {
        return this.lineMenu.isOpen() || this.nameDialog.isOpen()
                || this.confirmDialog.isOpen() || hasDragSource()
                || this.pendingStorageKey != null;
    }

    /** 是否正在拖资源（超过阈值）。 */
    public boolean isDraggingResource() {
        return this.dragActive && this.dragResourceKey != null;
    }

    /** 是否正在拖拽机器组（超过阈值）。 */
    public boolean isDraggingMachine() {
        return this.dragActive && this.dragStack != null;
    }

    /**
     * 正在拖拽的机器名；多台时带台数，没有拖拽时为空串。
     *
     * <p>只带数字，不带 ×：× 在界面里专指删除按钮，混用会让拖拽文字看起来像「删 5 台」。</p>
     *
     * @return 拖拽文字
     */
    public String draggedMachineName() {
        if (this.dragStack == null) {
            return "";
        }
        String name = FactoryLabels.blockName(this.dragStack.blockId());
        return this.dragStack.stacked()
                ? name + " · " + this.dragStack.count() + " 台" : name;
    }

    /**
     * 这台机器是否是当前拖拽源所在组；用于把源卡片压暗。
     *
     * @param ref 机器引用
     * @return 是拖拽源所在组时为 {@code true}
     */
    public boolean isDragSource(MachineRef ref) {
        return this.dragStack != null && this.dragStack.members().stream()
                .anyMatch(member -> member.ref().equals(ref));
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
        if (this.nameDialog.isOpen()) {
            return this.nameDialog.mouseClicked(mouseX, mouseY, button);
        }
        if (this.confirmDialog.isOpen()) {
            return this.confirmDialog.mouseClicked(mouseX, mouseY, button);
        }
        if (this.lineMenu.isOpen()) {
            return this.lineMenu.mouseClicked(mouseX, mouseY, button);
        }
        // 拖拽进行中：后续点击只属于这次拖拽，不落到表格/仓库。
        if (hasDragSource() || this.pendingStorageKey != null) {
            return true;
        }
        // 机器库卡片 / 左表机器行都可以作为拖拽源；按下先记录，移动超过阈值才变拖拽。
        if (button == 0 && !hasDragSource()) {
            FactoryMachineStack library = this.screen.libraryStackAt(mouseX, mouseY);
            if (library != null) {
                beginMachineDrag(library, true, mouseX, mouseY);
                return true;
            }
            FactoryMachineStack row = this.screen.machineIconAt(mouseX, mouseY);
            if (row != null) {
                beginMachineDrag(row, false, mouseX, mouseY);
                return true;
            }
        }
        // 仓库格：按下先记录，松开时再决定“普通存取”还是“拖到 IO 空卡”。
        if ((button == 0 || button == 1) && !hasDragSource()) {
            FactoryStorageS2C.Entry entry = this.screen.storageEntryAt(mouseX, mouseY);
            if (entry != null) {
                beginStorageDrag(entry, button, mouseX, mouseY);
                return true;
            }
        }
        return false;
    }

    private boolean hasDragSource() {
        return this.dragStack != null || this.dragResourceKey != null
                || this.dragRuleSpec != null;
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
        if (this.dragStack != null && button == 0) {
            if (this.dragActive) {
                finishMachineDrag(mouseX, mouseY);
            }
            cancelDrag();
            return true;
        }
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

    private void beginMachineDrag(FactoryMachineStack stack, boolean fromLibrary,
            double mouseX, double mouseY) {
        cancelDrag();
        this.dragStack = stack;
        this.dragFromLibrary = fromLibrary;
        beginDragPosition(mouseX, mouseY);
    }

    private void beginStorageDrag(FactoryStorageS2C.Entry entry, int button,
            double mouseX, double mouseY) {
        cancelDrag();
        this.pendingStorageKey = entry.key();
        this.pendingStorageButton = button;
        this.pendingStorageShift = Screen.hasShiftDown();
        // 只有左键拖拽表示“拿它去加规则”；右键仍按仓库手势处理。
        this.dragResourceKey = button == 0 ? entry.key() : null;
        beginDragPosition(mouseX, mouseY);
    }

    private void beginDragPosition(double mouseX, double mouseY) {
        this.dragActive = false;
        this.dragStartX = mouseX;
        this.dragStartY = mouseY;
        this.dragMouseX = mouseX;
        this.dragMouseY = mouseY;
    }

    private void finishMachineDrag(double mouseX, double mouseY) {
        FactoryMachineStack source = this.dragStack;
        if (source == null) {
            return;
        }
        FactorySnapshotBuilder.MachineView anchor = source.anchor();
        if (this.dragFromLibrary) {
            if (this.screen.addMachineRowContains(mouseX, mouseY)) {
                UUID lineId = this.screen.selectedLineId();
                if (lineId == null) {
                    showFeedback("先选择一条产线", false);
                    return;
                }
                // 默认只放一台；Shift 拖全组（P2 接数量，这里先按整组逐台放）。
                if (Screen.hasShiftDown() && source.stacked()) {
                    for (FactorySnapshotBuilder.MachineView member : source.members()) {
                        sendLineAction(FactoryLineActionC2S.Action.PLACE_MACHINE, lineId,
                                member.ref().id(), null);
                    }
                    return;
                }
                sendLineAction(FactoryLineActionC2S.Action.PLACE_MACHINE, lineId,
                        anchor.ref().id(), null);
            }
            return;
        }
        if (this.screen.isInMachineLibrary(mouseX, mouseY)) {
            if (anchor.lineIds().isEmpty()) {
                showFeedback("这台机器没有产线", false);
                return;
            }
            // 拖的是组，拖回库也整组移出，跟行尾 × 的语义一致。
            sendLineAction(FactoryLineActionC2S.Action.REMOVE_STACK,
                    anchor.lineIds().get(0), source.anchorId(), null);
        }
    }

    private void finishResourceDrag(double mouseX, double mouseY) {
        FactoryScreenGeometry.IoDropTarget target = this.screen.ioDropTargetAt(mouseX, mouseY);
        if (target == null || this.dragResourceKey == null) {
            return;
        }
        FactoryMachineStack stack = target.stack();
        if (stack.anchor().lineIds().isEmpty()) {
            showFeedback("这台机器没有产线", false);
            return;
        }
        sendRuleAction(FactoryRuleActionC2S.Action.ADD, stack, target.side(),
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
                || this.pendingStorageKey.kind() != io.github.crafteve.idlefactory.factory.resource.ResourceKind.ITEM) {
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
     * <p>规则作用于整组，拖拽源记锚点所在的组。</p>
     *
     * @param stack  机器组
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
        beginDragPosition(mouseX, mouseY);
    }

    /**
     * 删除一条规则；作用于整组。
     *
     * @param stack 机器组
     * @param side  输入/输出
     * @param spec  规则
     */
    public void removeRule(FactoryMachineStack stack, IoSide side, ResourceSpec spec) {
        sendRuleAction(FactoryRuleActionC2S.Action.REMOVE, stack, side, spec.key(), null, null);
    }

    /**
     * 更新规则的面 / 匹配模式；作用于整组。
     *
     * @param stack 机器组
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
     * <p>只传锚点 id 和锚点所在产线；哪些机器算一组由服务端重新推，客户端不传成员列表，
     * 避免「客户端说这组有 5 台」这种不可信输入。</p>
     *
     * @param action 动作
     * @param stack  机器组
     * @param side   输入/输出
     * @param key    资源
     * @param face   面，可为 {@code null}
     * @param mode   匹配模式，可为 {@code null}
     */
    private void sendRuleAction(FactoryRuleActionC2S.Action action, FactoryMachineStack stack,
            IoSide side, ResourceKey key, @Nullable Direction face, @Nullable MatchMode mode) {
        FactorySnapshotBuilder.MachineView anchor = stack.anchor();
        if (anchor.lineIds().isEmpty()) {
            showFeedback("这台机器没有产线", false);
            return;
        }
        PacketDistributor.sendToServer(new FactoryRuleActionC2S(
                this.screen.getMenu().containerId, action, anchor.lineIds().get(0),
                stack.anchorId(), side, key, face,
                mode == null ? MatchMode.EXACT : mode));
    }

    private void cancelDrag() {
        this.dragStack = null;
        this.dragActive = false;
        this.dragFromLibrary = false;
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
        if (this.nameDialog.isOpen()) {
            return this.nameDialog.keyPressed(keyCode, scanCode, modifiers);
        }
        if (this.confirmDialog.isOpen()) {
            return this.confirmDialog.keyPressed(keyCode);
        }
        if (hasDragSource() || this.pendingStorageKey != null) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                cancelDrag();
                return true;
            }
        }
        if (this.lineMenu.isOpen()
                && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            this.lineMenu.close();
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
        return this.nameDialog.isOpen() && this.nameDialog.charTyped(codePoint, modifiers);
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
        if (hasDragSource() || this.pendingStorageKey != null) {
            return true;
        }
        if (this.nameDialog.isOpen() || this.confirmDialog.isOpen()) {
            return true;
        }
        if (this.lineMenu.isOpen()) {
            this.lineMenu.mouseScrolled(mouseX, mouseY, delta);
            return true;
        }
        return false;
    }

    /** 输入框光标与反馈计时。 */
    public void tick() {
        this.nameDialog.tick();
        if (this.feedbackTicks > 0) {
            this.feedbackTicks--;
        }
    }

    /**
     * 画下拉、浮层和反馈；必须在表格/右区之后调用。
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
        this.lineMenu.draw(graphics, this.font, this.theme, mouseX, mouseY);
        this.nameDialog.draw(graphics, mouseX, mouseY, partialTick);
        this.confirmDialog.draw(graphics, mouseX, mouseY);
        graphics.pose().popPose();
        if (this.dragActive) {
            ItemStack icon = ItemStack.EMPTY;
            if (this.dragStack != null) {
                icon = FactoryLabels.blockStack(this.dragStack.blockId());
            } else if (this.dragResourceKey != null) {
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
        FactoryLineActionC2S.Action action = this.pendingAction;
        UUID lineId = this.pendingLineId;
        this.pendingAction = null;
        this.pendingLineId = null;

        if (this.awaitingName) {
            this.awaitingName = false;
            if (payload.ok()) {
                this.nameDialog.close();
            } else {
                this.nameDialog.setError(payload.message());
                return;
            }
        }

        if (payload.ok() && action != null) {
            if (action == FactoryLineActionC2S.Action.CREATE_LINE
                    || action == FactoryLineActionC2S.Action.RENAME_LINE) {
                if (payload.lineId() != null) {
                    this.screen.selectLine(payload.lineId());
                }
            } else if (action == FactoryLineActionC2S.Action.DELETE_LINE
                    && lineId != null && lineId.equals(this.screen.selectedLineId())) {
                this.screen.selectLine(null);
            }
        }

        this.showFeedback(payload.message(), payload.ok());
    }

    private void showFeedback(String message, boolean ok) {
        this.feedbackText = message;
        this.feedbackOk = ok;
        this.feedbackTicks = FEEDBACK_TICKS;
    }

    private void openNameDialog(FactoryLineNameDialog.Mode mode, @Nullable String initial,
            @Nullable UUID lineId) {
        if (this.layout == null) {
            return;
        }
        this.renameLineId = lineId;
        this.nameDialog.open(mode, initial, this.layout);
    }

    private void confirmName(FactoryLineNameDialog.Mode mode, String name) {
        if (mode == FactoryLineNameDialog.Mode.CREATE) {
            this.pendingAction = FactoryLineActionC2S.Action.CREATE_LINE;
            this.pendingLineId = null;
            this.awaitingName = true;
            sendLineAction(FactoryLineActionC2S.Action.CREATE_LINE, null, null, name);
        } else {
            if (this.renameLineId == null) {
                this.nameDialog.setError("找不到要重命名的产线");
                return;
            }
            this.pendingAction = FactoryLineActionC2S.Action.RENAME_LINE;
            this.pendingLineId = this.renameLineId;
            this.awaitingName = true;
            sendLineAction(FactoryLineActionC2S.Action.RENAME_LINE, this.renameLineId, null,
                    name);
        }
    }

    /**
     * 把一组机器的台数改到目标值；滚轮停止后由界面层调用一次。
     *
     * @param anchorId 组锚点
     * @param target   目标台数；服务端会再夹紧
     */
    public void resizeStack(UUID anchorId, int target) {
        UUID lineId = this.screen.selectedLineId();
        if (lineId == null) {
            return;
        }
        sendLineAction(FactoryLineActionC2S.Action.RESIZE_STACK, lineId, anchorId, null, target);
    }

    /**
     * 把整组机器移出当前产线；机器和规则回到机器库。
     *
     * @param stack 机器组
     */
    public void removeStackFromLine(FactoryMachineStack stack) {
        UUID lineId = this.screen.selectedLineId();
        if (lineId == null) {
            return;
        }
        sendLineAction(FactoryLineActionC2S.Action.REMOVE_STACK, lineId,
                stack.anchorId(), null);
    }

    /**
     * 打开删除整组的确认框。
     *
     * <p>在产线里的组不允许直接删，只能先移出；这里只处理未编线的组。确认框写清台数和规则数，
     * 因为删除不可恢复。</p>
     *
     * @param stack 机器组
     */
    public void confirmDeleteStack(FactoryMachineStack stack) {
        if (this.layout == null) {
            return;
        }
        FactorySnapshotBuilder.MachineView anchor = stack.anchor();
        if (!anchor.lineIds().isEmpty()) {
            showFeedback("这组机器还在产线里，先移出产线再删除", false);
            return;
        }
        if (!stack.stacked()) {
            confirmDeleteMachine(anchor);
            return;
        }
        int rules = 0;
        for (FactorySnapshotBuilder.MachineView member : stack.members()) {
            rules += member.ruleCount();
        }
        if (rules == 0) {
            sendLineAction(FactoryLineActionC2S.Action.DELETE_STACK, null,
                    stack.anchorId(), null);
            return;
        }
        String message = "这一行有 " + stack.count() + " 台机器、还有 " + rules
                + " 条 IO 配置。\n删除后规则无法恢复。";
        this.confirmDialog.open("删除整组", message, "删除",
                () -> sendLineAction(FactoryLineActionC2S.Action.DELETE_STACK, null,
                        stack.anchorId(), null),
                this.layout);
    }

    /**
     * 打开删除单台机器的确认框；有规则时由调用方显示规则数。
     *
     * @param machine 机器
     */
    public void confirmDeleteMachine(FactorySnapshotBuilder.MachineView machine) {
        if (this.layout == null) {
            return;
        }
        if (machine.ruleCount() == 0) {
            sendLineAction(FactoryLineActionC2S.Action.DELETE_MACHINE, null,
                    machine.ref().id(), null);
            return;
        }
        String message = "机器 [" + machine.ref().shortId() + "] 还有 " + machine.ruleCount()
                + " 条 IO 配置。\n删除后规则无法恢复。";
        this.confirmDialog.open("删除机器", message, "删除",
                () -> sendLineAction(FactoryLineActionC2S.Action.DELETE_MACHINE, null,
                        machine.ref().id(), null),
                this.layout);
    }

    private void confirmDelete(FactorySnapshotBuilder.LineView line) {
        if (this.layout == null) {
            return;
        }
        this.confirmDialog.open("删除产线",
                "删除产线 [" + line.name() + "]？\n机器会回到机器库，规则保留。",
                "删除",
                () -> {
                    this.pendingAction = FactoryLineActionC2S.Action.DELETE_LINE;
                    this.pendingLineId = line.id();
                    sendLineAction(FactoryLineActionC2S.Action.DELETE_LINE, line.id(), null, null);
                },
                this.layout);
    }

    private void sendLineAction(FactoryLineActionC2S.Action action, @Nullable UUID lineId,
            @Nullable UUID machineId, @Nullable String name) {
        sendLineAction(action, lineId, machineId, name, 0);
    }

    /**
     * 发产线动作；整组改台数用这个重载带目标数量。
     *
     * @param action    动作
     * @param lineId    产线 id，可为 {@code null}
     * @param machineId 机器 id，可为 {@code null}
     * @param name      产线名，可为 {@code null}
     * @param count     目标台数；非 {@code RESIZE_STACK} 动作传 0
     */
    private void sendLineAction(FactoryLineActionC2S.Action action, @Nullable UUID lineId,
            @Nullable UUID machineId, @Nullable String name, int count) {
        PacketDistributor.sendToServer(new FactoryLineActionC2S(
                this.screen.getMenu().containerId, action, lineId, machineId, name, count));
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
