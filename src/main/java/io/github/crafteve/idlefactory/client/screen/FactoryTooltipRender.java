package io.github.crafteve.idlefactory.client.screen;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.factory.io.IoResult;
import io.github.crafteve.idlefactory.factory.resource.ResourceKind;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;
import io.github.crafteve.idlefactory.network.payload.FactoryStorageS2C;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;

/**
 * 全部悬停提示：搜索语法、排序按钮、仓库格、机器库、展开按钮、IO 卡片和规则列。
 *
 * <p>顺序即优先级：先搜索框/排序，再玩家槽位、仓库/机器库，最后左表；命中即返回。
 * 产线下拉等浮层打开时整体跳过，避免提示和菜单叠在一起。</p>
 */
final class FactoryTooltipRender {
    private final FactoryScreenContext context;

    /**
     * @param context 界面只读视图
     */
    FactoryTooltipRender(FactoryScreenContext context) {
        this.context = context;
    }

    /**
     * 画当前鼠标下的提示。
     *
     * @param graphics 画布
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     */
    void draw(GuiGraphics graphics, int mouseX, int mouseY) {
        FactoryScreenInteraction interaction = this.context.interaction();
        if (interaction != null && interaction.isOverlayActive()) {
            return;
        }
        FactoryLayout l = this.context.layout();
        FactoryScreenState state = this.context.state();
        FactorySearchEditBox searchBox = this.context.searchBox();
        if (searchBox != null && !searchBox.isFocused()
                && mouseX >= l.rightX + 4
                && mouseX < l.rightX + 4 + l.searchWidth()
                && mouseY >= l.searchY && mouseY < l.searchY + FactoryLayout.SEARCH_H) {
            graphics.renderComponentTooltip(this.context.font(), List.of(
                    searchHint("@", "按模组"),
                    searchHint("#", "按标签"),
                    searchHint("$", "按 tooltip"),
                    searchHint("*", "按注册名"),
                    searchHint("|", "或；空格 与")), mouseX, mouseY);
            return;
        }

        int sortButton = this.context.geometry().sortButtonAt(mouseX, mouseY);
        if (sortButton >= 0 && this.context.sortButtons() != null) {
            List<Component> lines = this.context.sortButtons()
                    .tooltipLines(sortButton, state.activeTab == FactoryScreen.TAB_STORAGE)
                    .stream().map(line -> (Component) Component.literal(line)).toList();
            if (!lines.isEmpty()) {
                graphics.renderComponentTooltip(this.context.font(), lines, mouseX, mouseY);
            }
            return;
        }

        // 背包/快捷栏直接用原版物品 tooltip，完整继承模组追加的文本和组件。
        for (Slot slot : this.context.menu().slots) {
            if (slot.hasItem() && FactoryScreenGeometry.inside(mouseX, mouseY,
                    this.context.leftPos() + slot.x, this.context.topPos() + slot.y, 16, 16)) {
                graphics.renderTooltip(this.context.font(), slot.getItem(), mouseX, mouseY);
                return;
            }
        }

        if (state.activeTab == FactoryScreen.TAB_STORAGE
                && mouseX >= l.gridX
                && mouseX < l.gridX + FactoryLayout.GRID_COLS * FactoryLayout.CELL
                && mouseY >= l.gridY
                && mouseY < l.gridY + l.gridRows * FactoryLayout.CELL) {
            int col = (mouseX - l.gridX) / FactoryLayout.CELL;
            int row = (mouseY - l.gridY) / FactoryLayout.CELL;
            int index = (state.storageFirstRow + row) * FactoryLayout.GRID_COLS + col;
            if (index >= 0 && index < state.filteredStorageEntries.size()) {
                FactoryStorageS2C.Entry entry = state.filteredStorageEntries.get(index);
                // 仓库格同样不做自定义摘要：物品走原版 tooltip，流体/能量只保留名称。
                if (entry.key().kind() == ResourceKind.ITEM) {
                    graphics.renderTooltip(this.context.font(),
                            FactoryUiText.iconStack(entry.key()), mouseX, mouseY);
                } else {
                    graphics.renderComponentTooltip(this.context.font(), List.of(
                            FactoryLabels.colored(FactoryUiText.resourceName(entry.key()),
                                    this.context.theme().tooltip().title())), mouseX, mouseY);
                }
                return;
            }
        }

        if (state.activeTab == FactoryScreen.TAB_MACHINES
                && mouseX >= l.gridX
                && mouseX < l.gridX + FactoryLayout.GRID_COLS * FactoryLayout.CELL
                && mouseY >= l.gridY
                && mouseY < l.gridY + l.gridRows * FactoryLayout.CELL) {
            int row = (mouseY - l.gridY) / FactoryLayout.ROW_H;
            int index = state.machineFirstRow + row;
            if (row < state.machineRowsVisible()
                    && index >= 0 && index < state.filteredMachines.size()) {
                FactorySnapshotBuilder.MachineView machine = state.filteredMachines.get(index);
                drawMachineTooltip(graphics, machine, mouseX, mouseY);
                return;
            }
        }

        if (this.context.geometry().addMachineRowContains(mouseX, mouseY)) {
            graphics.renderComponentTooltip(this.context.font(), List.of(
                    FactoryLabels.colored("新增机器", this.context.theme().tooltip().title()),
                    FactoryLabels.colored("在右侧机器库按住左键拖一台机器到这里",
                            this.context.theme().tooltip().label())), mouseX, mouseY);
            return;
        }

        if (mouseX >= l.leftX && mouseX < l.rightX - FactoryLayout.GAP
                && mouseY >= l.rowTop && mouseY < l.footerY) {
            FactoryScreenGeometry.TableHit hit = this.context.geometry().tableHitAt(mouseY);
            if (hit == null) {
                return;
            }
            FactorySnapshotBuilder.MachineView machine = hit.machine();
            // 展开按钮压在输出列右端，必须先判按钮，否则会弹出输出列规则提示。
            if (this.context.geometry().inExpandButton(hit, mouseX, mouseY)) {
                drawExpandTooltip(graphics, machine, mouseX, mouseY);
                return;
            }
            FactoryIoDetailLayout.Card card = this.context.geometry()
                    .detailCardAt(hit, mouseX, mouseY);
            if (card != null) {
                drawCardTooltip(graphics, machine, card, mouseX, mouseY);
                return;
            }
            // 列规则提示只对基础行有效；展开区不再回落成列提示。
            if (mouseY >= hit.y() + FactoryLayout.ROW_H) {
                return;
            }
            if (l.hasColumn(FactoryLayout.COL_INPUT)
                    && this.context.geometry().inColumn(l, FactoryLayout.COL_INPUT, mouseX)) {
                drawRuleTooltip(graphics, "输入", machine.inputs(),
                        machine.inputRates(), machine.inputReasons(), mouseX, mouseY,
                        state.lineIdleLabel());
                return;
            }
            if (l.hasColumn(FactoryLayout.COL_OUTPUT)
                    && this.context.geometry().inColumn(l, FactoryLayout.COL_OUTPUT, mouseX)) {
                drawRuleTooltip(graphics, "输出", machine.outputs(),
                        machine.outputRates(), machine.outputReasons(), mouseX, mouseY,
                        state.lineIdleLabel());
                return;
            }
        }
    }

