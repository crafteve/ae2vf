package io.github.crafteve.idlefactory.factory.probe;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.location.BlockKey;

import net.minecraft.resources.ResourceLocation;

/**
 * 一个方块的只读观测事实。
 *
 * <p>这是观测层对外的唯一形状：健康解析器只依赖本接口，不再每 tick 把观测投影成第二份记录
 * 对象。{@code Observation} 直接实现它，「事实只存一份」由此成立。</p>
 */
public interface BlockFacts {
    /**
     * 是否探测过。
     *
     * @return 探测过为 {@code true}；没探过时其余判断一律视为不成立
     */
    boolean probed();

    /**
     * 方块是否就绪。
     *
     * @return 健康为 PRESENT 时为 {@code true}
     */
    boolean present();

    /**
     * 健康是否已经过期。
     *
     * <p>公式：{@code probed && now - lastProbeTick > staleHealthTicks}。从未探测过时恒为
     * {@code false}——「没探过」由 {@link #probed()} 表达，不能拿 -1 哨兵算成过期。</p>
     *
     * @param now              当前观测时钟
     * @param staleHealthTicks 过期阈值（tick）
     * @return 探过且超过阈值时为 {@code true}
     */
    boolean isStale(long now, long staleHealthTicks);

    /**
     * 观测到的方块 id。
     *
     * @return 注册名；未加载或未探测时为 {@code null}
     */
    @Nullable
    ResourceLocation blockId();

    /**
     * 按位置取观测事实的来源。
     *
     * <p>实现方保证「没登记过」返回未探测的空事实而不是 {@code null}，调用方不必判空。</p>
     */
    @FunctionalInterface
    interface Source {
        /**
         * 取一个位置的观测事实。
         *
         * @param key 位置键
         * @return 观测事实；没有记录时返回未探测的空事实
         */
        BlockFacts at(BlockKey key);
    }
}
