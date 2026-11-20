package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.client.screen.FactoryGuiTheme;
import io.github.crafteve.idlefactory.client.screen.FactoryLabels;
import io.github.crafteve.idlefactory.client.screen.FactoryNodeSearchFields;
import io.github.crafteve.idlefactory.client.screen.search.SearchQuery;
import io.github.crafteve.idlefactory.client.screen.FactorySortContext;
import io.github.crafteve.idlefactory.client.screen.FactorySortKey;
import io.github.crafteve.idlefactory.client.screen.FactoryStatus;
import io.github.crafteve.idlefactory.io.IoReason;
import io.github.crafteve.idlefactory.io.IoSide;
import io.github.crafteve.idlefactory.machine.MatchMode;
import io.github.crafteve.idlefactory.observation.NodeHealth;
import io.github.crafteve.idlefactory.view.ViewIoRule;
import io.github.crafteve.idlefactory.view.ViewMachine;
import io.github.crafteve.idlefactory.view.ViewNode;
import io.github.crafteve.idlefactory.view.ViewTable;

import appeng.api.stacks.AEKey;

import net.minecraft.core.Direction;

/**
 * 被动 IO 终端的只读内容模型：一张视图表 + 搜索词 → 按节点分组的卡片。
 *
 * <p>按节点分组：一个节点一张大卡片，堆叠机器的数量标识等于机器数。速率与原因已由服务端
 * 按成员聚合（速率求和、原因取最坏），本类不再自己累加。节点顺序跟随视图表顺序，不随速率或
 * 状态重排。</p>
 *
 * <p>模型只存服务端已经算好的视图，不探世界、不推导执行状态；过滤、排序都在 {@link #rebuild()}
 * 一处重算。绘制与命中只读 {@link #blocks()}。</p>
 */
final class FactoryPassiveIoModel {
    private final FactoryGuiTheme theme;
    private List<ViewNode> nodes = List.of();
    private boolean hasFactory;
    private boolean storageOnline;
    private SearchQuery query = SearchQuery.EMPTY;
    /** 当前排序上下文：档位、方向与玩家位置。 */
    private FactorySortContext sortContext = FactorySortContext.defaults();
    private List<BlockView> blocks = List.of();

    /**
     * 一条 IO 规则在界面上的样子。
     *
     * @param rule 服务端算好的规则行（速率与原因已按成员聚合）
     */
    record RuleRow(ViewIoRule rule) {
        /** 方向。 */
        IoSide side() {
            return this.rule.side();
        }

        /** 该方向内的下标，用来回传动作。 */
        int index() {
            return this.rule.order();
        }

        /** 资源。 */
        AEKey key() {
            return this.rule.key();
        }

        /** 访问面；{@code null} 表示任意。 */
        @Nullable
        Direction face() {
            return this.rule.face();
        }

        /** 匹配模式。 */
        MatchMode mode() {
            return this.rule.mode();
        }

        /** 速率（数量/秒，已按成员求和）。 */
        long rate() {
            return this.rule.rate();
        }

        /** 本轮原因（已按成员取最坏）。 */
        IoReason reason() {
            return this.rule.reason();
        }

        /** 是否输入。 */
        boolean input() {
            return this.rule.side() == IoSide.INPUT;
        }
    }

    /**
     * 一个节点在内容区里的一块：机器行 + 规则行 + 虚线新增行。
     *
     * @param node   节点行（身份、健康、计数与规则都在里面）
     * @param status 节点状态的展示映射
     * @param rules  输入在前、输出在后（节点规则）
     */
    record BlockView(ViewNode node, FactoryStatus status, List<RuleRow> rules) {
        BlockView {
            rules = List.copyOf(rules);
        }

        /** 代表机器（节点第一台），图标 / 坐标 / 搜索字段用它。 */
        ViewMachine machine() {
            return this.node.machines().get(0);
        }

        /** 节点下的全部机器；堆叠时长度 > 1。 */
        List<ViewMachine> members() {
            return this.node.machines();
        }

