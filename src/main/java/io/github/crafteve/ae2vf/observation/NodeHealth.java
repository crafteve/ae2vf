package io.github.crafteve.ae2vf.observation;

/**
 * 一个节点的健康：在线、缺机或离线，由成员机器的健康聚合而来。
 *
 * <p>全部机器在线 = 在线；部分在线 = 缺机；没有一台在线 = 离线。未探测过的机器按离线计，
 * 「没探过」这件事由视图的 {@code probed} 字段表达，不在这里多开一个状态。</p>
 */
public enum NodeHealth {
    /** 离线：没有一台机器在线。 */
    OFFLINE,
    /** 缺机：至少一台在线，至少一台离线。 */
    PARTIAL,
    /** 在线：所有机器都在线。 */
    ONLINE;

    /** 是否在线。 */
    public boolean isOnline() {
        return this == ONLINE;
    }

    /** 给节点终端、机器表与 tooltip 共用的一句话。 */
    public String describe() {
        return switch (this) {
            case OFFLINE -> "离线";
            case PARTIAL -> "缺机";
            case ONLINE -> "在线";
        };
    }
}
