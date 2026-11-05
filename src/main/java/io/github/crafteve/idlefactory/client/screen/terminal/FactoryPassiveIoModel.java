package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * 被动 IO 终端的只读内容模型：一份机器表 + 搜索词 → 按节点分组的卡片。
 *
 * <p>按节点分组：一个节点一张大卡片，堆叠机器的数量标识等于机器数。规则条数取节点规则，
 * 速率按成员求和，逐规则原因取最坏（非 OK 优先）。节点顺序跟随机器表首次出现的顺序，
 * 不随速率或状态重排。</p>
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
     * @param rate   服务端算好的速率（数量/秒，已按成员求和）
     * @param reason 本轮逐规则原因（已按成员取最坏）
     */
    record RuleRow(IoSide side, int index, ResourceSpec spec, long rate, IoResult.Reason reason) {
        /** 是否输入。 */
        boolean input() {
            return this.side == IoSide.INPUT;
        }
    }

    /**
     * 一个节点在内容区里的一块：机器行 + 规则行 + 虚线新增行。
     *
     * @param machine 代表机器（节点第一台），图标 / 坐标 / 搜索字段用它
     * @param members 节点下的全部机器；堆叠时长度 > 1
     * @param status  节点聚合状态
     * @param rules   输入在前、输出在后（节点规则）
     */
    record BlockView(FactorySnapshotBuilder.MachineView machine,
            List<FactorySnapshotBuilder.MachineView> members, FactoryStatus status,
            List<RuleRow> rules) {
        BlockView {
            members = List.copyOf(members);
            rules = List.copyOf(rules);
        }

        /** 机器 id；发机器级动作要用。 */
        UUID machineId() {
            return this.machine.machineId();
        }

        /** 节点 id；规则动作按节点发。 */
        UUID nodeId() {
            return this.machine.nodeId();
        }

        /** 节点机器数；堆叠数量标识用它。 */
        int machineCount() {
            return this.members.size();
        }

        /** 节点方块总数；多方块占位 tooltip 用它。 */
        int blockCount() {
            int total = 0;
            for (FactorySnapshotBuilder.MachineView member : this.members) {
                total += member.blockCount();
            }
            return total;
        }

        /** 是否多方块（任一成员方块数 > 1）。 */
        boolean multiblock() {
            for (FactorySnapshotBuilder.MachineView member : this.members) {
                if (member.blockCount() > 1) {
                    return true;
                }
            }
            return false;
        }

        /** 节点显示名；优先节点名，否则方块名；失联时直接写「已失联」。 */
        String name() {
            if (this.machine.nodeName() != null && !this.machine.nodeName().isEmpty()) {
                return this.machine.nodeName();
            }
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

    /** 当前搜索词是否命中任何节点。 */
    boolean filteredEmpty() {
        return !this.query.isEmpty() && this.blocks.isEmpty();
    }

    /**
     * 按当前搜索词与快照重建块列表。
     *
     * <p>过滤只作用于成员机器层：命中一台机器就把整个节点铺开，不做规则级过滤。</p>
     */
    private void rebuild() {
        Map<UUID, List<FactorySnapshotBuilder.MachineView>> byNode = new LinkedHashMap<>();
        for (FactorySnapshotBuilder.MachineView machine : this.machines) {
            byNode.computeIfAbsent(machine.nodeId(), ignored -> new ArrayList<>()).add(machine);
        }
        List<BlockView> next = new ArrayList<>(byNode.size());
        for (List<FactorySnapshotBuilder.MachineView> members : byNode.values()) {
            boolean matched = this.query.isEmpty();
            for (FactorySnapshotBuilder.MachineView member : members) {
                if (!matched && this.query.matches(
                        FactoryMachineSearchFields.of(member, this.theme))) {
                    matched = true;
                }
            }
            if (!matched) {
                continue;
            }
            FactorySnapshotBuilder.MachineView anchor = members.get(0);
            next.add(new BlockView(anchor, members, nodeStatus(members, anchor),
                    rules(members, anchor)));
        }
        this.blocks = List.copyOf(next);
    }

    /**
     * 节点聚合状态：全部离线 = 失联，部分在线 = 缺机，其余沿用代表机器的状态。
     *
     * @param members 成员
     * @param anchor  代表机器
     * @return 状态展示
     */
    private FactoryStatus nodeStatus(List<FactorySnapshotBuilder.MachineView> members,
            FactorySnapshotBuilder.MachineView anchor) {
        int online = 0;
        for (FactorySnapshotBuilder.MachineView member : members) {
            if (member.health().isOnline()) {
                online++;
            }
        }
        if (online == 0) {
            return FactoryStatus.offline(this.theme);
        }
        if (online < members.size()) {
            return FactoryStatus.partial(this.theme);
        }
        return FactoryStatus.of(anchor, this.theme);
    }

    /**
     * 把节点规则拼成一份行列表：速率按成员求和，原因取最坏（非 OK 优先）。
     *
     * @param members 成员
     * @param anchor  代表机器（规则相同的来源）
     * @return 输入在前、输出在后的规则行
     */
    private static List<RuleRow> rules(List<FactorySnapshotBuilder.MachineView> members,
            FactorySnapshotBuilder.MachineView anchor) {
        List<RuleRow> rows = new ArrayList<>(anchor.inputs().size() + anchor.outputs().size());
        append(rows, members, IoSide.INPUT, anchor.inputs());
        append(rows, members, IoSide.OUTPUT, anchor.outputs());
        return rows;
    }

    private static void append(List<RuleRow> rows, List<FactorySnapshotBuilder.MachineView> members,
            IoSide side, List<ResourceSpec> specs) {
        for (int i = 0; i < specs.size(); i++) {
            long rate = 0L;
            IoResult.Reason reason = IoResult.Reason.OK;
            for (FactorySnapshotBuilder.MachineView member : members) {
                List<Long> rates = side == IoSide.INPUT ? member.inputRates()
                        : member.outputRates();
                List<IoResult.Reason> reasons = side == IoSide.INPUT ? member.inputReasons()
                        : member.outputReasons();
                rate += i < rates.size() ? rates.get(i) : 0L;
                reason = worse(reason, i < reasons.size() ? reasons.get(i)
                        : IoResult.Reason.NO_ITEM);
            }
            rows.add(new RuleRow(side, i, specs.get(i), rate, reason));
        }
    }

    /** 取更坏的原因：REJECTED &gt; NO_HANDLER &gt; NO_SPACE &gt; NO_ITEM &gt; OK。 */
    private static IoResult.Reason worse(IoResult.Reason left, IoResult.Reason right) {
        return severity(right) > severity(left) ? right : left;
    }

    private static int severity(IoResult.Reason reason) {
        return switch (reason) {
            case REJECTED -> 4;
            case NO_HANDLER -> 3;
            case NO_SPACE -> 2;
            case NO_ITEM -> 1;
            case OK -> 0;
        };
    }
}
