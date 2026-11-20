package io.github.crafteve.ae2vf.client.screen.terminal.passiveio;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.ae2vf.client.screen.UiText;
import io.github.crafteve.ae2vf.client.screen.text.TerminalText;
import io.github.crafteve.ae2vf.client.screen.paint.StatusView;
import io.github.crafteve.ae2vf.client.screen.search.SearchFields;
import io.github.crafteve.ae2vf.client.screen.search.SearchQuery;
import io.github.crafteve.ae2vf.client.screen.sort.SortKey;
import io.github.crafteve.ae2vf.client.screen.terminal.TerminalModel;
import io.github.crafteve.ae2vf.client.screen.terminal.TerminalSource;
import io.github.crafteve.ae2vf.io.IoReason;
import io.github.crafteve.ae2vf.io.IoSide;
import io.github.crafteve.ae2vf.machine.MatchMode;
import io.github.crafteve.ae2vf.observation.NodeHealth;
import io.github.crafteve.ae2vf.view.ViewIoRule;
import io.github.crafteve.ae2vf.view.ViewMachine;
import io.github.crafteve.ae2vf.view.ViewNode;
import io.github.crafteve.ae2vf.view.ViewTable;

import appeng.api.stacks.AEKey;

import net.minecraft.core.Direction;

/**
 * 被动 IO 终端的只读内容模型：把视图表按节点铺成卡片。
 *
 * <p>速率与原因已由服务端按成员聚合（速率求和、原因取最坏），本类不再自己累加。搜索与排序
 * 状态在 {@link TerminalModel} 里，本类只负责"表 → 块"和"块的搜索 / 排序字段"。</p>
 */
final class PassiveIoModel extends TerminalModel<PassiveIoModel.BlockView> {
    /**
     * 一条 IO 规则在界面上的样子。
     *
     * @param rule 服务端算好的规则行
     */
    record RuleRow(ViewIoRule rule) {
        IoSide side() {
            return this.rule.side();
        }

        int index() {
            return this.rule.order();
        }

        AEKey key() {
            return this.rule.key();
        }

        @Nullable
        Direction face() {
            return this.rule.face();
        }

        MatchMode mode() {
            return this.rule.mode();
        }

        long rate() {
            return this.rule.rate();
        }

        IoReason reason() {
            return this.rule.reason();
        }

        boolean input() {
            return this.rule.side() == IoSide.INPUT;
        }
    }

    /**
     * 一个节点在内容区里的一块。
     *
     * @param node   节点行
     * @param status 状态展示
     * @param rules  输入在前、输出在后
     */
    record BlockView(ViewNode node, StatusView status, List<RuleRow> rules) {
        BlockView {
            rules = List.copyOf(rules);
        }

        ViewMachine machine() {
            return this.node.machines().get(0);
        }

        List<ViewMachine> members() {
            return this.node.machines();
        }

        UUID machineId() {
            return this.machine().machineId();
        }

        UUID nodeId() {
            return this.node.nodeId();
        }

        int machineCount() {
            return this.node.machineCount();
        }

        String name() {
            if (this.node.core().name() != null && !this.node.core().name().isEmpty()) {
                return this.node.core().name();
            }
            return this.node.core().health() == NodeHealth.OFFLINE
                    ? net.minecraft.network.chat.Component.translatable("idlefactory.common.offline")
                            .getString()
                    : UiText.blockName(this.machine().blockId());
        }
    }

    /**
     * @param source 终端数据源
     */
    PassiveIoModel(TerminalSource source) {
        super(source);
    }

    /** 当前可见块（模型基座里叫行，这里保持界面语汇）。 */
    List<BlockView> blocks() {
        return rows();
    }

    /** 是否有机器（未过滤前）；由屏幕用来区分"空工厂"与"搜索无命中"。 */
    boolean hasMachines() {
        return !rows().isEmpty();
    }

    @Override
    protected List<BlockView> buildRows(ViewTable table) {
        List<BlockView> blocks = new ArrayList<>(table.nodes().size());
        for (ViewNode node : table.nodes()) {
            blocks.add(new BlockView(node, StatusView.of(node.core()), rules(node)));
        }
        return blocks;
    }

    @Override
    protected SearchQuery.Fields fields(BlockView block) {
        // 被动 IO 终端不看样板数，搜索字段里省掉那一段。
        return SearchFields.of(block.node(), -1);
    }

    @Override
    protected SortKey sortKey(BlockView block) {
        long nearest = SortKey.UNREACHABLE_DISTANCE;
        for (ViewMachine machine : block.members()) {
            long distance = SortKey.distance(playerDimension(), playerPosition(),
                    machine.block().dimension(), machine.block().pos());
            nearest = Math.min(nearest, distance);
        }
        return new SortKey(SortKey.displayName(block.node().core().name(),
                        block.node().core().typeBlockId()),
                block.node().core().typeBlockId().toString(), block.machineCount(),
                block.node().ruleCount(), StatusView.sortRank(block.node().core()), nearest,
                block.nodeId());
    }

    @Override
    protected UUID rowId(BlockView block) {
        return block.nodeId();
    }

    /**
     * 把节点规则转成行列表：输入在前、输出在后。
     *
     * @param node 节点行
     * @return 规则行
     */
    private static List<RuleRow> rules(ViewNode node) {
        List<RuleRow> rows = new ArrayList<>(node.rules().size());
        for (ViewIoRule rule : node.rules()) {
            if (rule.side() == IoSide.INPUT) {
                rows.add(new RuleRow(rule));
            }
        }
        for (ViewIoRule rule : node.rules()) {
            if (rule.side() == IoSide.OUTPUT) {
                rows.add(new RuleRow(rule));
            }
        }
        return rows;
    }
}
