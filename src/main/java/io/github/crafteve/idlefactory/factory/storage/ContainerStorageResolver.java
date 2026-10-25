package io.github.crafteve.idlefactory.factory.storage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.server.MinecraftServer;

/**
 * 容器仓库解析器：地址 → 一组可用的容器 → {@link ContainerStorage}。
 *
 * <p>部分容器不可用（被拆、区块未加载）不算失败：只要还剩一个，仓库就在线。全部不可用才返回
 * {@code null}，由插头降级成离线。</p>
 */
public final class ContainerStorageResolver implements StorageResolver {
    private final MinecraftServer server;

    /**
     * @param server 服务端
     */
    public ContainerStorageResolver(MinecraftServer server) {
        this.server = server;
    }

    @Override
    @Nullable
    public Storage resolve(StorageAnchor anchor) {
        if (!(anchor instanceof ContainerAnchor containerAnchor) || containerAnchor.isEmpty()) {
            return null;
        }
        List<ContainerRef> available = new ArrayList<>(containerAnchor.containers().size());
        for (ContainerRef ref : containerAnchor.containers()) {
            if (ContainerStorage.available(this.server, ref)) {
                available.add(ref);
            }
        }
        if (available.isEmpty()) {
            return null;
        }
        // 高优先级在前：先填、后抽
        available.sort(Comparator.comparingInt(ContainerRef::priority).reversed());
        return new ContainerStorage(this.server, available, containerAnchor.containers().size());
    }
}
