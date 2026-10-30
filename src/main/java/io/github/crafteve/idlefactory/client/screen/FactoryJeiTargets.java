package io.github.crafteve.idlefactory.client.screen;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
import io.github.crafteve.idlefactory.factory.resource.ResourceKind;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;
import io.github.crafteve.idlefactory.network.payload.FactoryStorageS2C;

import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 把鼠标下的槽位/规则/机器转成 JEI 目标。
 *
 * <p>顺序与绘制层级一致：玩家槽位 → 仓库格/机器库 → 左表机器 → IO 配置卡 → 规则列；
 * 命中即返回。流体按桶图标处理，能量返回空 stack，由 JEI 插件换成独立 FE 配料。</p>
 */
final class FactoryJeiTargets {
    private final FactoryScreenContext context;

    /**
     * @param context 界面只读视图
     */
    FactoryJeiTargets(FactoryScreenContext context) {
        this.context = context;
    }

    /**
     * 当前鼠标下的 JEI 查询目标。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 目标；鼠标下没有物品时为 {@code null}
     */
    @Nullable
    FactoryScreen.JeiTarget at(double mouseX, double mouseY) {
        FactoryLayout l = this.context.layout();
        if (l == null) {
            return null;
        }
        FactoryScreenInteraction interaction = this.context.interaction();
        if (interaction != null && interaction.isOverlayActive()) {
            return null;
        }
        FactoryScreenState state = this.context.state();

        for (Slot slot : this.context.menu().slots) {
            if (slot.hasItem()
                    && FactoryScreenGeometry.inside(mouseX, mouseY,
                            this.context.leftPos() + slot.x, this.context.topPos() + slot.y,
                            16, 16)) {
                return new FactoryScreen.JeiTarget(ResourceKind.ITEM, slot.getItem(),
                        new Rect2i(this.context.leftPos() + slot.x - 1,
                                this.context.topPos() + slot.y - 1, FactoryLayout.CELL,
                                FactoryLayout.CELL));
            }
        }

        if (state.activeTab == FactoryScreen.TAB_STORAGE
                && mouseX >= l.gridX
                && mouseX < l.gridX + FactoryLayout.GRID_COLS * FactoryLayout.CELL
                && mouseY >= l.gridY
                && mouseY < l.gridY + l.gridRows * FactoryLayout.CELL) {
            FactoryStorageS2C.Entry entry = storageEntryAt(mouseX, mouseY);
            if (entry != null) {
                int col = (int) ((mouseX - l.gridX) / FactoryLayout.CELL);
                int row = (int) ((mouseY - l.gridY) / FactoryLayout.CELL);
                return resourceTarget(entry.key(),
                        new Rect2i(l.gridX + col * FactoryLayout.CELL,
                                l.gridY + row * FactoryLayout.CELL,
                                FactoryLayout.CELL, FactoryLayout.CELL));
            }
        }

        if (state.activeTab == FactoryScreen.TAB_MACHINES
                && mouseX >= l.gridX
                && mouseX < l.gridX + FactoryLayout.GRID_COLS * FactoryLayout.CELL
                && mouseY >= l.gridY
                && mouseY < l.gridY + l.gridRows * FactoryLayout.CELL) {
            int row = (int) ((mouseY - l.gridY) / FactoryLayout.ROW_H);
            int index = state.machineFirstRow + row;
            if (row < state.machineRowsVisible()
                    && index >= 0 && index < state.filteredMachines.size()) {
                int cardY = l.gridY + row * FactoryLayout.ROW_H;
                int iconX = l.gridX + 6;
                int iconY = cardY + 3;
                if (FactoryScreenGeometry.inside(mouseX, mouseY, iconX, iconY,
                        FactoryLayout.CELL, FactoryLayout.CELL)) {
                    FactorySnapshotBuilder.MachineView machine = state.filteredMachines.get(index);
                    ItemStack stack = FactoryLabels.blockStack(machine.blockId());
                    if (!stack.isEmpty()) {
                        return new FactoryScreen.JeiTarget(ResourceKind.ITEM, stack,
                                new Rect2i(iconX, iconY, FactoryLayout.CELL,
                                        FactoryLayout.CELL));
                    }
                }
            }
        }

        if (mouseX >= l.leftX && mouseX < l.rightX - FactoryLayout.GAP
                && mouseY >= l.rowTop && mouseY < l.footerY) {
            FactoryScreenGeometry.TableHit hit = this.context.geometry().tableHitAt(mouseY);
            if (hit != null) {
                FactorySnapshotBuilder.MachineView machine = hit.machine();
                int y = hit.y();

                if (this.context.geometry().inExpandButton(hit, mouseX, mouseY)
                        || this.context.geometry().inRemoveButton(hit, mouseX, mouseY)) {
                    return null;
                }

                FactoryIoDetailLayout.Card card = this.context.geometry()
                        .detailCardAt(hit, mouseX, mouseY);
                if (card != null) {
                    if (!card.empty()
                            && FactoryScreenGeometry.inside(mouseX, mouseY,
                                    card.x() + 3, card.y() + 3,
                                    FactoryLayout.CELL, FactoryLayout.CELL)) {
                        List<ResourceSpec> rules = card.input()
                                ? machine.inputs() : machine.outputs();
                        if (card.ruleIndex() < rules.size()) {
                            return resourceTarget(rules.get(card.ruleIndex()).key(),
                                    new Rect2i(card.x() + 4, card.y() + 4,
                                            FactoryLayout.CELL, FactoryLayout.CELL));
                        }
                    }
                    return null;
                }

                if (mouseY >= y + FactoryLayout.ROW_H) {
                    return null;
                }

                int iconX = l.columnX(FactoryLayout.COL_NAME) + 4;
                if (FactoryScreenGeometry.inside(mouseX, mouseY, iconX, y + 4,
                        FactoryLayout.CELL, FactoryLayout.CELL)) {
                    ItemStack stack = FactoryLabels.blockStack(machine.blockId());
                    if (!stack.isEmpty()) {
                        return new FactoryScreen.JeiTarget(ResourceKind.ITEM, stack,
                                new Rect2i(iconX, y + 4, FactoryLayout.CELL,
                                        FactoryLayout.CELL));
                    }
                }
                if (l.hasColumn(FactoryLayout.COL_INPUT)) {
                    FactoryScreen.JeiTarget target = ruleTarget(machine.inputs(),
                            machine.inputRates(), l.columnX(FactoryLayout.COL_INPUT) + 2,
                            y + 4, l.columnW(FactoryLayout.COL_INPUT) - 4, mouseX, mouseY);
                    if (target != null) {
                        return target;
                    }
                }
                if (l.hasColumn(FactoryLayout.COL_OUTPUT)) {
                    FactoryScreen.JeiTarget target = ruleTarget(machine.outputs(),
                            machine.outputRates(), l.columnX(FactoryLayout.COL_OUTPUT) + 2,
                            y + 4, l.columnW(FactoryLayout.COL_OUTPUT) - 4
                                    - FactoryLayout.ROW_ACTIONS_W, mouseX, mouseY);
                    if (target != null) {
                        return target;
                    }
                }
            }
        }
        return null;
    }

