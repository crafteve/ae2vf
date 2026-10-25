package io.github.crafteve.idlefactory.factory.storage;

import org.jetbrains.annotations.Nullable;

/**
 * 按地址类型分发到具体解析器。
 *
 * <p>当前只有两条路：AE2 和方块容器。加第三种后端时在这里加一个分支即可。</p>
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
        if (anchor instanceof Ae2Anchor) {
            return this.ae2.resolve(anchor);
        }
        if (anchor instanceof ContainerAnchor) {
            return this.containers.resolve(anchor);
        }
        return null;
    }
}
