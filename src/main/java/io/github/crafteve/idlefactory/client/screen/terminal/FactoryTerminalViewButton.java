package io.github.crafteve.idlefactory.client.screen.terminal;

import appeng.client.gui.Icon;
import appeng.client.gui.widgets.IconButton;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * 左侧按钮栏里的视图切换按钮：AE2 图标按钮，和排序 / 设置按钮同一条。
 *
 * <p>图标由调用方给：合成视图显示「去样板」，样板视图显示「去合成」。</p>
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
        this.setWidth(20);
        this.setHeight(20);
        this.setTooltip(Tooltip.create(Component.literal(tooltip)));
    }

    @Override
    protected Icon getIcon() {
        return this.icon;
    }
}
