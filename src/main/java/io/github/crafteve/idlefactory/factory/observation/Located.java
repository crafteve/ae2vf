package io.github.crafteve.idlefactory.factory.observation;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 世界里占一个方块位置的东西：机器引用、容器引用、以后的接线端子。
 *
 * <p>它存在的唯一理由是让 {@link ObservationService} 只认「位置」、不认「角色」。于是观测层
 * 不需要 import 机器包或存储包，依赖方向保持单向——是「配置」依赖「观测的键」，而不是反过来。</p>
 *
 * <p>实现者是值对象，必须不可变：{@link #dimension()} 与 {@link #pos()} 在生命周期内不能变。</p>
 */
public interface Located {
    /** 所在维度。 */
    ResourceKey<Level> dimension();

    /** 方块坐标。 */
    BlockPos pos();
}