    /**
     * 搜索语法提示：符号强调色，说明暗色。
     *
     * @param key         语法符号
     * @param description 说明文字
     * @return 一行提示
     */
    private Component searchHint(String key, String description) {
        FactoryGuiTheme.Tooltip tt = this.context.theme().tooltip();
        return Component.empty()
                .append(FactoryLabels.coloredBold(key, tt.value()))
                .append(FactoryLabels.colored(" " + description, tt.label()));
    }

    /**
     * 展开按钮的提示。
     *
     * @param graphics 画布
     * @param machine  机器视图
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     */
    private void drawExpandTooltip(GuiGraphics graphics,
            FactorySnapshotBuilder.MachineView machine, int mouseX, int mouseY) {
        List<Component> lines = new ArrayList<>();
        FactoryGuiTheme.Tooltip tt = this.context.theme().tooltip();
        lines.add(FactoryLabels.colored(
                this.context.geometry().isOpen(machine) ? "收起 IO 详情" : "展开 IO 详情",
                tt.title()));
        lines.add(Component.empty()
                .append(FactoryLabels.colored("输入 ", tt.label()))
                .append(FactoryLabels.coloredBold(
                        Integer.toString(machine.inputs().size()), tt.value()))
                .append(FactoryLabels.colored(" · 输出 ", tt.label()))
                .append(FactoryLabels.coloredBold(
                        Integer.toString(machine.outputs().size()), tt.value()))
                .append(FactoryLabels.colored(" 条", tt.label())));
        graphics.renderComponentTooltip(this.context.font(), lines, mouseX, mouseY);
    }

