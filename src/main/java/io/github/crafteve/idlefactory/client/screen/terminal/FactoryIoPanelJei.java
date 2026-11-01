package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEKey;

import io.github.crafteve.idlefactory.client.screen.FactoryLabels;
import io.github.crafteve.idlefactory.client.screen.FactoryScreen;
import io.github.crafteve.idlefactory.client.screen.FactoryUiText;
import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.factory.capability.CapabilityKind;
import io.github.crafteve.idlefactory.factory.io.IoSide;
import io.github.crafteve.idlefactory.factory.machine.MatchMode;
import io.github.crafteve.idlefactory.factory.machine.ResourceSpec;
import io.github.crafteve.idlefactory.factory.storage.AeKeySupport;
import io.github.crafteve.idlefactory.network.payload.FactoryRuleActionC2S;

import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 被动 IO 面板的 JEI 命中与拖入：机器图标、规则图标、输入 / 输出落点。
 *
 * <p>命中区全部从 {@link FactoryIoPanelRender} 的几何读：机器图标在行内，规则图标在
 * 展开卡里，落点用展开区的两段区域，和绘制共用同一套坐标。</p>
 */
final class FactoryIoPanelJei {
    private final FactoryIoPanel panel;
    private final FactoryIoPanelRender layout;

    /**
     * @param panel  所属面板
     * @param layout 同一面板的几何
     */
    FactoryIoPanelJei(FactoryIoPanel panel, FactoryIoPanelRender layout) {
        this.panel = panel;
        this.layout = layout;
    }

    /**
     * 鼠标下的 JEI 查询目标。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 目标或 {@code null}
     */
    @Nullable
    FactoryScreen.JeiTarget jeiTargetAt(double mouseX, double mouseY) {
        FactoryIoPanelRender.RowRect row = this.layout.rowAtPoint(mouseX, mouseY);
        if (row != null) {
            int iconX = this.layout.contentLeft() + 3;
            if (mouseX >= iconX && mouseX < iconX + FactoryIoPanelRender.ICON_SLOT) {
                FactorySnapshotBuilder.MachineView machine =
                        this.panel.visible.get(row.viewIndex());
                return this.itemTarget(FactoryLabels.blockStack(machine.blockId()), iconX,
                        row.y() + 5);
            }
            return null;
        }
        FactorySnapshotBuilder.MachineView machine = this.panel.selected();
        if (machine == null) {
            return null;
        }
        for (FactoryIoPanelRender.CardView card : this.layout.cardViews()) {
            if (card.empty()) {
                continue;
            }
            int iconX = card.x() + 2;
            if (mouseX >= iconX && mouseX < iconX + FactoryIoPanelRender.ICON_SLOT
                    && mouseY >= card.y() + 5
                    && mouseY < card.y() + 5 + FactoryIoPanelRender.ICON_SLOT) {
                List<ResourceSpec> rules = card.input() ? machine.inputs() : machine.outputs();
                if (card.ruleIndex() < rules.size()) {
                    return this.resourceTarget(rules.get(card.ruleIndex()).key(), iconX,
                            card.y() + 5);
                }
            }
        }
        return null;
    }

    /**
     * 接收 JEI 拖入；命中输入 / 输出段就加一条规则。
     *
     * @param key    资源
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 是否接收
     */
    boolean dropJeiKey(AEKey key, double mouseX, double mouseY) {
        FactorySnapshotBuilder.MachineView machine = this.panel.selected();
        if (machine == null) {
            return false;
        }
        for (FactoryTerminalDropArea area : this.jeiDropAreas()) {
            if (mouseX >= area.area().getX() && mouseX < area.area().getX() + area.area().getWidth()
                    && mouseY >= area.area().getY()
                    && mouseY < area.area().getY() + area.area().getHeight()) {
                PacketDistributor.sendToServer(new FactoryRuleActionC2S(this.panel.containerId,
                        FactoryRuleActionC2S.Action.ADD, machine.ref().id(), area.side(), key, null,
                        MatchMode.IGNORE_COMPONENTS));
                return true;
            }
        }
        return false;
    }

    /**
     * 当前可接收 JEI 拖拽的区域。
     *
     * @return 输入 / 输出两段；没展开时为空
     */
    List<FactoryTerminalDropArea> jeiDropAreas() {
        List<Rect2i> areas = this.layout.sectionAreas();
        if (areas.size() < 2) {
            return List.of();
        }
        return List.of(
                new FactoryTerminalDropArea(areas.get(0), IoSide.INPUT),
                new FactoryTerminalDropArea(areas.get(1), IoSide.OUTPUT));
    }

    @Nullable
    private FactoryScreen.JeiTarget itemTarget(ItemStack stack, int x, int y) {
        if (stack.isEmpty()) {
            return null;
        }
        return new FactoryScreen.JeiTarget(CapabilityKind.ITEM, stack,
                new Rect2i(x, y, FactoryIoPanelRender.ICON_SLOT,
                        FactoryIoPanelRender.ICON_SLOT));
    }

    private FactoryScreen.JeiTarget resourceTarget(AEKey key, int x, int y) {
        CapabilityKind kind = AeKeySupport.kind(key);
        ItemStack stack = kind == CapabilityKind.ENERGY ? ItemStack.EMPTY
                : FactoryUiText.iconStack(key);
        return new FactoryScreen.JeiTarget(kind, stack,
                new Rect2i(x, y, FactoryIoPanelRender.ICON_SLOT,
                        FactoryIoPanelRender.ICON_SLOT));
    }
}
