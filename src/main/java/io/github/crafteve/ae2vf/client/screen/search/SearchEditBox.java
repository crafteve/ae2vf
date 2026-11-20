package io.github.crafteve.ae2vf.client.screen.search;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * 终端里的输入框：搜索框与节点改名框共用。
 *
 * <p>复用原版 {@link EditBox} 的输入/选区/剪贴板逻辑，只是关掉原版边框、换成自己的文字色，
 * 并在绘制后覆盖一条强调色光标（1.21.1 的 EditBox 把光标颜色写死成浅灰，没有 setter）。</p>
 */
public class SearchEditBox extends EditBox {
    /** 光标与选中描边的强调色。 */
    private static final int ACCENT = 0xFF33445C;
    /** 不可编辑时的文字色。 */
    private static final int UNEDITABLE_TEXT = 0xFFD8D9DF;
    /** 占位提示；聚焦或有内容时临时清掉，避免和输入文字叠在一起。 */
    private final Component hint;
    /** 提示文字颜色。 */
    private final int hintColor;

    public SearchEditBox(Font font, int x, int y, int width, int height, Component hint) {
        this(font, x, y, width, height, hint, 0xFFFFFFFF, 0xFFE6EEF8);
    }

    /**
     * @param font      字体
     * @param x         左边缘
     * @param y         上边缘
     * @param width     宽
     * @param height    高
     * @param hint      占位提示
     * @param textColor 输入文字颜色
     * @param hintColor 占位提示颜色
     */
    public SearchEditBox(Font font, int x, int y, int width, int height,
            Component hint, int textColor, int hintColor) {
        super(font, x, y, width, height, Component.empty());
        this.hint = hint;
        this.hintColor = hintColor;
        setBordered(false);
        // AE2 的输入框底板是中等灰蓝，搜索框文字与提示都用亮色才看得清；
        // 节点改名贴在浅色节点行上，改用黑字，避免和底色糊在一起。
        setTextColor(textColor);
        setTextColorUneditable(UNEDITABLE_TEXT);
        setTextShadow(false);
        // 原版 hint 颜色写死成灰色，自己画可控颜色的提示。
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
                    this.hintColor, false);
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
        guiGraphics.fill(caretX, getY(), caretX + 1, getY() + 9, ACCENT);
    }
}
