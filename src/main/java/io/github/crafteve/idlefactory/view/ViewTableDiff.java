package io.github.crafteve.idlefactory.view;

import java.util.List;

import org.jetbrains.annotations.Nullable;

/**
 * 两版视图表之间的差异：版本、可选的表头变化、节点差异列表。
 *
 * <p>{@code baseRevision} 是客户端应当持有的版本，{@code revision} 是应用本差异后的版本。
 * 两者都在报文里而不在 {@link ViewTable} 里：表头只放内容字段，版本属于传输状态。</p>
 *
 * @param baseRevision 差异基于的版本
 * @param revision     应用后的版本
 * @param header       表头变化；没变时为 {@code null}
 * @param nodes        节点差异
 */
public record ViewTableDiff(long baseRevision, long revision, @Nullable ViewHeader header,
        List<ViewNodeDiff> nodes) {

    public ViewTableDiff {
        nodes = List.copyOf(nodes);
    }

    /** 这次差异是不是空的（表头与节点都没变）。 */
    public boolean isEmpty() {
        return this.header == null && this.nodes.isEmpty();
    }
}
