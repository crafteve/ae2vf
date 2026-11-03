package io.github.crafteve.idlefactory.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * 深色/浅色搜索框（跟随主题）。
 *
 * <p>复用原版 {@link EditBox} 的输入/选区/剪贴板逻辑，只是关掉原版边框、换成主题文字色，
 * 并在绘制后覆盖一条强调色光标（1.21.1 的 EditBox 把光标颜色写死成浅灰，没有 setter）。</p>
 */
public class FactorySearchEditBox extends EditBox {
    private final FactoryGuiTheme theme;
    /** 占位提示；聚焦或有内容时临时清掉，避免和输入文字叠在一起。 */
    private final Component hint;

    public FactorySearchEditBox(Font font, int x, int y, int width, int height, FactoryGuiTheme theme, Component hint) {
        super(font, x, y, width, height, Component.empty());
        this.theme = theme;
        this.hint = hint;
        setBordered(false);
        // AE2 的输入框底板是中等灰蓝，文字与提示都用亮色才看得清。
        setTextColor(0xFFFFFFFF);
        setTextColorUneditable(0xFFD8D9DF);
        setTextShadow(false);
        // 原版 hint 颜色写死成灰色，自己画亮色提示。
        setHint(Component.empty());
        setMaxLength(64);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 右键清空（AE2 终端同款习惯）
        if (button == 1 && isMouseOver(mouseX, mouseY)) {
            setValue("");
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);

        // 未聚焦且为空时画亮色占位提示；聚焦或有内容时不画，避免和光标 / 输入文字叠住。
        if (isVisible() && !isFocused() && getValue().isEmpty()) {
            Font font = Minecraft.getInstance().font;
            guiGraphics.drawString(font, this.hint.getString(), getX(), getY() + 1,
                    0xFFE6EEF8, false);
        }

        if (!isFocused() || !isVisible()) {
            return;
        }

        Font font = Minecraft.getInstance().font;
        String value = getValue();
        int caret = Math.min(getCursorPosition(), value.length());

        // 与原版无边框模式一致：文字从 getX() 开始，光标在文字宽度 - 1 处
        int caretX = getX() + font.width(value.substring(0, caret)) - 1;
        caretX = Math.max(getX() - 1, Math.min(caretX, getX() + getWidth() - 1));
        guiGraphics.fill(caretX, getY(), caretX + 1, getY() + 9, this.theme.accent());
    }
}
