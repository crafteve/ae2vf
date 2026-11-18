package io.github.crafteve.idlefactory.io;

/**
 * 一次机器侧搬运尝试的结果原因。
 *
 * <p>它只回答「这一次尝试为什么是这个结果」，是账本行上的标签，也是执行器对上级的唯一回答。
 * 世界态（方块在不在、区块加载没加载）不在这里——那是 {@code MachineStatus} 的事；机器一旦
 * 离开表单，它的条目与账本一起释放，不会产生「不搬的原因」。</p>
 *
 * <p>{@link #RUN} 既包含「搬了」也包含「跑了但没量」，有没有量看账本行的数量字段。</p>
 */
public enum IoReason {
    /** 这一轮正常执行。 */
    RUN,
    /** 执行层择时判定本轮不跑（连续空转，见条目的结论）。 */
    SLEEPING,
    /** 探不到可用的面。 */
    NO_FACE,
    /** 候选或探测预算耗尽，本轮没排上。 */
    BUDGET_OUT,
    /** 仓库或机器没有这个资源。 */
    NO_MATERIAL,
    /** 机器或仓库满了。 */
    FULL,
    /** 有容量却不收（面不对），当场清掉面记忆。 */
    REJECTED,
    /** 异常；退款失败也归这里。 */
    ERROR,
    /** 玩家配的面没有这个能力（配置错误），不自动换别的面重探。 */
    FACE_CONFIG_INVALID;

    /**
     * 严重度：越大越需要用户注意，用于「一列原因取最坏的那个」。
     *
     * <p>全仓只在这一处维护这张表：执行器聚合、服务端视图聚合、客户端聚合都调它。</p>
     *
     * @return 严重度；正常与休息为 0
     */
    public int severity() {
        return switch (this) {
            case ERROR -> 5;
            case REJECTED, FACE_CONFIG_INVALID -> 4;
            case NO_FACE -> 3;
            case FULL -> 2;
            case NO_MATERIAL -> 1;
            case RUN, SLEEPING, BUDGET_OUT -> 0;
        };
    }
}
