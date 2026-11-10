package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.client.screen.FactoryGuiTheme;
import io.github.crafteve.idlefactory.client.screen.FactoryLabels;
import io.github.crafteve.idlefactory.client.screen.FactorySearchQuery;
import io.github.crafteve.idlefactory.client.screen.FactoryStatus;
import io.github.crafteve.idlefactory.client.screen.FactoryUiText;
import io.github.crafteve.idlefactory.factory.FactoryNodeSnapshotBuilder;
import io.github.crafteve.idlefactory.network.payload.FactoryNodeTableS2C;

/**
 * 节点终端的只读内容模型：一份节点表 + 搜索词 → 平铺的节点行。
 *
 * <p>不做分组、不按状态重排：顺序跟随机器库登记顺序，否则每秒快照都会让卡片跳动。节点内部的
 * 机器行是节点自己的成员，不单独参与过滤——命中一个节点就把它的成员一起铺开。</p>
 */
final class FactoryNodeModel {
    private final FactoryGuiTheme theme;
    private List<FactoryNodeSnapshotBuilder.NodeView> nodes = List.of();
    private boolean hasFactory;
    private boolean meStorage;
    private boolean storageOnline;
    private FactorySearchQuery query = FactorySearchQuery.EMPTY;
    private List<NodeRow> rows = List.of();

    /**
     * 一台机器在节点终端里的样子。
     *
     * @param member 成员视图
     * @param status 状态展示
     * @param label  行标签；多机时是 #1、#2，单机时是方块名
     */
    record MemberRow(FactoryNodeSnapshotBuilder.MemberView member, FactoryStatus status,
            String label) {
    }

    /**
     * 一个节点在内容区里的一行。
     *
     * @param view   节点视图
     * @param status 状态展示
     * @param name   显示名（自定义名或方块名）
     * @param members 机器行
     */
    record NodeRow(FactoryNodeSnapshotBuilder.NodeView view, FactoryStatus status, String name,
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
     * 接收一份新的节点表。
     *
     * @param payload 节点表快照
     */
    void accept(FactoryNodeTableS2C payload) {
        this.hasFactory = payload.hasFactory();
        this.meStorage = payload.meStorage();
        this.storageOnline = payload.storageOnline();
        this.nodes = payload.nodes();
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

    /** 当前可见节点。 */
    List<NodeRow> rows() {
        return this.rows;
    }

    /** 服务端是否已经有工厂。 */
    boolean hasFactory() {
        return this.hasFactory;
    }

    /** 仓库是否是 ME 模式。 */
    boolean meStorage() {
        return this.meStorage;
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
     * 按当前搜索词与快照重建节点行。
     */
    private void rebuild() {
        List<NodeRow> next = new ArrayList<>(this.nodes.size());
        for (FactoryNodeSnapshotBuilder.NodeView view : this.nodes) {
            String name = displayName(view);
            FactoryStatus status = FactoryStatus.of(view, this.theme);
            if (!this.query.isEmpty() && !this.query.matches(fields(view, name, status))) {
                continue;
            }
            next.add(new NodeRow(view, status, name, members(view)));
        }
        this.rows = List.copyOf(next);
    }

    private List<MemberRow> members(FactoryNodeSnapshotBuilder.NodeView view) {
        List<MemberRow> rows = new ArrayList<>(view.members().size());
        for (int i = 0; i < view.members().size(); i++) {
            FactoryNodeSnapshotBuilder.MemberView member = view.members().get(i);
            rows.add(new MemberRow(member, FactoryStatus.of(view, this.theme), "#" + (i + 1)));
        }
        return rows;
    }

    /**
     * 节点显示名：自定义名字优先，否则用方块名。
     *
     * @param view 节点视图
     * @return 显示名
     */
    static String displayName(FactoryNodeSnapshotBuilder.NodeView view) {
        return view.name() != null ? view.name() : FactoryLabels.blockName(view.typeBlockId());
    }

    private static FactorySearchQuery.Fields fields(FactoryNodeSnapshotBuilder.NodeView view,
            String name, FactoryStatus status) {
        String id = view.typeBlockId().toString();
        String namespace = view.typeBlockId().getNamespace();
        StringBuilder tooltip = new StringBuilder(status.text());
        FactoryNodeSnapshotBuilder.MemberView first =
                view.members().isEmpty() ? null : view.members().get(0);
        if (first != null) {
            tooltip.append('\n').append(first.dimensionId()).append(' ')
                    .append(first.primaryPos() == null ? "" : first.primaryPos().toShortString());
        }
        tooltip.append("\n机器 ").append(view.machineCount())
                .append(" 台 · 规则 ").append(view.ruleCount())
                .append(" 条 · 样板 ").append(view.patternCount());
        return new FactorySearchQuery.Fields(name, id, namespace,
                FactoryUiText.modName(namespace), tooltip.toString(), List.of());
    }
}
