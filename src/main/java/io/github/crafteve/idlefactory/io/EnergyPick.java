package io.github.crafteve.idlefactory.io;

import java.util.Map;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.key.AeKeyAdapter;

import appeng.api.behaviors.ExternalStorageStrategy;
import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.storage.MEStorage;
import appeng.parts.automation.StackWorldBehaviors;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * 接入时直接探一次 FE 能力，不依赖观测表。
 *
 * <p>观测表是「按 tick 扫描」的第二事实层：刚放下的方块还没被探到，只能读回未探测，
 * 自动补 FE 规则就会时灵时不灵。绑定是一次性操作，这里当场按六个面
 * 建一次外部存储并模拟插入 1000 FE，结果立刻可用，也不改观测缓存。</p>
 */
public final class EnergyPick {
    /** 探测用量：Mekanism 的 FE↔J 换算会在小数值上截断。 */
    private static final int PROBE = 1000;

    /** 外部存储包装用的空回调。 */
    private static final Runnable NOOP = () -> {
    };

    private EnergyPick() {
        // 纯静态查询
    }

    /**
     * 这个位置的方块是否能接收 FE。
     *
     * @param level 所在世界
     * @param pos   方块位置
     * @return 任一面模拟接收 FE 成功时为 {@code true}
     */
    public static boolean capable(ServerLevel level, BlockPos pos) {
        AEKey energy = AeKeyAdapter.energy();
        if (energy == null) {
            return false;
        }
        for (Direction face : Direction.values()) {
            if (capableOn(level, pos, face, energy)) {
                return true;
            }
        }
        return false;
    }

    private static boolean capableOn(ServerLevel level, BlockPos pos, Direction face, AEKey energy) {
        try {
            Map<AEKeyType, ExternalStorageStrategy> strategies =
                    StackWorldBehaviors.createExternalStorageStrategies(level, pos, face);
            for (ExternalStorageStrategy strategy : strategies.values()) {
                MEStorage wrapper = strategy.createWrapper(false, NOOP);
                if (wrapper != null
                        && wrapper.insert(energy, PROBE, Actionable.SIMULATE,
                                AeKeyAdapter.SOURCE) > 0L) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            // 第三方能力实现抛异常：这个面按不支持处理，换下一个面。
            return false;
        }
        return false;
    }
}
