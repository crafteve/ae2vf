package io.github.crafteve.idlefactory.client.screen.terminal.passiveio;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEKey;

import io.github.crafteve.idlefactory.client.screen.UiText;
import io.github.crafteve.idlefactory.client.screen.text.TerminalText;
import io.github.crafteve.idlefactory.client.screen.terminal.passiveio.PassiveIoLayout.BlockBox;
import io.github.crafteve.idlefactory.client.screen.terminal.passiveio.PassiveIoLayout.Hit;
import io.github.crafteve.idlefactory.client.screen.terminal.passiveio.PassiveIoLayout.Viewport;
import io.github.crafteve.idlefactory.client.screen.terminal.passiveio.PassiveIoModel.BlockView;
import io.github.crafteve.idlefactory.client.screen.terminal.passiveio.PassiveIoModel.RuleRow;
import io.github.crafteve.idlefactory.key.AeKeyAdapter;
import io.github.crafteve.idlefactory.menu.PassiveIoViewMode;

import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;

/**
 * 被动 IO 内容区的 JEI 目标：R/U 查询区域与拖拽新建区域。
 *
 * <p>两类目标都从同一份排版算出来：拖拽目标是整张机器大卡片（含机器行），查询目标是
 * 机器图标与规则图标。几何不在插件里重算，插件只负责把目标交给 JEI。</p>
 */
public final class PassiveIoJeiIngredients {
    private PassiveIoJeiIngredients() {
        // 纯静态目标构造
    }

    /**
     * 一条 JEI 拖拽目标。
     *
     * @param block 机器块下标
     * @param area  机器大卡片的屏幕区域（已按视口裁剪）
     */
    public record AddTarget(int block, Rect2i area) {
    }

    /**
     * 一个 JEI 查询目标。
     *
     * @param energy 是否 FE 目标（JEI 用独立配料，不能落到红石粉）
     * @param stack  图标物品（流体用桶、FE 用红石）
     * @param area   图标区域
     */
    public record JeiTarget(boolean energy, ItemStack stack, Rect2i area) {
    }

    /**
     * 构造全部拖拽目标。
     *
     * <p>每台机器一条，区域就是它的机器大卡片：整卡可拖入，不再依赖虚线新增行。
     * 区域按视口裁剪，滚出视口的部分不参与命中，否则拖拽高亮会画到滚动条或背包槽上。</p>
     *
     * @param layout 当前排版
     * @param scroll 当前滚动
     * @return 每台机器一条
     */
    static List<AddTarget> addTargets(PassiveIoLayout layout, int scroll) {
        List<AddTarget> targets = new ArrayList<>();
        Viewport viewport = layout.viewport();
        int left = PassiveIoLayout.bodyX(viewport);
        int width = viewport.width() - 1;
        int viewTop = viewport.y();
        int viewBottom = viewport.y() + viewport.height();
        for (BlockBox box : layout.boxes()) {
            int top = Math.max(viewTop, PassiveIoLayout.screenY(viewport, box.top(), scroll));
            int bottom = Math.min(viewBottom,
                    PassiveIoLayout.screenY(viewport, box.bottom(), scroll));
            if (bottom <= top) {
                continue;
            }
            targets.add(new AddTarget(box.index(), new Rect2i(left, top, width, bottom - top)));
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
    static JeiTarget targetAt(PassiveIoLayout layout, List<BlockView> blocks, int scroll,
            double mouseX, double mouseY) {
        Hit hit = layout.hit(mouseX, mouseY, scroll);
        if (hit == null || hit.kind() == PassiveIoLayout.Kind.ADD) {
            return null;
        }
        BlockView block = blocks.get(hit.block());
        if (hit.kind() == PassiveIoLayout.Kind.MACHINE) {
            ItemStack stack = UiText.blockStack(block.machine().blockId());
            return stack.isEmpty() ? null
                    : new JeiTarget(false, stack, machineIconArea(layout, hit, scroll));
        }
        RuleRow rule = block.rules().get(hit.rule());
        AEKey key = rule.key();
        return new JeiTarget(AeKeyAdapter.isEnergy(key), UiText.icon(key),
                ruleIconArea(layout, hit, scroll));
    }

    private static Rect2i machineIconArea(PassiveIoLayout layout, Hit hit, int scroll) {
        Viewport viewport = layout.viewport();
        BlockBox box = layout.boxes().get(hit.block());
        int x = PassiveIoLayout.headerX(viewport);
        int y = PassiveIoLayout.screenY(viewport, box.top(), scroll) + 1;
        return new Rect2i(x, y, PassiveIoLayout.SLOT, PassiveIoLayout.SLOT);
    }

    private static Rect2i ruleIconArea(PassiveIoLayout layout, Hit hit, int scroll) {
        Viewport viewport = layout.viewport();
        BlockBox box = layout.boxes().get(hit.block());
        if (layout.view() == PassiveIoViewMode.LIST) {
            int y = PassiveIoLayout.rowY(viewport, box, hit.rule(), scroll)
                    + (PassiveIoLayout.ROW_H - PassiveIoLayout.RULE_ICON) / 2;
            return new Rect2i(PassiveIoLayout.slotX(viewport), y,
                    PassiveIoLayout.RULE_ICON, PassiveIoLayout.RULE_ICON);
        }
        int x = PassiveIoLayout.tileX(viewport, hit.rule() % 2)
                + PassiveIoLayout.TILE_INSET;
        int y = PassiveIoLayout.tileRowY(viewport, box, hit.rule() / 2, scroll) + 2;
        return new Rect2i(x, y, PassiveIoLayout.SLOT, PassiveIoLayout.SLOT);
    }
}
