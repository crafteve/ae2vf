package io.github.crafteve.idlefactory.storage;

/**
 * 工厂仓库的接入模式。
 *
 * <p>存储链接杖的 A/B 模式最终落在工厂上，所有存储杖共享；A 和 B 互斥，切换前必须清空
 * 当前仓库地址。</p>
 */
public enum StorageMode {
    /** ME 网络：一个 ME 接入点，读整个网络的存储。 */
    ME,
    /** 方块容器组：最多 {@link ContainerAnchor#MAX_CONTAINERS} 个容器。 */
    CONTAINER
}
