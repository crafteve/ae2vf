package io.github.crafteve.idlefactory.client.screen.terminal;

import io.github.crafteve.idlefactory.client.screen.FactoryGuiTheme;
import io.github.crafteve.idlefactory.client.screen.FactoryNineSlice;
import io.github.crafteve.idlefactory.network.payload.FactoryEditFeedbackS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTableS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTerminalTargetS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTerminalTargetsS2C;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

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

    private int left;
    private int top;
    private int width;
    private int height;

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
        // 基类无状态。
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
}
