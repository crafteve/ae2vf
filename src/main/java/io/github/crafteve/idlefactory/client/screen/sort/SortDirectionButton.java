package io.github.crafteve.idlefactory.client.screen.sort;


import java.util.List;
import java.util.function.Supplier;

import appeng.api.config.SortDir;
import appeng.client.gui.Icon;
import appeng.client.gui.widgets.IconButton;

import net.minecraft.network.chat.Component;

/**
 * 排序方向按钮：升序显示向上箭头、降序显示向下箭头，点击在两者之间切换。
 *
 * <p>方向是全局的：四个比较尺子一起翻，和 AE2 终端的排序方向语义一致。</p>
 */
public final class SortDirectionButton extends IconButton {
    /** 当前方向来源；按钮每帧现查，切换后下一帧自动换图标。 */
    private final Supplier<SortDir> direction;
    private final SortPress press;

    /**
     * @param direction 当前方向
     * @param press     点击动作
     */
    public SortDirectionButton(Supplier<SortDir> direction, SortPress press) {
        super(button -> { });
        this.direction = direction;
        this.press = press;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 左键正向、右键反向：只处理右键会让左键点击落到空的 onPress 上，表现为按钮点不动。
        if ((button == 0 || button == 1) && this.active && this.visible
                && clicked(mouseX, mouseY)) {
            this.press.press(button == 1);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected Icon getIcon() {
        return this.direction.get() == SortDir.ASCENDING ? Icon.ARROW_UP : Icon.ARROW_DOWN;
    }

    @Override
    public List<Component> getTooltipMessage() {
        boolean ascending = this.direction.get() == SortDir.ASCENDING;
        return List.of(Component.translatable(ascending
                ? "idlefactory.sort.direction.ascending" : "idlefactory.sort.direction.descending"),
                Component.translatable("idlefactory.sort.direction.toggle"));
    }
}
