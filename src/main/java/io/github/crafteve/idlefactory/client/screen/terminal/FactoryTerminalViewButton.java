package io.github.crafteve.idlefactory.client.screen.terminal;

import appeng.client.gui.Icon;
import appeng.client.gui.widgets.IconButton;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * 左侧按钮栏里的视图切换按钮：AE2 图标按钮，和排序 / 设置按钮同一条。
 *
 * <p>尺寸保持 {@link IconButton} 默认的 16×16：按钮底图是 16×16 的
 * {@code TOOLBAR_BUTTON_BACKGROUND}，改大只会让底图和图标错位。图标必须是 16×16 的
 * 工具栏字形，不能拿 {@code BACKGROUND_*} 这类整格底图当图标。</p>
 */
final class FactoryTerminalViewButton extends IconButton {
    /** 按钮图标。 */
    private final Icon icon;

    /**
     * @param icon    按钮图标
     * @param tooltip 悬停提示
     * @param onPress 点击动作
     */
    FactoryTerminalViewButton(Icon icon, String tooltip, Button.OnPress onPress) {
        super(onPress);
        this.icon = icon;
        this.setTooltip(Tooltip.create(Component.literal(tooltip)));
    }

    @Override
    protected Icon getIcon() {
        return this.icon;
    }
}
