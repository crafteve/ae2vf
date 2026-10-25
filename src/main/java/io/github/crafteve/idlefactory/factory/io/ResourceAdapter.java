package io.github.crafteve.idlefactory.factory.io;

import io.github.crafteve.idlefactory.factory.resource.ResourceKey;

/**
 * 机器侧的一个资源接口。
 *
 * <p>把 {@code IItemHandler}、{@code IFluidHandler}、{@code IEnergyStorage} 抹平成同一组操作，
 * 让 {@link IoTransfer} 只写一份事务逻辑。</p>
 */
public interface ResourceAdapter {
    /** 这个适配器对应的资源。 */
    ResourceKey key();

    /** 机器现在还能收多少。 */
    long capacity();

    /**
     * 往机器里塞。
     *
     * @param amount   想塞多少
     * @param simulate 是否只模拟
     * @return 实际塞进去的数量
     */
    long insert(long amount, boolean simulate);

    /**
     * 从机器里抽。
     *
     * @param amount   想抽多少
     * @param simulate 是否只模拟
     * @return 实际抽出的数量
     */
    long extract(long amount, boolean simulate);
}
