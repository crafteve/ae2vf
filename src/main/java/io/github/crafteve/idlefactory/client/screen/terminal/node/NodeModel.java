package io.github.crafteve.idlefactory.client.screen.terminal.node;

import io.github.crafteve.idlefactory.client.screen.terminal.TerminalSource;

import io.github.crafteve.idlefactory.client.screen.terminal.TerminalModel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.crafteve.idlefactory.client.screen.UiResources;
import io.github.crafteve.idlefactory.client.screen.text.TerminalText;
import io.github.crafteve.idlefactory.client.screen.search.SearchFields;
import io.github.crafteve.idlefactory.client.screen.paint.StatusView;
import io.github.crafteve.idlefactory.client.screen.search.SearchQuery;
import io.github.crafteve.idlefactory.client.screen.sort.SortKey;
import io.github.crafteve.idlefactory.view.ViewMachine;
import io.github.crafteve.idlefactory.view.ViewNode;
import io.github.crafteve.idlefactory.view.ViewTable;

/**
 * 节点终端的只读内容模型：把视图表展开成平铺的节点行。
 *
 * <p>不做分组、不按状态重排：顺序跟随视图表的节点顺序（服务端按节点库登记顺序给出），否则
 * 每秒快照都会让卡片跳动。节点内部的机器行是节点自己的成员，不单独参与过滤——命中一个节点
 * 就把它的成员一起铺开。</p>
 *
 * <p>搜索与排序状态在 {@link TerminalModel} 里，本类只负责"表 → 行"和"行的搜索 / 排序字段"。</p>
 */
final class NodeModel extends TerminalModel<NodeModel.NodeRow> {
    /**
     * 一台机器在节点终端里的样子。
     *
     * @param machine 成员机器
     * @param status  状态展示
     * @param label   行标签；多机时是 #1、#2
     */
    record MemberRow(ViewMachine machine, StatusView status, String label) {
    }

    /**
     * 一个节点在内容区里的一行。
     *
     * @param view    节点行
     * @param status  状态展示
     * @param name    显示名（方块名或自定义名）
     * @param members 机器行
     */
    record NodeRow(ViewNode view, StatusView status, String name,
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
     * @param source 终端数据源
     */
    NodeModel(TerminalSource source) {
        super(source);
    }

    /** 当前可见的节点行对应的视图，顺序与 {@link #rows()} 一致。 */
    List<ViewNode> visibleNodes() {
        List<ViewNode> nodes = new ArrayList<>(rows().size());
        for (NodeRow row : rows()) {
            nodes.add(row.view());
        }
        return nodes;
    }

    /** 是否有节点（未过滤前）。 */
    boolean hasNodes() {
        return !rows().isEmpty();
    }

    @Override
    protected List<NodeRow> buildRows(ViewTable table) {
        List<NodeRow> rows = new ArrayList<>(table.nodes().size());
        for (ViewNode view : table.nodes()) {
            StatusView status = StatusView.of(view.core());
            rows.add(new NodeRow(view, status, displayName(view), members(view, status)));
        }
        return rows;
    }

    @Override
    protected SearchQuery.Fields fields(NodeRow row) {
        return SearchFields.of(row.view(), row.view().core().patternCount());
    }

    /**
     * 取一行的排序键。
     *
     * <p>距离取最近的成员机器；显示名用「自定义名 → 方块名」，与被动 IO 终端对同一个节点排出来
     * 一致。</p>
     */
    @Override
    protected SortKey sortKey(NodeRow row) {
        ViewNode view = row.view();
        long nearest = SortKey.UNREACHABLE_DISTANCE;
        ViewMachine nearestMachine = null;
        for (ViewMachine machine : view.machines()) {
            long distance = SortKey.distance(playerDimension(), playerPosition(),
                    machine.block().dimension(), machine.block().pos());
            if (distance < nearest) {
                nearest = distance;
                nearestMachine = machine;
            }
        }
        return new SortKey(row.name(), view.core().typeBlockId().toString(),
                view.machineCount(), view.ruleCount(), StatusView.sortRank(view.core()), nearest,
                row.nodeId());
    }

    @Override
    protected UUID rowId(NodeRow row) {
        return row.nodeId();
    }

    private List<MemberRow> members(ViewNode view, StatusView status) {
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
                : UiResources.blockName(view.core().typeBlockId());
    }
}
