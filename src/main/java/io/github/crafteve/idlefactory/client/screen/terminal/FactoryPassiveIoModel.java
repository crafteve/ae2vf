package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.crafteve.idlefactory.client.screen.FactoryGuiTheme;
import io.github.crafteve.idlefactory.client.screen.FactoryLabels;
import io.github.crafteve.idlefactory.client.screen.FactoryMachineSearchFields;
import io.github.crafteve.idlefactory.client.screen.FactorySearchQuery;
import io.github.crafteve.idlefactory.client.screen.FactoryStatus;
import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.factory.io.IoSide;
import io.github.crafteve.idlefactory.factory.io.IoResult;
import io.github.crafteve.idlefactory.factory.machine.ResourceSpec;
import io.github.crafteve.idlefactory.network.payload.FactoryTableS2C;

/**
 * 被动 IO 终端的只读内容模型：一份机器表 + 搜索词 → 平铺的机器块。
 *
 * <p>不做分组：草稿 12 的三组分组（失联 / 未配置 / 运行中）在实现阶段被用户取消，机器按机器库
 * 登记顺序平铺，顺序只随快照变化，不随速率或状态变化重排——否则每秒刷新都会跳卡。</p>
 *
 * <p>模型只存服务端已经算好的视图，不探世界、不推导执行状态；分组、过滤、计数都在
 * {@link #rebuild()} 一处重算。绘制与命中只读 {@link #blocks()}。</p>
 */
final class FactoryPassiveIoModel {
    private final FactoryGuiTheme theme;
    private List<FactorySnapshotBuilder.MachineView> machines = List.of();
    private boolean hasFactory;
    private boolean storageOnline;
    private FactorySearchQuery query = FactorySearchQuery.EMPTY;
    private List<BlockView> blocks = List.of();

    /**
     * 一条 IO 规则在界面上的样子。
     *
     * @param side   方向
     * @param index  该方向内的下标，用来回传动作
     * @param spec   规则
     * @param rate   服务端算好的速率（数量/秒）
     * @param reason 本轮逐规则原因
     */
    record RuleRow(IoSide side, int index, ResourceSpec spec, long rate, IoResult.Reason reason) {
        /** 是否输入。 */
        boolean input() {
            return this.side == IoSide.INPUT;
        }
    }

    /**
     * 一台机器在内容区里的一块：机器行 + 规则行 + 虚线新增行。
     *
     * @param machine 机器视图
     * @param status  状态展示（文字、颜色、灯）
     * @param rules   输入在前、输出在后
     */
    record BlockView(FactorySnapshotBuilder.MachineView machine, FactoryStatus status,
            List<RuleRow> rules) {
        BlockView {
            rules = List.copyOf(rules);
        }

        /** 机器 id；发规则动作要用。 */
        UUID machineId() {
            return this.machine.machineId();
        }

        /** 机器显示名；失联时直接写「已失联」。 */
        String name() {
            return !this.machine.health().isOnline()
                    ? "已失联" : FactoryLabels.blockName(this.machine.blockId());
        }
    }

    /**
     * @param theme 当前主题，状态文字与搜索字段取色用
     */
    FactoryPassiveIoModel(FactoryGuiTheme theme) {
        this.theme = theme;
    }

    /**
     * 接收一份新的机器表。
     *
     * @param payload 机器表快照
     */
    void accept(FactoryTableS2C payload) {
        this.hasFactory = payload.hasFactory();
        this.storageOnline = payload.storageOnline();
        this.machines = payload.machines();
        rebuild();
    }

    /**
     * 更新搜索词并重建视图。
     *
     * @param next 新搜索条件
     */
    void setQuery(FactorySearchQuery next) {
        this.query = next == null ? FactorySearchQuery.EMPTY : next;
        rebuild();
    }

    /** 当前可见块。 */
    List<BlockView> blocks() {
        return this.blocks;
    }

    /** 服务端是否已经有工厂实例。 */
    boolean hasFactory() {
        return this.hasFactory;
    }

    /** 仓库是否在线；内容区暂不显示，留给后续状态条。 */
    boolean storageOnline() {
        return this.storageOnline;
    }

    /** 是否有机器（未过滤前）。 */
    boolean hasMachines() {
        return !this.machines.isEmpty();
    }

    /** 当前搜索词是否命中任何机器。 */
    boolean filteredEmpty() {
        return !this.query.isEmpty() && this.blocks.isEmpty();
    }

    /**
     * 按当前搜索词与快照重建块列表。
     *
     * <p>过滤只作用于机器层：命中一台机器就把它的全部规则铺开，不做规则级过滤。</p>
     */
    private void rebuild() {
        List<BlockView> next = new ArrayList<>(this.machines.size());
        for (FactorySnapshotBuilder.MachineView machine : this.machines) {
            if (!this.query.isEmpty()
                    && !this.query.matches(FactoryMachineSearchFields.of(machine, this.theme))) {
                continue;
            }
            next.add(new BlockView(machine, FactoryStatus.of(machine, this.theme),
                    rules(machine)));
        }
        this.blocks = List.copyOf(next);
    }

    /**
     * 把一台机器的两侧规则拼成一份行列表。
     *
     * @param machine 机器视图
     * @return 输入在前、输出在后的规则行
     */
    private static List<RuleRow> rules(FactorySnapshotBuilder.MachineView machine) {
        List<RuleRow> rows = new ArrayList<>(machine.inputs().size() + machine.outputs().size());
        append(rows, machine, IoSide.INPUT, machine.inputs(), machine.inputRates(),
                machine.inputReasons());
        append(rows, machine, IoSide.OUTPUT, machine.outputs(), machine.outputRates(),
                machine.outputReasons());
        return rows;
    }

    private static void append(List<RuleRow> rows, FactorySnapshotBuilder.MachineView machine,
            IoSide side, List<ResourceSpec> specs, List<Long> rates, List<IoResult.Reason> reasons) {
        for (int i = 0; i < specs.size(); i++) {
            long rate = i < rates.size() ? rates.get(i) : 0L;
            IoResult.Reason reason = i < reasons.size() ? reasons.get(i) : IoResult.Reason.NO_ITEM;
            rows.add(new RuleRow(side, i, specs.get(i), rate, reason));
        }
    }
}