    @Nullable
    private FactoryStorageS2C.Entry storageEntryAt(double mouseX, double mouseY) {
        FactoryLayout l = this.context.layout();
        FactoryScreenState state = this.context.state();
        int col = (int) ((mouseX - l.gridX) / FactoryLayout.CELL);
        int row = (int) ((mouseY - l.gridY) / FactoryLayout.CELL);
        int index = (state.storageFirstRow + row) * FactoryLayout.GRID_COLS + col;
        return index >= 0 && index < state.filteredStorageEntries.size()
                ? state.filteredStorageEntries.get(index)
                : null;
    }

    private FactoryScreen.JeiTarget resourceTarget(ResourceKey key, Rect2i area) {
        ResourceKind kind = key.kind();
        return new FactoryScreen.JeiTarget(kind,
                kind == ResourceKind.ENERGY ? ItemStack.EMPTY
                        : FactoryUiText.iconStack(key), area);
    }

    @Nullable
    private FactoryScreen.JeiTarget ruleTarget(List<ResourceSpec> rules, List<Long> rates,
            int x, int y, int maxWidth, double mouseX, double mouseY) {
        if (rules.isEmpty()) {
            return null;
        }
        int cursor = x;
        int shown = 0;
        int limit = Math.min(rules.size(), 3);
        while (shown < limit) {
            String rate = FactoryLabels.rateText(
                    shown < rates.size() ? rates.get(shown) : 0L,
                    rules.get(shown).key().kind());
            int entryWidth = FactoryLayout.CELL + 2 + this.context.font().width(rate) + 6;
            if (shown > 0 && cursor + entryWidth > x + maxWidth) {
                break;
            }
            if (FactoryScreenGeometry.inside(mouseX, mouseY, cursor, y,
                    FactoryLayout.CELL, FactoryLayout.CELL)) {
                return resourceTarget(rules.get(shown).key(),
                        new Rect2i(cursor, y, FactoryLayout.CELL, FactoryLayout.CELL));
            }
            cursor += entryWidth;
            shown++;
        }
        return null;
    }
}
