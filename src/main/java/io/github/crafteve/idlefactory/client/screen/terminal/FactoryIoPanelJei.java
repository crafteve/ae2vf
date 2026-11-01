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
 * <p>全部命中区都从 {@link FactoryIoPanelRender} 的几何读，和绘制使用同一套坐标；拖入
 * 直接发规则动作包，服务端重校验后回推机器表。</p>
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
        int x = this.layout.contentLeft();
        if (this.layout.inMachineList(mouseX, mouseY)) {
            int row = this.layout.machineRowAt(mouseY);
            int index = this.panel.firstRow + row;
            if (index >= 0 && index < this.panel.visible.size()
                    && mouseX < x + FactoryTerminalGeometry.ICON_SIZE) {
                return this.itemTarget(
                        FactoryLabels.blockStack(this.panel.visible.get(index).blockId()),
                        x, this.layout.machinesTop() + row * FactoryIoPanelRender.MACHINE_ROW);
            }
        }
        FactorySnapshotBuilder.MachineView machine = this.panel.selected();
        if (machine == null) {
            return null;
        }
        int top = this.layout.rulesTop();
        int sectionHeight = (this.layout.machinesBottom() - top) / 2;
        FactoryScreen.JeiTarget input = this.ruleTargetAt(machine.inputs(), x, top,
                sectionHeight, mouseX, mouseY);
        if (input != null) {
            return input;
        }
        return this.ruleTargetAt(machine.outputs(), x, top + sectionHeight, sectionHeight,
                mouseX, mouseY);
    }

    /**
     * 接收 JEI 拖入；命中输入 / 输出区就加一条规则。
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
     * @return 输入 / 输出两个区域；没选机器时为空
     */
    List<FactoryTerminalDropArea> jeiDropAreas() {
        if (this.panel.selected() == null) {
            return List.of();
        }
        int x = this.layout.contentLeft();
        int width = this.layout.contentWidth();
        int top = this.layout.rulesTop();
        int sectionHeight = (this.layout.machinesBottom() - top) / 2;
        return List.of(
                new FactoryTerminalDropArea(new Rect2i(x, top, width, sectionHeight),
                        IoSide.INPUT),
                new FactoryTerminalDropArea(
                        new Rect2i(x, top + sectionHeight, width, sectionHeight), IoSide.OUTPUT));
    }

    @Nullable
    private FactoryScreen.JeiTarget ruleTargetAt(List<ResourceSpec> rules, int x, int y,
            int sectionHeight, double mouseX, double mouseY) {
        int maxRows = Math.max(0,
                (sectionHeight - FactoryIoPanelRender.SECTION_HEADER)
                        / FactoryIoPanelRender.RULE_ROW);
        int limit = Math.min(maxRows, rules.size());
        for (int i = 0; i < limit; i++) {
            int rowY = y + FactoryIoPanelRender.SECTION_HEADER
                    + i * FactoryIoPanelRender.RULE_ROW;
            if (mouseX >= x + 1 && mouseX < x + 1 + FactoryTerminalGeometry.ICON_SIZE
                    && mouseY >= rowY && mouseY < rowY + FactoryTerminalGeometry.ICON_SIZE) {
                return this.resourceTarget(rules.get(i).key(), x + 1, rowY);
            }
        }
        return null;
    }

    @Nullable
    private FactoryScreen.JeiTarget itemTarget(ItemStack stack, int x, int y) {
        if (stack.isEmpty()) {
            return null;
        }
        return new FactoryScreen.JeiTarget(CapabilityKind.ITEM, stack,
                new Rect2i(x, y, FactoryTerminalGeometry.ICON_SIZE,
                        FactoryTerminalGeometry.ICON_SIZE));
    }

    private FactoryScreen.JeiTarget resourceTarget(AEKey key, int x, int y) {
        CapabilityKind kind = AeKeySupport.kind(key);
        ItemStack stack = kind == CapabilityKind.ENERGY ? ItemStack.EMPTY
                : FactoryUiText.iconStack(key);
        return new FactoryScreen.JeiTarget(kind, stack,
                new Rect2i(x, y, FactoryTerminalGeometry.ICON_SIZE,
                        FactoryTerminalGeometry.ICON_SIZE));
    }
}
