package io.github.crafteve.idlefactory.view;

import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

/**
 * 整张视图表：表头 + 节点行。服务端每次推送时临时组装，不写档。
 *
 * <p>读侧只有这一条通道：全量（本类）与差量（{@link ViewTableDiff}）。被动 IO 终端按节点
 * 分块读，节点终端直接读节点行；两者都不再各自拼一份状态。</p>
 *
 * @param header 表头
 * @param nodes  节点行，顺序与节点库登记顺序一致
 */
public record ViewTable(ViewHeader header, List<ViewNode> nodes) {

    public ViewTable {
        nodes = List.copyOf(nodes);
    }

    /**
     * 玩家还没有工厂时的空表。
     *
     * <p>空表与"有工厂但没节点"必须能区分：前者界面显示"未创建工厂"，后者显示空列表并允许
     * 添加节点，所以 {@code hasFactory} 不能省。</p>
     *
     * @return 空表
     */
    public static ViewTable empty() {
        return new ViewTable(new ViewHeader(false, false), List.of());
    }

    /**
     * 按节点 id 找节点行。
     *
     * @param nodeId 节点 id
     * @return 节点行；不在表里时为 {@code null}
     */
    @Nullable
    public ViewNode nodeOf(UUID nodeId) {
        for (ViewNode node : this.nodes) {
            if (node.nodeId().equals(nodeId)) {
                return node;
            }
        }
        return null;
    }
}
