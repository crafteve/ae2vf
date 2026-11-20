package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.List;

import appeng.client.gui.Icon;
import appeng.client.gui.widgets.IconButton;

import net.minecraft.network.chat.Component;

/**
 * 被动 IO 的帮助占位按钮：图标与 AE2 的 OpenGuideButton 一致，暂时没有跳转。
 *
 * <p>AE2 的 {@code OpenGuideButton} 只在 {@code getHelpTopic()} 非空时显示；本模组还没
 * 有指南页，所以在指南页接入前先用这个按钮保证左侧按钮数量与设计稿一致。</p>
 */
public final class PassiveIoHelpButton extends IconButton {
    /**
     * @param press 点击动作；指南页接入前为空操作
     */
    public PassiveIoHelpButton(OnPress press) {
        super(press);
    }

    @Override
    protected Icon getIcon() {
        return Icon.HELP;
    }

    @Override
    public List<Component> getTooltipMessage() {
        return List.of(Component.literal("打开 AE 指南"), Component.literal("指南页待接入"));
    }
}
