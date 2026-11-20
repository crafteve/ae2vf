package io.github.crafteve.ae2vf.client.screen.terminal;

import java.util.List;
import java.util.function.Supplier;

import appeng.client.gui.Icon;
import appeng.client.gui.widgets.IconButton;

import io.github.crafteve.ae2vf.menu.PassiveIoViewMode;

import net.minecraft.network.chat.Component;

/**
 * 列表 / 卡片视图切换按钮：图标随当前视图变化，点击只切换客户端视图并发同步包。
 *
 * <p>用 AE2 现成的 {@code IconButton}，不另画底图；tooltip 说明点下去会切到哪种视图。</p>
 */
public final class PassiveIoViewButton extends IconButton {
    /** 当前视图来源；按钮每帧现查，切换后下一帧自动换图标。 */
    private final Supplier<PassiveIoViewMode> view;

    /**
     * @param view  当前视图
     * @param press 点击动作
     */
    public PassiveIoViewButton(Supplier<PassiveIoViewMode> view, OnPress press) {
        super(press);
        this.view = view;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 1) {
            // 两态按钮：右键与左键等价，走同一条按下路径（含音效与焦点）。
            return super.mouseClicked(mouseX, mouseY, 0);
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected Icon getIcon() {
        return this.view.get() == PassiveIoViewMode.LIST
                ? Icon.VIEW_MODE_STORED : Icon.VIEW_MODE_ALL;
    }

    /**
     * 取一条界面文案。
     *
     * @param key 语言键
     * @return 当前语言下的文本
     */
    private static String tr(String key) {
        return net.minecraft.network.chat.Component.translatable(key).getString();
    }

    @Override
    public List<Component> getTooltipMessage() {
        String next = tr(this.view.get() == PassiveIoViewMode.LIST
                ? "ae2vf.passive.view.tile" : "ae2vf.passive.view.list");
        return List.of(Component.translatable("ae2vf.passive.view.switch", next));
    }
}
