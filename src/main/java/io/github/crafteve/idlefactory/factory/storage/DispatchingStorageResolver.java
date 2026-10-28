package io.github.crafteve.idlefactory.factory.storage;

import org.jetbrains.annotations.Nullable;

/**
 * 按地址类型分发到具体解析器。
 *
 * <p>地址有三种（ME 坐标、旧量子环、容器组），后端仍是两条：ME 和方块容器。加第三种后端时
 * 在这里加一个分支即可。</p>
 */
public final class DispatchingStorageResolver implements StorageResolver {
    private final StorageResolver ae2;
    private final StorageResolver containers;

    /**
     * @param ae2        AE2 解析器
     * @param containers 容器解析器
     */
    public DispatchingStorageResolver(StorageResolver ae2, StorageResolver containers) {
        this.ae2 = ae2;
        this.containers = containers;
    }

    @Override
    @Nullable
    public Storage resolve(StorageAnchor anchor) {
        if (anchor instanceof MeAnchor || anchor instanceof Ae2Anchor) {
            return this.ae2.resolve(anchor);
        }
        if (anchor instanceof ContainerAnchor) {
            return this.containers.resolve(anchor);
        }
        return null;
    }
}
