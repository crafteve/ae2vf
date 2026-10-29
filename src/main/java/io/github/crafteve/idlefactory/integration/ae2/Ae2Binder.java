package io.github.crafteve.idlefactory.integration.ae2;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * AE2 绑定层：按方块坐标找 ME 网络节点。
 *
 * <p>目标不限于 ME 控制器，只要方块实现了 AE2 的 {@link IInWorldGridNodeHost} 并暴露了任一
 * 面的节点即可。没有节点、区块未加载或方块不是 ME 网络方块时返回 {@code null}。</p>
 */
public final class Ae2Binder {
    private Ae2Binder() {
        // 纯静态工具类
    }

    /**
     * 按方块坐标找它接入 ME 网络的节点。
     *
     * @param level 服务端世界
     * @param pos   方块坐标
     * @return 节点；不在 ME 网络上时为 {@code null}
     */
    @Nullable
    public static IGridNode nodeAt(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        try {
            IInWorldGridNodeHost host = GridHelper.getNodeHost(level, pos);
            if (host == null) {
                return null;
            }
            for (Direction direction : Direction.values()) {
                IGridNode node = host.getGridNode(direction);
                if (node != null) {
                    return node;
                }
            }
        } catch (RuntimeException e) {
            // 节点已销毁 / 集群正在拆分
            return null;
        }
        return null;
    }
}