    /**
     * 展开区卡片的完整信息。
     *
     * @param graphics 画布
     * @param machine  机器视图
     * @param card     展开区卡片
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     */
    private void drawCardTooltip(GuiGraphics graphics,
            FactorySnapshotBuilder.MachineView machine, FactoryIoDetailLayout.Card card,
            int mouseX, int mouseY) {
        List<ResourceSpec> rules = card.input() ? machine.inputs() : machine.outputs();
        FactoryGuiTheme.Tooltip tt = this.context.theme().tooltip();
        List<Component> lines = new ArrayList<>();
        if (card.empty()) {
            lines.add(FactoryLabels.colored(card.input() ? "输入空卡" : "输出空卡", tt.title()));
            lines.add(FactoryLabels.colored("从仓库拖一个资源到这里新增规则", tt.label()));
            graphics.renderComponentTooltip(this.context.font(), lines, mouseX, mouseY);
            return;
        }
        if (card.ruleIndex() >= rules.size()) {
            return;
        }
        ResourceSpec spec = rules.get(card.ruleIndex());
        String idleLabel = this.context.state().lineIdleLabel();
        FactoryIoCardControls.Hit hit = FactoryIoCardControls.hit(card.x(), card.y(), card.width(),
                false, spec, mouseX, mouseY);
        switch (hit.kind()) {
            case FACE -> {
                lines.add(FactoryLabels.colored(
                        card.input() ? "输入面" : "输出面", tt.title()));
                lines.add(FactoryLabels.faceLine(
                        "当前：", FactoryUiText.faceName(spec), FactoryLabels.faceEn(spec),
                        this.context.theme()));
                lines.add(FactoryLabels.colored("左键下一个面，右键上一个面", tt.label()));
                graphics.renderComponentTooltip(this.context.font(), lines, mouseX, mouseY);
                return;
            }
            case MATCH -> {
                lines.add(FactoryLabels.colored("匹配模式", tt.title()));
                lines.add(FactoryLabels.colored(spec.fuzzy()
                        ? "模糊（忽略组件），点击改精确"
                        : "精确（含组件），点击改模糊", tt.label()));
                graphics.renderComponentTooltip(this.context.font(), lines, mouseX, mouseY);
                return;
            }
            default -> {
                // 卡片空白处：回落到规则详情。
            }
        }

        long rate = machine.ruleRate(card.input(), card.ruleIndex());
        List<IoResult.Reason> reasons = card.input()
                ? machine.inputReasons() : machine.outputReasons();
        IoResult.Reason reason = card.ruleIndex() < reasons.size()
                ? reasons.get(card.ruleIndex()) : IoResult.Reason.NO_ITEM;
        IoResult.Reason effective = idleLabel != null ? IoResult.Reason.NO_ITEM
                : FactoryLabels.effectiveReason(rate, reason);

        lines.add(Component.empty()
                .append(FactoryLabels.colored(card.input() ? "输入" : "输出", tt.title()))
                .append(FactoryLabels.colored(" #", tt.label()))
                .append(FactoryLabels.colored(Integer.toString(card.ruleIndex() + 1),
                        tt.index()))
                .append(FactoryLabels.colored(" · ", tt.label()))
                .append(FactoryLabels.colored(FactoryLabels.name(spec.key()), tt.title())));
        lines.add(FactoryLabels.faceLine(
                card.input() ? "输入面：" : "输出面：",
                FactoryUiText.faceName(spec), FactoryLabels.faceEn(spec),
                this.context.theme()));
        if (spec.fuzzy()) {
            lines.add(FactoryLabels.coloredBold("忽略组件", 0xFFFF55));
        }
        lines.add(Component.empty()
                .append(FactoryLabels.colored("速率：", tt.label()))
                .append(FactoryLabels.rate(rate, spec.key().kind(), effective,
                        this.context.theme())));
        if (idleLabel != null) {
            lines.add(Component.empty()
                    .append(FactoryLabels.colored("状态：", tt.label()))
                    .append(FactoryLabels.coloredBold(idleLabel, tt.idle())));
        } else {
            lines.add(FactoryLabels.state(effective, card.input(), this.context.theme()));
        }
        lines.add(FactoryLabels.colored("拖回仓库删除", tt.label()));
        graphics.renderComponentTooltip(this.context.font(), lines, mouseX, mouseY);
    }