        /** 机器 id；发机器级动作要用。 */
        UUID machineId() {
            return this.machine().machineId();
        }

        /** 节点 id；规则动作按节点发。 */
        UUID nodeId() {
            return this.node.nodeId();
        }

        /** 节点机器数；堆叠数量标识用它。 */
        int machineCount() {
            return this.node.machineCount();
        }

        /** 节点显示名；优先节点名，否则方块名；失联时直接写「已失联」。 */
        String name() {
            if (this.node.core().name() != null && !this.node.core().name().isEmpty()) {
                return this.node.core().name();
            }
            return this.node.core().health() == NodeHealth.OFFLINE
                    ? "已失联" : FactoryLabels.blockName(this.machine().blockId());
        }
    }

    /**
     * @param theme 当前主题，状态文字与搜索字段取色用
     */
    FactoryPassiveIoModel(FactoryGuiTheme theme) {
        this.theme = theme;
    }

    /**
     * 接收一张新的视图表。
     *
     * @param table 视图表
     */
    void accept(ViewTable table) {
        this.hasFactory = table.header().hasFactory();
        this.storageOnline = table.header().storageOnline();
        this.nodes = table.nodes();
        rebuild();
    }

    /**
     * 更新搜索词并重建视图。
     *
     * @param next 新搜索条件
     */
    void setQuery(@Nullable SearchQuery next) {
        this.query = next == null ? SearchQuery.EMPTY : next;
        rebuild();
    }

    /**
     * 更新排序上下文；只记录，不重建。调用方随后自己触发一次 {@link #resort()}。
     *
     * @param next 新上下文
     */
    void setSortContext(FactorySortContext next) {
        this.sortContext = next;
    }

    /**
     * 按当前表与排序上下文重新排序。
     *
     * <p>由排序按钮与快照接收方调用；滚动与排版由屏幕在之后重算。</p>
     */
    void resort() {
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
        return !this.nodes.isEmpty();
    }

    /** 当前搜索词是否命中任何节点。 */
    boolean filteredEmpty() {
        return !this.query.isEmpty() && this.blocks.isEmpty();
    }

    /**
     * 按当前搜索词与表重建块列表。
     *
     * <p>顺序：先过滤、再排序；规则行与成员行不参与排序。</p>
     *
     * <p>过滤只作用于节点层：命中一个节点就把整个节点铺开，不做规则级过滤。被动 IO 终端不看
     * 样板数，搜索字段里省掉那一段。</p>
     */
    private void rebuild() {
        List<BlockView> next = new ArrayList<>(this.nodes.size());
        for (ViewNode node : this.nodes) {
            if (!this.query.isEmpty()
                    && !this.query.matches(FactoryNodeSearchFields.of(node, this.theme, -1))) {
                continue;
            }
            next.add(new BlockView(node, FactoryStatus.of(node.core(), this.theme), rules(node)));
        }
        this.blocks = FactorySortKey.sorted(next, this::sortKey, this.sortContext);
    }

    /**
     * 取一块的排序键。
     *
     * <p>显示名用「自定义名 → 类型方块名」，不用卡片上的「已失联」占位文本，保证和节点管理
     * 终端对同一个节点排出来一致。</p>
     *
     * @param block 节点块
     * @return 排序键
     */
    private FactorySortKey sortKey(BlockView block) {
        long nearest = FactorySortKey.UNREACHABLE_DISTANCE;
        for (ViewMachine machine : block.members()) {
            long distance = FactorySortKey.distance(this.sortContext.playerDimension(),
                    this.sortContext.playerPos(), machine.block().dimension(), machine.block().pos());
            nearest = Math.min(nearest, distance);
        }
        return new FactorySortKey(FactorySortKey.displayName(block.node().core().name(),
                        block.node().core().typeBlockId()),
                block.node().core().typeBlockId().toString(), block.machineCount(),
                block.node().ruleCount(), FactoryStatus.sortRank(block.node().core()), nearest,
                block.nodeId());
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
