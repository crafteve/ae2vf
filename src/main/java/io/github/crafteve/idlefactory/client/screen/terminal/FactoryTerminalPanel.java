package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEKey;

import io.github.crafteve.idlefactory.client.screen.FactoryGuiTheme;
import io.github.crafteve.idlefactory.client.screen.FactoryNineSlice;
import io.github.crafteve.idlefactory.client.screen.FactoryScreen;
import io.github.crafteve.idlefactory.client.screen.FactoryUiText;
import io.github.crafteve.idlefactory.factory.capability.CapabilityKind;
import io.github.crafteve.idlefactory.factory.storage.AeKeySupport;
import io.github.crafteve.idlefactory.network.payload.FactoryEditFeedbackS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTableS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTerminalTargetS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTerminalTargetsS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTerminalSwitchC2S;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 工厂终端左面板的基类：生命周期、绘制、输入与滚动的统一骨架。
 *
 * <p>面板不直接持有屏幕：屏幕在自己的坐标系里把它绑定到一块矩形，面板只在这块矩形内
 * 画和收输入。这样两个视图各写各的内容，定位与事件分派只实现一次。</p>
 */
public abstract class FactoryTerminalPanel {
    /** 客户端实例；面板在屏幕构造期创建，拿得到。 */
    protected final Minecraft minecraft = Minecraft.getInstance();
    /** 当前字体。 */
    protected final Font font = this.minecraft.font;
    /** 当前主题；唯色源。 */
    protected final FactoryGuiTheme theme = FactoryGuiTheme.current();
    /** 所属菜单容器 id；左面板发动作包时带上，服务端用它拒绝过期界面。 */
    protected final int containerId;
    /** 模式切换按钮上的文字，例如「样板」。 */
    private final String switchLabel;

    private int left;
    private int top;
    private int width;
    private int height;
    private int snapshotTicker;
    @Nullable
    private Runnable focusSink;

    /**
     * @param containerId 所属菜单容器 id
     * @param switchLabel 模式切换按钮文字；按钮把界面切到另一个视图
     */
    protected FactoryTerminalPanel(int containerId, String switchLabel) {
        this.containerId = containerId;
        this.switchLabel = switchLabel;
    }

    /**
     * 设置「面板抢到键盘焦点」时的回调；屏幕用它清掉 AE2 搜索框的焦点。
     *
     * @param focusSink 回调；传 {@code null} 表示不需要
     */
    public final void setFocusSink(@Nullable Runnable focusSink) {
        this.focusSink = focusSink;
    }

    /**
     * 请求把键盘焦点从 AE2 控件挪到面板。
     */
    protected final void takeFocus() {
        if (this.focusSink != null) {
            this.focusSink.run();
        }
    }

    /**
     * 绑定面板矩形；由屏幕在 {@code init()} 里调用，缩放时重新绑定。
     *
     * @param left   左边界
     * @param top    上边界
     * @param width  宽度
     * @param height 高度
     */
    public final void bind(int left, int top, int width, int height) {
        this.left = left;
        this.top = top;
        this.width = width;
        this.height = height;
        // 打开与缩放时立刻拉一次；之后每 20 tick 由 tick() 刷新。
        this.snapshotTicker = 0;
        this.requestSnapshot();
    }

    /** 左边界。 */
    public final int left() {
        return this.left;
    }

    /** 上边界。 */
    public final int top() {
        return this.top;
    }

    /** 宽度。 */
    public final int width() {
        return this.width;
    }

    /** 高度。 */
    public final int height() {
        return this.height;
    }

    /** 右边界（不含）。 */
    public final int right() {
        return this.left + this.width;
    }

    /** 下边界（不含）。 */
    public final int bottom() {
        return this.top + this.height;
    }

    /**
     * 鼠标是否在面板内。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @return 命中时为 {@code true}
     */
    public final boolean isMouseOver(double mouseX, double mouseY) {
        return mouseX >= this.left && mouseX < this.right()
                && mouseY >= this.top && mouseY < this.bottom();
    }

