package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEKey;

import io.github.crafteve.idlefactory.client.screen.FactoryLabels;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryPassiveIoLayout.BlockBox;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryPassiveIoLayout.Hit;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryPassiveIoLayout.Viewport;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryPassiveIoModel.BlockView;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryPassiveIoModel.RuleRow;
import io.github.crafteve.idlefactory.factory.capability.CapabilityKind;
import io.github.crafteve.idlefactory.factory.storage.AeKeySupport;
import io.github.crafteve.idlefactory.menu.FactoryPassiveIoView;

import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;

/**
 * 被动 IO 内容区的 JEI 目标：R/U 查询区域与拖拽新建区域。
 *
 * <p>两类目标都从同一份排版算出来：拖拽目标是每台机器的虚线新增行 / 虚线新增磁贴，查询目标是
 * 机器图标与规则图标。几何不在插件里重算，插件只负责把目标交给 JEI。</p>
 */
public final class FactoryPassiveIoJeiTargets {
    private FactoryPassiveIoJeiTargets() {
        // 纯静态目标构造
    }

    /**
     * 一条 JEI 拖拽目标。
     *
     * @param block 机器块下标
     * @param area  虚线新增行 / 磁贴的屏幕区域
     */
    public record AddTarget(int block, Rect2i area) {
    }

    /**
     * 一个 JEI 查询目标。
     *
     * @param kind  资源类型
     * @param stack 图标物品（流体用桶、FE 用红石）
     * @param area  图标区域
     */
    public record JeiTarget(CapabilityKind kind, ItemStack stack, Rect2i area) {
    }

    /**
     * 构造全部拖拽目标。
     *
     * @param layout 当前排版
     * @param scroll 当前滚动
     * @return 每台机器一条
     */
    static List<AddTarget> addTargets(FactoryPassiveIoLayout layout, int scroll) {
        List<AddTarget> targets = new ArrayList<>();
        Viewport viewport = layout.viewport();
        for (BlockBox box : layout.boxes()) {
            if (layout.view() == FactoryPassiveIoView.LIST) {
                int x = FactoryPassiveIoLayout.rowAreaX(viewport) + FactoryPassiveIoLayout.PAD;
                int y = FactoryPassiveIoLayout.screenY(viewport, box.addTop(), scroll);
                targets.add(new AddTarget(box.index(), new Rect2i(x, y,
                        FactoryPassiveIoLayout.contentRight(viewport) - x,
                        FactoryPassiveIoLayout.ADD_ROW_H)));
            } else {
                int add = box.ruleCount();
                int x = FactoryPassiveIoLayout.tileX(viewport, add % 2);
                int y = FactoryPassiveIoLayout.tileRowY(viewport, box, add / 2, scroll);
                targets.add(new AddTarget(box.index(), new Rect2i(x, y,
                        FactoryPassiveIoLayout.TILE_W, FactoryPassiveIoLayout.TILE_H)));
            }
        }
        return targets;
    }

    /**
     * 鼠标下的查询目标。
     *
     * @param layout 当前排版
     * @param blocks 模型块
     * @param scroll 当前滚动
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 目标；没有物品时为 {@code null}
     */
    @Nullable
    static JeiTarget targetAt(FactoryPassiveIoLayout layout, List<BlockView> blocks, int scroll,
            double mouseX, double mouseY) {
        Hit hit = layout.hit(mouseX, mouseY, scroll);
        if (hit == null || hit.kind() == FactoryPassiveIoLayout.Kind.ADD) {
            return null;
        }
        BlockView block = blocks.get(hit.block());
        if (hit.kind() == FactoryPassiveIoLayout.Kind.MACHINE) {
            ItemStack stack = FactoryLabels.blockStack(block.machine().blockId());
            return stack.isEmpty() ? null
                    : new JeiTarget(CapabilityKind.ITEM, stack, machineIconArea(layout, hit, scroll));
        }
        RuleRow rule = block.rules().get(hit.rule());
        AEKey key = rule.spec().key();
        return new JeiTarget(AeKeySupport.kind(key), FactoryLabels.icon(key),
                ruleIconArea(layout, hit, scroll));
    }

    private static Rect2i machineIconArea(FactoryPassiveIoLayout layout, Hit hit, int scroll) {
        Viewport viewport = layout.viewport();
        BlockBox box = layout.boxes().get(hit.block());
        int x = FactoryPassiveIoLayout.rowAreaX(viewport) + FactoryPassiveIoLayout.PAD;
        int y = FactoryPassiveIoLayout.screenY(viewport, box.top(), scroll) + 1;
        return new Rect2i(x, y, FactoryPassiveIoLayout.SLOT, FactoryPassiveIoLayout.SLOT);
    }

    private static Rect2i ruleIconArea(FactoryPassiveIoLayout layout, Hit hit, int scroll) {
        Viewport viewport = layout.viewport();
        BlockBox box = layout.boxes().get(hit.block());
        if (layout.view() == FactoryPassiveIoView.LIST) {
            int y = FactoryPassiveIoLayout.rowY(viewport, box, hit.rule(), scroll);
            return new Rect2i(FactoryPassiveIoLayout.slotX(viewport), y,
                    FactoryPassiveIoLayout.SLOT, FactoryPassiveIoLayout.SLOT);
        }
        int x = FactoryPassiveIoLayout.tileX(viewport, hit.rule() % 2)
                + FactoryPassiveIoLayout.TILE_INSET;
        int y = FactoryPassiveIoLayout.tileRowY(viewport, box, hit.rule() / 2, scroll) + 2;
        return new Rect2i(x, y, FactoryPassiveIoLayout.SLOT, FactoryPassiveIoLayout.SLOT);
    }
}