    /**
     * 输入/输出列的多规则 tooltip。
     *
     * @param graphics  画布
     * @param title     标题
     * @param rules     规则
     * @param rates     规则速率
     * @param reasons   逐规则原因
     * @param mouseX    鼠标 x
     * @param mouseY    鼠标 y
     * @param idleLabel 暂停/睡眠文案；运行中传 {@code null}
     */
    private void drawRuleTooltip(GuiGraphics graphics, String title,
            List<ResourceSpec> rules, List<Long> rates, List<IoResult.Reason> reasons,
            int mouseX, int mouseY, @Nullable String idleLabel) {
        if (rules.isEmpty()) {
            return;
        }
        List<Component> lines = new ArrayList<>();
        FactoryGuiTheme.Tooltip tt = this.context.theme().tooltip();
        lines.add(FactoryLabels.colored(title, tt.title()));
        int shown = Math.min(rules.size(), 10);
        for (int i = 0; i < shown; i++) {
            ResourceSpec spec = rules.get(i);
            long rate = i < rates.size() ? rates.get(i) : 0L;
            IoResult.Reason reason = i < reasons.size()
                    ? reasons.get(i) : IoResult.Reason.NO_ITEM;
            IoResult.Reason effective = idleLabel != null ? IoResult.Reason.NO_ITEM
                    : FactoryLabels.effectiveReason(rate, reason);
            lines.add(Component.empty()
                    .append(FactoryLabels.colored(FactoryLabels.name(spec.key()), tt.title()))
                    .append(FactoryLabels.colored(" · ", tt.label()))
                    .append(FactoryLabels.coloredBold(FactoryUiText.faceName(spec), tt.face()))
                    .append(FactoryLabels.colored(" · ", tt.label()))
                    .append(FactoryLabels.rate(rate, spec.key().kind(), effective,
                            this.context.theme())));
        }
        if (rules.size() > shown) {
            lines.add(Component.empty()
                    .append(FactoryLabels.colored("还有 ", tt.label()))
                    .append(FactoryLabels.coloredBold(
                            Integer.toString(rules.size() - shown), tt.value()))
                    .append(FactoryLabels.colored(" 条", tt.label())));
        }
        if (idleLabel != null) {
            lines.add(FactoryLabels.coloredBold(idleLabel, tt.idle()));
        }
        graphics.renderComponentTooltip(this.context.font(), lines, mouseX, mouseY);
    }

    /**
     * 机器库卡片的 tooltip。
     *
     * @param graphics 画布
     * @param machine  机器视图
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     */
    private void drawMachineTooltip(GuiGraphics graphics,
            FactorySnapshotBuilder.MachineView machine, int mouseX, int mouseY) {
        List<Component> lines = new ArrayList<>();
        FactoryGuiTheme.Tooltip tt = this.context.theme().tooltip();
        lines.add(FactoryLabels.colored(FactoryLabels.blockName(machine.blockId()), tt.title()));
        lines.add(FactoryLabels.coloredBold(
                FactoryStatus.text(machine, this.context.theme()),
                FactoryStatus.of(machine, this.context.theme()).color()));
        lines.add(FactoryLabels.colored(machine.ref().dimension().location()
                + " " + machine.ref().pos().toShortString(), tt.label()));
        lines.add(machine.lineNames().isEmpty()
                ? FactoryLabels.colored("未编线", tt.idle())
                : FactoryLabels.labelValue("产线 ",
                        String.join("/", machine.lineNames()), this.context.theme()));
        lines.add(Component.empty()
                .append(FactoryLabels.colored("规则 ", tt.label()))
                .append(FactoryLabels.coloredBold(
                        Integer.toString(machine.ruleCount()), tt.value()))
                .append(FactoryLabels.colored(" 条", tt.label())));
        graphics.renderComponentTooltip(this.context.font(), lines, mouseX, mouseY);
    }
}
