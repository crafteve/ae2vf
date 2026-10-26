package io.github.crafteve.idlefactory.factory.observation;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 观测索引键：维度 + 坐标。
 *
 * <p>跨维度、跨工厂唯一——同一台机器就算被两个工厂引用，观测也只有一份。</p>
 *
 * @param dimension 维度
 * @param pos       方块坐标（构造时转成不可变副本）
 */
public record ObservationKey(ResourceKey<Level> dimension, BlockPos pos) {

    public ObservationKey {
        pos = pos.immutable();
    }

    /**
     * 按「有位置的东西」建键。
     *
     * @param located 机器引用、容器引用或任何 {@link Located}
     * @return 观测键
     */
    public static ObservationKey of(Located located) {
        return new ObservationKey(located.dimension(), located.pos());
    }
}
