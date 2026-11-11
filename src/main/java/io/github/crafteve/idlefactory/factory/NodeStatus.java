package io.github.crafteve.idlefactory.factory;

import io.github.crafteve.idlefactory.factory.probe.NodeHealth;

/**
 * 一个节点的体检汇总结论：由全部成员机器的事实聚合而来，与成员登记顺序无关。
 *
 * <p>这是节点状态在服务端与客户端的唯一表示。节点终端、被动 IO 终端、搜索、tooltip 与排序
 * 都只读这一份结论；谁都不许再自己拿"第一台"或"最后一台"成员拼一个状态。</p>
 *
 * <p>字段含义与聚合规则见 {@link NodeStatusResolver}：{@code probed} 要求全部成员探过，
 * {@code stale} 要求任一成员过期，{@code health} 按在线台数分档，{@code totalState} 按
 * "最需要管的那台"聚合。</p>
 *
 * <p>两个边界值也是契约的一部分：没有成员时 {@code probed = false}、{@code health = OFFLINE}、
 * {@code totalState = OFFLINE}，因为没有任何可观测事实；全员离线时 {@code totalState} 直接给
 * {@code OFFLINE}，不会出现"离线 + 未知"的组合交给下游。</p>
 *
 * @param probed     是否全部成员都探测过
 * @param stale      是否有成员健康过期
 * @param health     节点健康
 * @param totalState 节点在干什么
 */
public record NodeStatus(boolean probed, boolean stale, NodeHealth health,
        FactorySnapshotBuilder.TotalState totalState) {
}