    /**
     * 画面板底与标题条；屏幕的 {@code drawBG} 在画 AE 终端前调用。
     *
     * @param graphics 绘制器
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     */
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY) {
        FactoryNineSlice.panel(graphics, this.left, this.top, this.width, this.height);
        FactoryNineSlice.header(graphics, this.left + 3, this.top + 3,
                this.width - 6, FactoryTerminalGeometry.HEADER_HEIGHT - 2);
        graphics.drawString(this.font, this.title(), this.left + 6, this.top + 6,
                FactoryTerminalGeometry.TEXT_COLOR, false);
        graphics.drawString(this.font, this.subtitle(), this.left + 6,
                this.top + FactoryTerminalGeometry.HEADER_HEIGHT + 3,
                FactoryTerminalGeometry.TEXT_MUTED, false);
        // 模式切换按钮放在标题右侧：两个视图各自只需要一个「去另一边」的入口。
        int buttonWidth = this.switchButtonWidth();
        int buttonLeft = this.switchButtonLeft();
        FactoryNineSlice.button(graphics, buttonLeft, this.top + 3, buttonWidth,
                FactoryTerminalGeometry.HEADER_HEIGHT - 4);
        graphics.drawString(this.font, this.switchLabel,
                buttonLeft + (buttonWidth - this.font.width(this.switchLabel)) / 2,
                this.top + 5, FactoryTerminalGeometry.TEXT_COLOR, false);
    }

    /**
     * 模式切换按钮宽度。
     *
     * @return 宽度
     */
    private int switchButtonWidth() {
        return Math.max(24, this.font.width(this.switchLabel) + 8);
    }

    /**
     * 模式切换按钮左边界；避开终端按钮栏占用的右侧一条。
     *
     * @return 左边界
     */
    private int switchButtonLeft() {
        return this.right() - FactoryTerminalGeometry.TOOLBAR_RESERVE
                - FactoryTerminalGeometry.CONTENT_PADDING - this.switchButtonWidth();
    }

    /**
     * 鼠标是否点在模式切换按钮上。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @return 命中时为 {@code true}
     */
    protected final boolean inSwitchButton(double mouseX, double mouseY) {
        int buttonLeft = this.switchButtonLeft();
        int buttonTop = this.top + 3;
        return mouseX >= buttonLeft && mouseX < buttonLeft + this.switchButtonWidth()
                && mouseY >= buttonTop
                && mouseY < buttonTop + FactoryTerminalGeometry.HEADER_HEIGHT - 4;
    }

    /**
     * 面板顶层绘制；默认什么都不画，用于 tooltip 等必须盖在槽位上的内容。
     *
     * @param graphics 绘制器
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     */
    public void renderForeground(GuiGraphics graphics, int mouseX, int mouseY) {
        // 基类没有浮层。
    }

    /** 面板标题。 */
    protected abstract String title();

    /** 面板副标题；默认空。 */
    protected String subtitle() {
        return "";
    }

    /**
     * 鼠标点击；坐标系是屏幕绝对坐标。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @param button 按键
     * @return 是否消费该事件
     */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.inSwitchButton(mouseX, mouseY)) {
            PacketDistributor.sendToServer(new FactoryTerminalSwitchC2S(this.containerId));
            return true;
        }
        return false;
    }

    /**
     * 鼠标释放。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @param button 按键
     * @return 是否消费该事件
     */
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return false;
    }

    /**
     * 鼠标拖拽。
     *
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     * @param button   按键
     * @param dragX    水平位移
     * @param dragY    垂直位移
     * @return 是否消费该事件
     */
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX,
            double dragY) {
        return false;
    }

    /**
     * 滚轮。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @param deltaY 滚动量
     * @return 是否消费该事件
     */
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaY) {
        return false;
    }

    /**
     * 键盘按下；面板优先于 AE 终端处理。
     *
     * @param keyCode   键码
     * @param scanCode  扫描码
     * @param modifiers 修饰键
     * @return 是否消费该事件
     */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return false;
    }

    /**
     * 字符输入。
     *
     * @param codePoint 字符
     * @param modifiers 修饰键
     * @return 是否消费该事件
     */
    public boolean charTyped(char codePoint, int modifiers) {
        return false;
    }

    /** 每 tick 一次；默认没有状态要推进。 */
    public void tick() {
        // 每 20 tick 拉一次服务端快照；机器状态、规则与目标都靠它刷新。
        if (++this.snapshotTicker >= 20) {
            this.snapshotTicker = 0;
            this.requestSnapshot();
        }
    }

    /**
     * 请求服务端数据；默认什么都不发，由具体面板决定请求什么。
     */
    protected void requestSnapshot() {
        // 基类没有要拉的数据。
    }

    /**
     * 收机器表。
     *
     * @param payload 机器表
     */
    public void acceptTable(FactoryTableS2C payload) {
        // 基类不使用机器表。
    }

    /**
     * 收编辑反馈。
     *
     * @param payload 反馈
     */
    public void acceptEditFeedback(FactoryEditFeedbackS2C payload) {
        // 基类不显示反馈。
    }

    /**
     * 收主动目标列表。
     *
     * @param payload 目标列表
     */
    public void acceptTargets(FactoryTerminalTargetsS2C payload) {
        // 基类不使用目标列表。
    }

    /**
     * 收单个目标详情。
     *
     * @param payload 目标详情
     */
    public void acceptTargetDetail(FactoryTerminalTargetS2C payload) {
        // 基类不使用目标详情。
    }

    /**
     * 鼠标下的 JEI 查询目标；默认没有。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 目标或 {@code null}
     */
    @Nullable
    public FactoryScreen.JeiTarget jeiTargetAt(double mouseX, double mouseY) {
        return null;
    }

    /**
     * 接收从 JEI 拖进来的资源；默认不接收。
     *
     * @param key    资源
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 是否接收
     */
    public boolean dropJeiKey(AEKey key, double mouseX, double mouseY) {
        return false;
    }

    /**
     * 可接收 JEI 拖拽的区域；默认没有。
     *
     * @return 区域列表
     */
    public List<FactoryTerminalDropArea> jeiDropAreas() {
        return List.of();
    }

    /**
     * 把一个物品栈包装成 JEI 目标。
     *
     * @param stack 物品
     * @param x     图标左上角 x
     * @param y     图标左上角 y
     * @return 目标；空栈返回 {@code null}
     */
    @Nullable
    protected final FactoryScreen.JeiTarget itemTarget(ItemStack stack, int x, int y) {
        if (stack.isEmpty()) {
            return null;
        }
        return new FactoryScreen.JeiTarget(CapabilityKind.ITEM, stack,
                new Rect2i(x, y, FactoryTerminalGeometry.ICON_SIZE,
                        FactoryTerminalGeometry.ICON_SIZE));
    }

    /**
     * 把一个资源 key 包装成 JEI 目标；FE 用独立配料，所以栈留空。
     *
     * @param key 资源
     * @param x   图标左上角 x
     * @param y   图标左上角 y
     * @return 目标
     */
    protected final FactoryScreen.JeiTarget resourceTarget(AEKey key, int x, int y) {
        CapabilityKind kind = AeKeySupport.kind(key);
        ItemStack stack = kind == CapabilityKind.ENERGY ? ItemStack.EMPTY
                : FactoryUiText.iconStack(key);
        return new FactoryScreen.JeiTarget(kind, stack,
                new Rect2i(x, y, FactoryTerminalGeometry.ICON_SIZE,
                        FactoryTerminalGeometry.ICON_SIZE));
    }
}
