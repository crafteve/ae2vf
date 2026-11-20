package io.github.crafteve.idlefactory.client.screen.sort;

import org.jetbrains.annotations.Nullable;

import appeng.api.config.SortDir;

import io.github.crafteve.idlefactory.menu.TerminalSortMode;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 一次排序请求的上下文：档位、方向、玩家所在维度与坐标。
 *
 * <p>距离档要拿玩家位置，但两个终端模型只读快照；把位置随上下文传进模型，模型在
 * {@code rebuild()} 里自己用，避免模型反查客户端世界。玩家位置缺失时距离档按「无穷远」
 * 处理，不会抛异常。</p>
 *
 * @param mode            排序档位
 * @param direction       排序方向
 * @param playerDimension 玩家所在维度；未知时 {@code null}
 * @param playerPos       玩家脚下方块坐标；未知时 {@code null}
 */
public record SortContext(TerminalSortMode mode, SortDir direction,
        @Nullable ResourceKey<Level> playerDimension, @Nullable BlockPos playerPos) {

    /**
     * 默认上下文：名称升序、没有玩家位置。
     *
     * @return 默认上下文
     */
    public static SortContext defaults() {
        return new SortContext(TerminalSortMode.NAME, SortDir.ASCENDING, null, null);
    }
}
