package io.github.crafteve.idlefactory.factory.target;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * 机器方块的能力检查。
 *
 * <p>「能接入机器库」的判定：方块实体存在，且至少提供一个 {@code IItemHandler}、
 * {@code IFluidHandler} 或 {@code IEnergyStorage}。检查永远现查，不缓存——方块随时可能被换成别的东西。</p>
 */
public final class MachineChecks {
    private MachineChecks() {
        // 纯静态工具类
    }

    /**
     * 这个坐标上的方块现在是不是一台可接入的机器。
     *
     * @param level 世界
     * @param pos   方块坐标
     * @return 有方块实体且提供物品/流体能力时为 {@code true}
     */
    public static boolean isSupported(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) == null) {
            return false;
        }
        return supportsItems(level, pos) || supportsFluids(level, pos) || supportsEnergy(level, pos);
    }

    /** 是否提供物品能力。 */
    public static boolean supportsItems(Level level, BlockPos pos) {
        return level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null) != null;
    }

    /** 是否提供流体能力。 */
    public static boolean supportsFluids(Level level, BlockPos pos) {
        return level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null) != null;
    }

    /** 是否提供能量能力。 */
    public static boolean supportsEnergy(Level level, BlockPos pos) {
        return level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, null) != null;
    }

    /**
     * 给界面用的能力摘要。
     *
     * @param level 世界
     * @param pos   坐标
     * @return {@code item} / {@code fluid} / {@code item+fluid} / {@code none}
     */
    public static String describeCapabilities(Level level, BlockPos pos) {
        boolean items = supportsItems(level, pos);
        boolean fluids = supportsFluids(level, pos);
        boolean energy = supportsEnergy(level, pos);
        StringBuilder builder = new StringBuilder();
        if (items) {
            builder.append("item");
        }
        if (fluids) {
            builder.append(builder.length() > 0 ? "+fluid" : "fluid");
        }
        if (energy) {
            builder.append(builder.length() > 0 ? "+energy" : "energy");
        }
        return builder.length() == 0 ? "none" : builder.toString();
    }
}
