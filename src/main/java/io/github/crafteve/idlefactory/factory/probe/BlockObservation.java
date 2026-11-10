package io.github.crafteve.idlefactory.factory.probe;

import org.jetbrains.annotations.Nullable;

import net.minecraft.resources.ResourceLocation;

/**
 * 一个方块的只读观测投影：健康、是否探过、最后一次探测的时刻。
 *
 * <p>它不是存档数据，每 tick 由 {@code FactoryObservationRunner} 从观测服务现构造。健康判定
 * 只在这里回答两个问题：「探过没有」与「探过但过期没有」；过期公式只写在
 * {@link #isStale(long, long)} 一处，机器 / 节点解析器只调用它，不重复实现。</p>
 *
 * @param blockId       观测到的方块 id；未加载或未探测时为 {@code null}
 * @param health        方块级健康
 * @param probed        是否探测过
 * @param lastProbeTick 最后一次探测的游戏时间；没探过为 {@code -1}
 */
public record BlockObservation(@Nullable ResourceLocation blockId, Health health, boolean probed,
        long lastProbeTick) {

    /**
     * 规范化：未探测时 {@code probed=false}、时刻为 -1，避免把哨兵值当成真实时刻。
     *
     * @param blockId       方块 id
     * @param health        方块健康
     * @param probed        是否探测过
     * @param lastProbeTick 最后探测时刻
     */
    public BlockObservation {
        if (health == Health.UNKNOWN) {
            probed = false;
        }
        if (!probed) {
            lastProbeTick = -1L;
        }
    }

    /**
     * 健康是否已经过期。
     *
     * <p>公式：{@code probed && now - lastProbeTick > staleHealthTicks}。从未探测过时恒为
     * {@code false}——「没探过」由 {@code probed=false} 表达，不能拿 {@code -1} 哨兵算成过期。</p>
     *
     * @param now              当前游戏时间
     * @param staleHealthTicks 过期阈值（tick）
     * @return 探过且超过阈值时为 {@code true}
     */
    public boolean isStale(long now, long staleHealthTicks) {
        if (!this.probed || this.lastProbeTick < 0L) {
            return false;
        }
        return now - this.lastProbeTick > staleHealthTicks;
    }

    /**
     * 方块是否就绪。
     *
     * @return 健康为 {@link Health#PRESENT} 时为 {@code true}
     */
    public boolean present() {
        return this.health == Health.PRESENT;
    }

}
