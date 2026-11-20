package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.client.screen.FactoryGuiTheme;
import io.github.crafteve.idlefactory.client.screen.FactoryLabels;
import io.github.crafteve.idlefactory.client.screen.FactoryNodeSearchFields;
import io.github.crafteve.idlefactory.client.screen.FactorySearchQuery;
import io.github.crafteve.idlefactory.client.screen.FactorySortContext;
import io.github.crafteve.idlefactory.client.screen.FactorySortKey;
import io.github.crafteve.idlefactory.client.screen.FactoryStatus;
import io.github.crafteve.idlefactory.view.ViewMachine;
import io.github.crafteve.idlefactory.view.ViewNode;
import io.github.crafteve.idlefactory.view.ViewTable;

/**
 * 节点终端的只读内容模型：一张视图表 + 搜索词 → 平铺的节点行。
 *
 * <p>不做分组、不按状态重排：顺序跟随视图表的节点顺序（服务端按节点库登记顺序给出），否则
 * 每秒快照都会让卡片跳动。节点内部的机器行是节点自己的成员，不单独参与过滤——命中一个节点
 * 就把它的成员一起铺开。</p>
 *
 * <p>第 3b 步起只读视图表：状态、规则数、待投递都已在服务端算好，本类不再聚合，也不再需要
 * {@code NodeStatusBuilder} 兜底。</p>
 */
final class FactoryNodeModel {
    private final FactoryGuiTheme theme;
    private List<ViewNode> nodes = List.of();
    private boolean hasFactory;
    private boolean storageOnline;
    private FactorySearchQuery query = FactorySearchQuery.EMPTY;
    /** 当前排序上下文：档位、方向与玩家位置。 */
    private FactorySortContext sortContext = FactorySortContext.defaults();
    /** 是否冻结顺序：改名框 / 删除确认框打开时不让列表在用户操作期间跳动。 */
    private boolean sortFrozen;
    private List<NodeRow> rows = List.of();

    /**
     * 一台机器在节点终端里的样子。
     *
     * @param machine 成员机器
     * @param status  状态展示
     * @param label   行标签；多机时是 #1、#2，单机时是方块名
     */
    record MemberRow(ViewMachine machine, FactoryStatus status, String label) {
    }

    /**
     * 一个节点在内容区里的一行。
     *
     * @param view    节点行
     * @param status  状态展示
     * @param name    显示名（方块名或自定义名）
     * @param members 机器行
     */
    record NodeRow(ViewNode view, FactoryStatus status, String name,
            List<MemberRow> members) {
        NodeRow {
            members = List.copyOf(members);
        }

        /** 节点 id；动作与展开状态都用它。 */
        UUID nodeId() {
            return this.view.nodeId();
        }
    }

    /**
     * @param theme 当前主题
     */
    FactoryNodeModel(FactoryGuiTheme theme) {
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
    void setQuery(@Nullable FactorySearchQuery next) {
        this.query = next == null ? FactorySearchQuery.EMPTY : next;
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
     * 冻结或解冻顺序；冻结时快照照常更新，但行序保持不变。
     *
     * @param frozen 是否冻结
     */
    void setSortFrozen(boolean frozen) {
        this.sortFrozen = frozen;
    }

    /**
     * 按当前表与排序上下文重新排序。
     *
     * <p>由排序按钮与快照接收方调用；滚动与排版由屏幕在之后重建。</p>
     */
    void resort() {
        rebuild();
    }

    /** 当前可见节点。 */
    List<NodeRow> rows() {
        return this.rows;
    }

    /**
     * 当前可见的节点行对应的视图，顺序与 {@link #rows()} 一致。
     *
     * <p>绘制与 tooltip 的命中下标都按可见行算，所以这里必须跟着过滤与排序走，不能直接返回
     * 表里的全部节点。</p>
     *
     * @return 可见节点
     */
    List<ViewNode> visibleNodes() {
        List<ViewNode> nodes = new ArrayList<>(this.rows.size());
        for (NodeRow row : this.rows) {
            nodes.add(row.view());
        }
        return nodes;
    }

    /** 服务端是否已经有工厂。 */
    boolean hasFactory() {
        return this.hasFactory;
    }

    /** 仓库是否在线。 */
    boolean storageOnline() {
        return this.storageOnline;
    }

    /** 是否有节点（未过滤前）。 */
    boolean hasNodes() {
        return !this.nodes.isEmpty();
    }

    /** 当前搜索词是否命中任何节点。 */
    boolean filteredEmpty() {
        return !this.query.isEmpty() && this.rows.isEmpty();
    }

    /**
     * 按当前搜索词与表重建节点行。
     *
     * <p>顺序：先过滤、再排序；成员行不参与排序。</p>
     */
    private void rebuild() {
        List<NodeRow> next = new ArrayList<>(this.nodes.size());
        for (ViewNode view : this.nodes) {
            FactoryStatus status = FactoryStatus.of(view.core(), this.theme);
            if (!this.query.isEmpty()
                    && !this.query.matches(FactoryNodeSearchFields.of(view, this.theme,
                            view.core().patternCount()))) {
                continue;
            }
            next.add(new NodeRow(view, status, displayName(view), members(view, status)));
        }
        if (this.sortFrozen) {
            this.rows = FactorySortKey.preserveOrder(next, this.rows, NodeRow::nodeId,
                    this::sortKey, this.sortContext);
        } else {
            this.rows = FactorySortKey.sorted(next, this::sortKey, this.sortContext);
        }
    }

    /**
     * 取一行的排序键。
     *
     * <p>距离取最近的成员机器；显示名用「自定义名 → 方块名」，与被动 IO 终端对同一个节点排出来
     * 一致。</p>
     *
     * @param row 节点行
     * @return 排序键
     */
    private FactorySortKey sortKey(NodeRow row) {
        ViewNode view = row.view();
        long nearest = FactorySortKey.UNREACHABLE_DISTANCE;
        for (ViewMachine machine : view.machines()) {
            long distance = FactorySortKey.distance(this.sortContext.playerDimension(),
                    this.sortContext.playerPos(), machine.block().dimension(), machine.block().pos());
            nearest = Math.min(nearest, distance);
        }
        return new FactorySortKey(row.name(), view.core().typeBlockId().toString(),
                view.machineCount(), view.ruleCount(),
                FactoryStatus.sortRank(view.core()), nearest, row.nodeId());
    }

    private List<MemberRow> members(ViewNode view, FactoryStatus status) {
        List<MemberRow> rows = new ArrayList<>(view.machines().size());
        for (int i = 0; i < view.machines().size(); i++) {
            rows.add(new MemberRow(view.machines().get(i), status, "#" + (i + 1)));
        }
        return rows;
    }

    /**
     * 节点显示名：自定义名字优先，否则用方块名。
     *
     * @param view 节点行
     * @return 显示名
     */
    static String displayName(ViewNode view) {
        return view.core().name() != null ? view.core().name()
                : FactoryLabels.blockName(view.core().typeBlockId());
    }
}
