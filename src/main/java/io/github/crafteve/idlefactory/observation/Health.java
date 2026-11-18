package io.github.crafteve.idlefactory.observation;

/**
 * 一个方块位置的观测结论。
 *
 * <p>注意这里<b>没有</b>「已断开」这个值：断开是玩家的意图，属于配置层，不属于世界事实。
 * 观测只回答「世界现在是什么样」。</p>
 */
public enum Health {
    /** 还没探测过。服务器刚启动、或这个键刚被配置引用。 */
    UNKNOWN,
    /** 在，而且是一台能用的机器 / 容器。 */
    PRESENT,
    /** 区块没加载——<b>不等于没了</b>，只是不知道。 */
    UNLOADED,
    /** 区块加载了，但这个位置已经没有那台机器（方块被拆、被换成了没有能力的方块）。 */
    MISSING,
    /** 维度不存在（datapack 改了、维度被删）。 */
    NO_DIMENSION;

    /** 是否「真的在」。 */
    public boolean alive() {
        return this == PRESENT;
    }

    /** 是否「确实不在了」。用于失联标记与清理判定；{@link #UNLOADED} 不算。 */
    public boolean gone() {
        return this == MISSING || this == NO_DIMENSION;
    }

    /** 给指令和界面用的一句话。 */
    public String describe() {
        return switch (this) {
            case UNKNOWN -> "未探测";
            case PRESENT -> "就绪";
            case UNLOADED -> "未加载";
            case MISSING -> "方块已消失";
            case NO_DIMENSION -> "维度不存在";
        };
    }
}
