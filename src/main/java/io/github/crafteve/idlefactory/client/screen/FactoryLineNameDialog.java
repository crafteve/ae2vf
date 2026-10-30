package io.github.crafteve.idlefactory.client.screen;

import java.util.function.BiConsumer;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.line.LineActions;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 新建 / 重命名共用的命名浮层。
 *
 * <p>它自己持有输入框但不把输入框挂进 {@code Screen} 的 widget 列表：工作台的自定义浮层画在
 * 表格之后，如果输入框是普通 widget，会被表格盖住。这里改为手动 render 和转发按键。</p>
 *
 * <p>保留字、重名、长度上限都由服务端判定；这里只拦“非空”和“无空白/控制字符”，错误由
 * {@link #setError(String)} 写进来。</p>
 */
public final class FactoryLineNameDialog {
    /** 浮层模式。 */
    public enum Mode {
        /** 新建产线。 */
        CREATE,
        /** 只改名字。 */
        RENAME
    }

    private final Font font;
    private final FactoryGuiTheme theme;
    private final BiConsumer<Mode, String> onConfirm;
    private final FactorySearchEditBox field;

    private boolean open;
    private boolean pending;
    private Mode mode = Mode.CREATE;
    @Nullable
    private String error;
    @Nullable
    private FactoryLayout layout;

    /**
     * @param font      字体
     * @param theme     主题
     * @param onConfirm 确认回调；参数是模式和已 trim 的名字
     */
    public FactoryLineNameDialog(Font font, FactoryGuiTheme theme, BiConsumer<Mode, String> onConfirm) {
        this.font = font;
        this.theme = theme;
        this.onConfirm = onConfirm;
        this.field = new FactorySearchEditBox(font, 0, 0, 120, 10, theme,
                Component.literal("产线名"));
        this.field.setMaxLength(LineActions.MAX_NAME_LENGTH);
        this.field.setFilter(value -> value.chars().noneMatch(ch ->
                Character.isWhitespace(ch) || Character.isISOControl(ch)));
    }

    /**
     * 打开浮层。
     *
     * @param mode    新建 / 重命名
     * @param initial 预填名字；新建时为 {@code null}
     * @param layout  当前布局
     */
    public void open(Mode mode, @Nullable String initial, FactoryLayout layout) {
        this.mode = mode;
        this.layout = layout;
        this.error = null;
        this.pending = false;
        this.open = true;
        String value = initial == null ? "" : initial;
        this.field.setValue(value);
        this.field.setCursorPosition(value.length());
        this.field.setHighlightPos(0);
        this.field.setFocused(true);
        positionField();
    }

    /** 关闭浮层。 */
    public void close() {
        this.open = false;
        this.pending = false;
        this.error = null;
        this.field.setFocused(false);
    }

    /** 是否打开。 */
    public boolean isOpen() {
        return this.open;
    }

    /**
     * 布局变化时重新定位输入框。
     *
     * @param layout 新布局
     */
    public void rebind(FactoryLayout layout) {
        this.layout = layout;
        if (this.open) {
            positionField();
        }
    }

    /**
     * 写入服务端返回的错误；同时解除“等待回包”状态。
     *
     * @param message 错误文案
     */
    public void setError(String message) {
        this.error = message;
        this.pending = false;
    }

    /** 输入框光标闪烁；当前版本由 EditBox 自己按渲染时间处理，无需额外推进。 */
    public void tick() {
        // 保留钩子：以后需要自绘闪烁时在这里推进状态。
    }

    /**
     * 画浮层。
     *
     * @param graphics 画布
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     * @param partialTick 渲染插值
     */
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!this.open || this.layout == null) {
            return;
        }
        FactoryLayout l = this.layout;
        int x = l.dialogX();
        int y = l.dialogY();
        int w = FactoryLayout.DIALOG_W;
        int h = FactoryLayout.DIALOG_H;

        // 蒙住整个面板，让浮层成为唯一可操作的区域。
        graphics.fill(l.panelX, l.panelY, l.panelX + l.panelW, l.panelY + l.panelH,
                0x90000000);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.DIVIDER_DARK, x + 3, y + 3, w, h);
        FactoryNineSlice.panel(graphics, x, y, w, h);

        String title = this.mode == Mode.CREATE ? "新建产线" : "重命名产线";
        graphics.drawString(this.font, title, x + 12, y + 10, this.theme.textPrimary(), false);
        String hint = "字母/数字/汉字，不能有空格";
        graphics.drawString(this.font, hint, x + w - 12 - this.font.width(hint), y + 10,
                this.theme.textDim(), false);

        int fieldX = x + 12;
        int fieldY = y + 30;
        int fieldW = w - 24;
        FactoryNineSlice.field(graphics, fieldX, fieldY, fieldW, 18);
        this.field.render(graphics, mouseX, mouseY, partialTick);

        if (this.error != null) {
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(this.error, w - 24),
                    x + 12, y + 54, this.theme.error(), false);
        }

        int buttonY = y + h - 26;
        String confirmLabel = this.mode == Mode.CREATE ? "创建" : "保存";
        int confirmW = Math.max(40, this.font.width(confirmLabel) + 18);
        int cancelW = Math.max(40, this.font.width("取消") + 18);
        int confirmX = x + w - 12 - confirmW;
        int cancelX = confirmX - 6 - cancelW;
        drawButton(graphics, cancelX, buttonY, cancelW, "取消", mouseX, mouseY,
                !this.pending);
        drawButton(graphics, confirmX, buttonY, confirmW, confirmLabel, mouseX, mouseY,
                !this.pending);
    }

    private void drawButton(GuiGraphics graphics, int x, int y, int w, String label,
            int mouseX, int mouseY, boolean enabled) {
        boolean hovered = enabled && mouseX >= x && mouseX < x + w
                && mouseY >= y && mouseY < y + 18;
        if (hovered) {
            FactoryNineSlice.buttonHover(graphics, x, y, w, 18);
        } else {
            FactoryNineSlice.button(graphics, x, y, w, 18);
        }
        graphics.drawString(this.font, label,
                x + (w - this.font.width(label)) / 2, y + 5,
                enabled ? this.theme.textPrimary() : this.theme.textDim(), false);
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
        if (!this.open || this.layout == null) {
            return false;
        }
        FactoryLayout l = this.layout;
        int x = l.dialogX();
        int y = l.dialogY();
        int w = FactoryLayout.DIALOG_W;
        int h = FactoryLayout.DIALOG_H;
        if (!inside(mouseX, mouseY, x, y, w, h)) {
            // 模态：浮层外的点击不穿透，避免误触表格/仓库。
            return true;
        }
        this.field.mouseClicked(mouseX, mouseY, button);
        int buttonY = y + h - 26;
        String confirmLabel = this.mode == Mode.CREATE ? "创建" : "保存";
        int confirmW = Math.max(40, this.font.width(confirmLabel) + 18);
        int cancelW = Math.max(40, this.font.width("取消") + 18);
        int confirmX = x + w - 12 - confirmW;
        int cancelX = confirmX - 6 - cancelW;
        if (inside(mouseX, mouseY, cancelX, buttonY, cancelW, 18)) {
            close();
            return true;
        }
        if (inside(mouseX, mouseY, confirmX, buttonY, confirmW, 18)) {
            confirm();
            return true;
        }
        return true;
    }

    /**
     * 处理键盘。浮层打开时吞掉所有按键，避免 E/Q 关闭界面或丢物品。
     *
     * @param keyCode   键码
     * @param scanCode  扫描码
     * @param modifiers 修饰键
     * @return 是否消费
     */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!this.open) {
            return false;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
            confirm();
            return true;
        }
        this.field.keyPressed(keyCode, scanCode, modifiers);
        return true;
    }

    /**
     * 处理字符输入。
     *
     * @param codePoint 字符
     * @param modifiers 修饰键
     * @return 是否消费
     */
    public boolean charTyped(char codePoint, int modifiers) {
        if (!this.open) {
            return false;
        }
        this.field.charTyped(codePoint, modifiers);
        return true;
    }

    private void confirm() {
        if (this.pending) {
            return;
        }
        String name = this.field.getValue().trim();
        if (name.isEmpty()) {
            this.error = "产线名不能为空";
            return;
        }
        this.error = null;
        this.pending = true;
        this.onConfirm.accept(this.mode, name);
    }

    private void positionField() {
        if (this.layout == null) {
            return;
        }
        int x = this.layout.dialogX();
        int y = this.layout.dialogY();
        int w = FactoryLayout.DIALOG_W;
        this.field.setX(x + 16);
        this.field.setY(y + 34);
        this.field.setWidth(w - 32);
        this.field.setHeight(10);
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width,
            int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
