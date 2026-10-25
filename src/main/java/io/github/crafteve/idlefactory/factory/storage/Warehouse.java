package io.github.crafteve.idlefactory.factory.storage;

import net.minecraft.world.item.ItemStack;

/**
 * 工厂眼中的「仓库」：一个只进不出的容器接口。
 *
 * <p>工厂不实现存储，也不关心仓库背后是 AE2、是别的模组、还是空实现。它只会问两件事：
 * 「这些物品你能收多少」「这些物品你能给多少」。所有实现都必须是<b>事务安全</b>的：
 * {@code simulate} 为 {@code true} 时不得产生任何副作用。</p>
 *
 * <p>第一版只支持物品；以后扩流体 / 能量时，把方法签名换成 {@code ResourceType + amount}
 * 即可，工厂主体不用动。</p>
 */
public interface Warehouse {
    /**
     * 往仓库里放东西。
     *
     * @param stack    物品样本（数量会被忽略）
     * @param amount   想放几个，必须为正
     * @param simulate 为 {@code true} 时只回报能收多少，不产生副作用
     * @return 实际收下的数量（{@code 0..amount}）
     */
    long insert(ItemStack stack, long amount, boolean simulate);

    /**
     * 从仓库里拿东西。
     *
     * @param stack    物品样本（数量会被忽略）
     * @param amount   想拿几个，必须为正
     * @param simulate 为 {@code true} 时只回报能拿多少，不产生副作用
     * @return 实际拿出的数量（{@code 0..amount}）
     */
    long extract(ItemStack stack, long amount, boolean simulate);

    /** 仓库当前是否完全没有内容（只用于界面展示，不参与搬运判断）。 */
    boolean isEmpty();
}
