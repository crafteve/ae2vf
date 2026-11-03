package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.List;
import java.util.function.Supplier;

import appeng.client.gui.Icon;
import appeng.client.gui.widgets.IconButton;

import io.github.crafteve.idlefactory.menu.FactoryPassiveIoView;

import net.minecraft.network.chat.Component;

/**
 * 列表 / 卡片视图切换按钮：图标随当前视图变化，点击只切换客户端视图并发同步包。
 *
 * <p>用 AE2 现成的 {@code IconButton}，不另画底图；tooltip 说明点下去会切到哪种视图。</p>
 */
final class FactoryPassiveIoViewButton extends IconButton {
    /** 当前视图来源；按钮每帧现查，切换后下一帧自动换图标。 */
    private final Supplier<FactoryPassiveIoView> view;

    /**
     * @param view  当前视图
     * @param press 点击动作
     */
    FactoryPassiveIoViewButton(Supplier<FactoryPassiveIoView> view, OnPress press) {
        super(press);
        this.view = view;
    }

    @Override
    protected Icon getIcon() {
        return this.view.get() == FactoryPassiveIoView.LIST
                ? Icon.VIEW_MODE_STORED : Icon.VIEW_MODE_ALL;
    }

    @Override
    public List<Component> getTooltipMessage() {
        String next = this.view.get() == FactoryPassiveIoView.LIST ? "磁贴视图" : "列表视图";
        return List.of(Component.literal("切换为" + next));
    }
}
