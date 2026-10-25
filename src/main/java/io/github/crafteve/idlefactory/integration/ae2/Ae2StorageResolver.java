package io.github.crafteve.idlefactory.integration.ae2;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.storage.Storage;
import io.github.crafteve.idlefactory.factory.storage.StorageAnchor;
import io.github.crafteve.idlefactory.factory.storage.StorageResolver;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * AE2 仓库解析器：地址 → 量子环节点 → ME 库存。
 *
 * <p>只在服务器启动时由 {@code FactoryRuntime} 注入 {@code StorageLink}。解析失败一律返回
 * {@code null}，由插头降级成空仓库。</p>
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
        ServerLevel level = this.server.getLevel(anchor.dimension());
        if (level == null) {
            return null;
        }
        IGridNode node = Ae2Binder.node(level, anchor.frequency());
        if (node == null) {
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
