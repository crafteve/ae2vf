package io.github.crafteve.idlefactory.factory.probe;

/**
 * 机器是否在工作的观测结论。
 *
 * <p>这是方块实体自身的事实，不是 IO 搬运结果。没有可靠探针时必须保持
 * {@link #UNKNOWN}，不能拿 IO 报错反推“空闲”。</p>
 */
public enum WorkState {
    /** 明确在工作。 */
    WORKING,
    /** 明确不在工作。 */
    IDLE,
    /** 没有可靠的探测手段。 */
    UNKNOWN
}
