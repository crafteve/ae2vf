package io.github.crafteve.idlefactory.factory.capability;

/**
 * 方块提供的能力大类。
 *
 * <p>观测层只关心「这个面有没有物品 / 流体 / 能量能力」，与「资源身份」是两件事：
 * 资源身份已经统一成 AE2 的 {@code AEKey}，而能力分类用来筛面表与界面图标。所以这里单独
 * 建模，不再复用资源类型枚举。</p>
 *
 * <p>化学品不在这里占位：它的可用性取决于是否装了对应的 AE 生态附属，而不是我们支不支持。</p>
 */
public enum CapabilityKind {
    /** 物品能力（{@code IItemHandler}）。 */
    ITEM,
    /** 流体能力（{@code IFluidHandler}）。 */
    FLUID,
    /** 能量能力（{@code IEnergyStorage}）。 */
    ENERGY
}
