package io.github.crafteve.idlefactory.integration.ae2;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.storage.Ae2Anchor;
import io.github.crafteve.idlefactory.factory.storage.MeAnchor;
import io.github.crafteve.idlefactory.factory.storage.Storage;
import io.github.crafteve.idlefactory.factory.storage.StorageAnchor;
import io.github.crafteve.idlefactory.factory.storage.StorageResolver;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * AE2 仓库解析器：地址 → ME 节点 → ME 库存。
 *
 * <p>支持两种地址：新的 {@link MeAnchor} 按方块坐标拿节点；旧的 {@link Ae2Anchor} 按量子环
 * 频率拿节点。解析失败一律返回 {@code null}，由插头降级成空仓库。</p>
 */
public final class Ae2StorageResolver implements StorageResolver {
    private final MinecraftServer server;

    /**
     * @param server 服务端实例（用来按维度取 level）
     */
    public Ae2StorageResolver(MinecraftServer server) {
        this.server = server;
    }

    @Override
    @Nullable
    public Storage resolve(StorageAnchor anchor) {
        IGridNode node;
        if (anchor instanceof MeAnchor me) {
            ServerLevel level = this.server.getLevel(me.dimension());
            if (level == null || !level.isLoaded(me.pos())) {
                return null;
            }
            node = Ae2Binder.nodeAt(level, me.pos());
        } else if (anchor instanceof Ae2Anchor ae2) {
            ServerLevel level = this.server.getLevel(ae2.dimension());
            if (level == null) {
                return null;
            }
            node = Ae2Binder.node(level, ae2.frequency());
        } else {
            return null;
        }
        if (node == null || !node.isActive()) {
            // 节点存在但没电 / 没启动 / 频道不够：仓库不可用
            return null;
        }
        IGrid grid;
        try {
            grid = node.getGrid();
        } catch (RuntimeException e) {
            // 节点已销毁 / 集群正在拆分
            return null;
        }
        if (grid == null) {
            return null;
        }
        return new Ae2Storage(grid.getStorageService().getInventory());
    }
}
