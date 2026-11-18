package io.github.crafteve.idlefactory.observation;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.location.BlockKey;

import net.minecraft.resources.ResourceLocation;

/**
 * 一个方块的只读观测事实。
 *
 * <p>这是观测层对外的唯一形状：健康解析器、机器状态只依赖本接口，不再每 tick 把观测投影成
 * 第二份记录对象。{@code Observation} 直接实现它，「事实只存一份」由此成立。</p>
 */
public interface BlockFacts {
    /**
     * 方块级健康。
     *
     * @return 当前结论；从没探过时为 {@link Health#UNKNOWN}
     */
    Health health();

    /**
     * 是否探测过。
     *
     * @return 健康不是 UNKNOWN 时为 {@code true}
     */
    default boolean probed() {
        return this.health() != Health.UNKNOWN;
    }

    /**
     * 方块是否就绪。
     *
     * @return 健康为 {@link Health#PRESENT} 时为 {@code true}
     */
    default boolean present() {
        return this.health() == Health.PRESENT;
    }

    /**
     * 健康是否已经过期。
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
