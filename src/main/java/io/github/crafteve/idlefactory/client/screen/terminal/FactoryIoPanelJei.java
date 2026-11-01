package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEKey;

import io.github.crafteve.idlefactory.client.screen.FactoryLabels;
import io.github.crafteve.idlefactory.client.screen.FactoryScreen;
import io.github.crafteve.idlefactory.client.screen.FactoryUiText;
import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.factory.capability.CapabilityKind;
import io.github.crafteve.idlefactory.factory.machine.MatchMode;
import io.github.crafteve.idlefactory.factory.machine.ResourceSpec;
import io.github.crafteve.idlefactory.factory.storage.AeKeySupport;
import io.github.crafteve.idlefactory.network.payload.FactoryRuleActionC2S;

import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 被动 IO 面板的 JEI 命中与拖入：机器图标、槽位、输入 / 输出行。
 *
 * <p>命中区全部从 {@link FactoryIoPanelRender} 的几何读，和绘制共用同一套坐标。</p>
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
        int machineIndex = this.layout.machineAt(mouseX, mouseY);
        if (machineIndex >= 0 && machineIndex < this.panel.visible.size()) {
            int iconX = this.layout.contentLeft();
            FactorySnapshotBuilder.MachineView machine = this.panel.visible.get(machineIndex);
            return this.itemTarget(FactoryLabels.blockStack(machine.blockId()), iconX,
                    this.layout.listTop());
        }
        FactoryIoPanelRender.SlotHit hit = this.layout.slotAt(mouseX, mouseY);
        if (hit == null || hit.empty() || hit.machineIndex() >= this.panel.visible.size()) {
            return null;
        }
        List<ResourceSpec> rules = hit.input()
                ? this.panel.visible.get(hit.machineIndex()).inputs()
                : this.panel.visible.get(hit.machineIndex()).outputs();
        if (hit.ruleIndex() >= rules.size()) {
            return null;
        }
        Rect2i area = this.layout.slotArea(hit);
        return this.resourceTarget(rules.get(hit.ruleIndex()).key(), area.getX(), area.getY());
    }

    /**
     * 接收 JEI 拖入；命中槽位就按它的机器与方向加一条规则。
     *
     * @param key    资源
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 是否接收
     */
    boolean dropJeiKey(AEKey key, double mouseX, double mouseY) {
        FactoryIoPanelRender.SlotHit hit = this.layout.slotAt(mouseX, mouseY);
        if (hit == null || hit.machineIndex() >= this.panel.visible.size()) {
            return false;
        }
        FactorySnapshotBuilder.MachineView machine = this.panel.visible.get(hit.machineIndex());
        PacketDistributor.sendToServer(new FactoryRuleActionC2S(this.panel.containerId,
                FactoryRuleActionC2S.Action.ADD, machine.ref().id(),
                hit.input() ? io.github.crafteve.idlefactory.factory.io.IoSide.INPUT
                        : io.github.crafteve.idlefactory.factory.io.IoSide.OUTPUT,
                key, null, MatchMode.IGNORE_COMPONENTS));
        return true;
    }

    /**
     * 当前可接收 JEI 拖拽的区域。
     *
     * @return 每台机器输入 / 输出行各一块
     */
    List<FactoryTerminalDropArea> jeiDropAreas() {
        return this.layout.dropAreas();
    }

    @Nullable
    private FactoryScreen.JeiTarget itemTarget(ItemStack stack, int x, int y) {
        if (stack.isEmpty()) {
            return null;
        }
        return new FactoryScreen.JeiTarget(CapabilityKind.ITEM, stack, new Rect2i(x, y, 16, 16));
    }

    private FactoryScreen.JeiTarget resourceTarget(AEKey key, int x, int y) {
        CapabilityKind kind = AeKeySupport.kind(key);
        ItemStack stack = kind == CapabilityKind.ENERGY ? ItemStack.EMPTY
                : FactoryUiText.iconStack(key);
        return new FactoryScreen.JeiTarget(kind, stack, new Rect2i(x, y, 16, 16));
    }
}
