package io.github.crafteve.idlefactory.factory.machine;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 机器库的只读视图：工厂配置对外只暴露这一层。
 *
 * <p>写入口只有 {@code MachineLibraryService}（运行时）与读档时的
 * {@code Factory#restoreMachine}；调用方拿不到 {@link MachineLibrary} 本身，
 * 也就不会出现「改了机器库忘记落盘」的漏法。</p>
 */
public interface MachineLibraryView {
    /**
     * 按坐标找机器。
     *
     * @param dimension 维度
     * @param pos       坐标
     * @return 机器；不在库里时为 {@code null}
     */
    Machine byPosition(ResourceKey<Level> dimension, BlockPos pos);

    /**
     * 按短 id 找机器。
     *
     * @param shortId 短 id
     * @return 机器；找不到或前缀有歧义时为空
     */
    Optional<Machine> byShortId(String shortId);

    /**
     * 按 id 查。
     *
     * @param id 机器 id
     * @return 机器；不在库里时为空
     */
    Optional<Machine> byId(UUID id);

    /**
     * 所有机器（登记顺序）。
     *
     * @return 只读列表
     */
    List<Machine> all();

    /** @return 机器数量 */
    int size();

    /** @return 是否为空 */
    boolean isEmpty();
}
