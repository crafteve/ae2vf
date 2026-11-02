package io.github.crafteve.idlefactory.factory;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * 准星目标解析：把玩家当前视线落点解析成方块坐标。
 *
 * <p>接入、断开、绑定仓库三条路径共用同一个射线距离；读不到目标时返回 {@code null}，
 * 文案由调用方给。</p>
 */
public final class LinkTargetResolver {
    /** 准星操作的射线距离（格）。 */
    public static final double REACH = 10.0D;

    private LinkTargetResolver() {
        // 纯静态工具类
    }

    /**
     * 玩家准星指向的方块坐标。
     *
     * @param player 玩家
     * @return 方块坐标；没对准方块时为 {@code null}
     */
    @Nullable
    public static BlockPos pickedBlock(ServerPlayer player) {
        HitResult hit = player.pick(REACH, 0.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult blockHit)) {
            return null;
        }
        return blockHit.getBlockPos();
    }
}
