package io.github.crafteve.idlefactory.client.screen;

import org.jetbrains.annotations.Nullable;


import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 破坏性操作的确认浮层（当前用于删除产线）。
 *
 * <p>不带输入框；确认后立即关闭并执行回调，结果由服务端反馈显示在底部状态条。Esc / 取消关闭，
 * 回车确认。浮层外点击一律吞掉，避免误触背后的表格。</p>
 */
public final class ConfirmDialog {
    private final Font font;
    private final FactoryGuiTheme theme;

    private boolean open;
    private String title = "";
    private String message = "";
    private String confirmLabel = "确定";
    @Nullable
    private Runnable onConfirm;
    @Nullable
    private FactoryLayout layout;

    /**
     * @param font  字体
     * @param theme 主题
     */
    public ConfirmDialog(Font font, FactoryGuiTheme theme) {
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
     * @param layout       当前布局
     */
    public void open(String title, String message, String confirmLabel, Runnable onConfirm,
            FactoryLayout layout) {
        this.title = title;
        this.message = message;
        this.confirmLabel = confirmLabel;
        this.onConfirm = onConfirm;
        this.layout = layout;
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
     * 布局变化时更新引用。
     *
     * @param layout 新布局
     */
    public void rebind(FactoryLayout layout) {
        this.layout = layout;
    }

    /**
     * 画浮层。
     *
     * @param graphics 画布
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     */
    public void draw(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!this.open || this.layout == null) {
            return;
        }
        FactoryLayout l = this.layout;
        int x = l.confirmX();
        int y = l.confirmY();
        int w = FactoryLayout.CONFIRM_W;
        int h = FactoryLayout.CONFIRM_H;
        graphics.fill(l.panelX, l.panelY, l.panelX + l.panelW, l.panelY + l.panelH,
                0x90000000);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.DIVIDER_DARK, x + 3, y + 3, w, h);
        FactoryNineSlice.panel(graphics, x, y, w, h);
        graphics.drawString(this.font, this.title, x + 12, y + 10,
                this.theme.textPrimary(), false);

        int lineY = y + 28;
        for (String line : this.message.split("\n")) {
            graphics.drawString(this.font, this.font.plainSubstrByWidth(line, w - 24),
                    x + 12, lineY, this.theme.textDim(), false);
            lineY += 11;
        }

        int buttonY = y + h - 26;
        int confirmW = Math.max(48, this.font.width(this.confirmLabel) + 18);
        int cancelW = Math.max(40, this.font.width("取消") + 18);
        int confirmX = x + w - 12 - confirmW;
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
        if (!this.open || this.layout == null) {
            return false;
        }
        FactoryLayout l = this.layout;
        int x = l.confirmX();
        int y = l.confirmY();
        int w = FactoryLayout.CONFIRM_W;
        int h = FactoryLayout.CONFIRM_H;
        if (!inside(mouseX, mouseY, x, y, w, h)) {
            return true;
        }
        int buttonY = y + h - 26;
        int confirmW = Math.max(48, this.font.width(this.confirmLabel) + 18);
        int cancelW = Math.max(40, this.font.width("取消") + 18);
        int confirmX = x + w - 12 - confirmW;
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
