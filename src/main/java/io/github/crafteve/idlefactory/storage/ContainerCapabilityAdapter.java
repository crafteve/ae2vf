package io.github.crafteve.idlefactory.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * 方块容器判定：一个位置能不能当仓库容器。
 *
 * <p>判定顺序与原实现一致：物品 handler、流体 handler、能量 handler 任一存在即算容器，
 * 最后兜底原版 {@link Container}。它只看能力，不解析后端、不碰观测。</p>
 */
public final class ContainerCapabilityAdapter {
    private ContainerCapabilityAdapter() {
        // 纯静态工具类
    }

    /**
     * 这个位置是不是可绑定的仓库容器。
     *
     * @param level 服务端世界
     * @param pos   方块坐标
     * @return 是容器时为 {@code true}
     */
    public static boolean isContainer(ServerLevel level, BlockPos pos) {
        if (level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null) != null) {
            return true;
        }
        if (level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null) != null) {
            return true;
        }
        if (level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, null) != null) {
            return true;
        }
        return level.getBlockEntity(pos) instanceof Container;
    }
}
