package io.github.crafteve.idlefactory.observation;

/**
 * 一台机器的健康：在线或离线。
 *
 * <p>只有两个值。机器由若干方块组成，全部方块都就绪且类型方块 id 与节点类型一致才算在线；
 * 任一方块不可用即离线。「未加载 / 已拆 / 维度不存在」这些区别留在方块级 {@link Health}，
 * 只用于诊断与 tooltip，不再升级成机器状态。</p>
 */
public enum MachineHealth {
    /** 离线：任一方块不可用，或还没探测过。 */
    OFFLINE,
    /** 在线：所有方块都就绪。 */
    ONLINE;

    /** 是否在线。 */
    public boolean isOnline() {
        return this == ONLINE;
    }

    /** 给节点终端、机器表与 tooltip 共用的一句话。 */
    public String describe() {
        return switch (this) {
            case OFFLINE -> "离线";
            case ONLINE -> "在线";
        };
    }
}
