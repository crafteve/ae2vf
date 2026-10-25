package io.github.crafteve.idlefactory.factory.resource;

/**
 * 仓库和 IO 认的资源类型。
 *
 * <p>{@link #CHEMICAL} 目前只占位：Mekanism 的化学品是独立 registry，等接入时再实现，
 * 现在配了规则也不会执行。</p>
 */
public enum ResourceKind {
    /** 物品，单位「个」。 */
    ITEM,
    /** 流体，单位 mB。 */
    FLUID,
    /** 能量，单位 FE。 */
    ENERGY,
    /** 化学品，预留。 */
    CHEMICAL
}
