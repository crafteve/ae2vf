package io.github.crafteve.idlefactory.storage;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.integration.ae2.Ae2Binder;
import io.github.crafteve.idlefactory.integration.ae2.Ae2Storage;
import io.github.crafteve.idlefactory.key.BlockKey;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.storage.MEStorage;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * ME 仓库解析器：把工厂记录下来的仓库方块坐标解析成 ME 网络库存。
 *
 * <p>仓库只有 ME 一种：按坐标拿 AE2 的网格节点，再取整个网络的存储。方块没加载、没接网络、
 * 节点没启动、网格正在拆分一律返回 {@code null}，由工厂降级成「仓库离线」——表现为没料、
 * 没处放，而不是抛异常。</p>
 *
 * <p>解析结果不缓存：工厂每 20 tick 调一次、锚点变更时立即调一次，缓存放在
 * {@code Factory} 的缓存字段，本类保持无状态。</p>
 */
public final class MeStorageResolver {
    /** 服务端实例，用来按维度取 level。 */
    private final MinecraftServer server;

    /**
     * @param server 服务端实例（用来按维度取 level）
     */
    public MeStorageResolver(MinecraftServer server) {
        this.server = server;
    }

    /**
     * 解析一个仓库方块。
     *
     * @param key 仓库方块的位置键
     * @return 网络库存；不可用时为 {@code null}
     */
    @Nullable
    public MEStorage resolve(BlockKey key) {
        ServerLevel level = this.server.getLevel(key.dimension());
        if (level == null || !level.isLoaded(key.pos())) {
            return null;
        }
        IGridNode node = Ae2Binder.nodeAt(level, key.pos());
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
