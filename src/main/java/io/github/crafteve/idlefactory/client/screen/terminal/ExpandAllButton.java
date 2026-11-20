package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.List;
import java.util.function.BooleanSupplier;

import appeng.client.gui.Icon;
import appeng.client.gui.widgets.IconButton;

import net.minecraft.network.chat.Component;

/**
 * 左侧工具栏的「全部展开 / 全部收起」按钮。
 *
 * <p>全部展开时显示向下箭头（点一下收起），有收起项时显示向右箭头（点一下全展开）。图标沿用
 * AE2 自带的箭头，不新增贴图；右键与左键行为一致，避免玩家还要记哪边是哪个方向。</p>
 */
public final class ExpandAllButton extends IconButton {
    /** 当前是否全部展开。 */
    private final BooleanSupplier allExpanded;
    /** 点击后的切换动作。 */
    private final Runnable toggleAll;

    /**
     * @param allExpanded 当前是否全部展开
     * @param toggleAll   切换动作
     */
    public ExpandAllButton(BooleanSupplier allExpanded, Runnable toggleAll) {
        super(button -> { });
        this.allExpanded = allExpanded;
        this.toggleAll = toggleAll;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if ((button == 0 || button == 1) && this.active && this.visible && clicked(mouseX, mouseY)) {
            this.toggleAll.run();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected Icon getIcon() {
        return this.allExpanded.getAsBoolean() ? Icon.ARROW_DOWN : Icon.ARROW_RIGHT;
    }

    @Override
    public List<Component> getTooltipMessage() {
        boolean open = this.allExpanded.getAsBoolean();
        return List.of(Component.translatable(open
                ? "idlefactory.terminal.collapse_all" : "idlefactory.terminal.expand_all"));
    }
}
