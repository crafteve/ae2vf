package io.github.crafteve.idlefactory.client.screen;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 破坏性操作的确认浮层（删除机器 / 删除节点）。
 *
 * <p>不带输入框；确认后立即关闭并执行回调，结果由服务端反馈显示。Esc / 取消关闭，回车确认。
 * 浮层外点击一律吞掉，避免误触背后的列表。</p>
 *
 * <p>几何分两部分：{@link Bounds} 是浮层自身的位置，{@link #backdrop()} 是要压暗的父面板。
 * 两者都由调用方（节点终端）直接给出屏幕矩形。</p>
 */
public final class FactoryConfirmDialog {
    /** 浮层尺寸。 */
    private static final int WIDTH = 240;
    private static final int HEIGHT = 84;

    /**
     * 一个矩形。
     *
     * @param x      左边缘
     * @param y      上边缘
     * @param width  宽
     * @param height 高
     */
    public record Bounds(int x, int y, int width, int height) {
    }

    private final Font font;
    private final FactoryGuiTheme theme;

    private boolean open;
    private String title = "";
    private String message = "";
    private String confirmLabel = "确定";
    @Nullable
    private Runnable onConfirm;
    @Nullable
    private Bounds dialog;
    @Nullable
    private Bounds backdrop;

    /**
     * @param font  字体
     * @param theme 主题
     */
    public FactoryConfirmDialog(Font font, FactoryGuiTheme theme) {
        this.font = font;
        this.theme = theme;
    }

    /**
     * 打开确认浮层。
     *
     * @param title        标题
     * @param message      说明；可用 {@code \n} 分行
     * @param confirmLabel 确认按钮文字
     * @param onConfirm    确认回调
     * @param dialog       浮层矩形
     * @param backdrop     要压暗的父面板
     */
    public void open(String title, String message, String confirmLabel, Runnable onConfirm,
            Bounds dialog, Bounds backdrop) {
        this.title = title;
        this.message = message;
        this.confirmLabel = confirmLabel;
        this.onConfirm = onConfirm;
        this.dialog = dialog;
        this.backdrop = backdrop;
        this.open = true;
    }

    /** 关闭。 */
    public void close() {
        this.open = false;
        this.onConfirm = null;
    }

    /** 是否打开。 */
    public boolean isOpen() {
        return this.open;
    }

    /**
     * 画浮层。
     *
     * @param graphics 画布
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     */
    public void draw(GuiGraphics graphics, int mouseX, int mouseY) {
        Bounds box = this.dialog;
        Bounds parent = this.backdrop;
        if (!this.open || box == null || parent == null) {
            return;
        }
        graphics.fill(parent.x(), parent.y(), parent.x() + parent.width(),
                parent.y() + parent.height(), 0x90000000);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.DIVIDER_DARK, box.x() + 3, box.y() + 3,
                box.width(), box.height());
        FactoryNineSlice.panel(graphics, box.x(), box.y(), box.width(), box.height());
        graphics.drawString(this.font, this.title, box.x() + 12, box.y() + 10,
                this.theme.textPrimary(), false);

        int lineY = box.y() + 28;
        for (String line : this.message.split("\n")) {
            graphics.drawString(this.font, this.font.plainSubstrByWidth(line, box.width() - 24),
                    box.x() + 12, lineY, this.theme.textDim(), false);
            lineY += 11;
        }

        int buttonY = box.y() + box.height() - 26;
        int confirmW = Math.max(48, this.font.width(this.confirmLabel) + 18);
        int cancelW = Math.max(40, this.font.width("取消") + 18);
        int confirmX = box.x() + box.width() - 12 - confirmW;
        int cancelX = confirmX - 6 - cancelW;
        drawButton(graphics, cancelX, buttonY, cancelW, "取消", false, mouseX, mouseY);
        drawButton(graphics, confirmX, buttonY, confirmW, this.confirmLabel, true,
                mouseX, mouseY);
    }

    private void drawButton(GuiGraphics graphics, int x, int y, int w, String label,
            boolean danger, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 18;
        if (hovered) {
            FactoryNineSlice.buttonHover(graphics, x, y, w, 18);
        } else {
            FactoryNineSlice.button(graphics, x, y, w, 18);
        }
        int color = danger ? this.theme.error() : this.theme.textPrimary();
        graphics.drawString(this.font, label,
                x + (w - this.font.width(label)) / 2, y + 5, color, false);
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
        Bounds box = this.dialog;
        if (!this.open || box == null) {
            return false;
        }
        if (!inside(mouseX, mouseY, box.x(), box.y(), box.width(), box.height())) {
            return true;
        }
        int buttonY = box.y() + box.height() - 26;
        int confirmW = Math.max(48, this.font.width(this.confirmLabel) + 18);
        int cancelW = Math.max(40, this.font.width("取消") + 18);
        int confirmX = box.x() + box.width() - 12 - confirmW;
        int cancelX = confirmX - 6 - cancelW;
        if (inside(mouseX, mouseY, cancelX, buttonY, cancelW, 18)) {
            close();
            return true;
        }
        if (inside(mouseX, mouseY, confirmX, buttonY, confirmW, 18)) {
            Runnable action = this.onConfirm;
            close();
            if (action != null) {
                action.run();
            }
            return true;
        }
        return true;
    }

    /**
     * 处理键盘。
     *
     * @param keyCode 键码
     * @return 是否消费
     */
    public boolean keyPressed(int keyCode) {
        if (!this.open) {
            return false;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
            Runnable action = this.onConfirm;
            close();
            if (action != null) {
                action.run();
            }
            return true;
        }
        return true;
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width,
            int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
