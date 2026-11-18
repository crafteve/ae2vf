package io.github.crafteve.idlefactory.storage;

import org.jetbrains.annotations.Nullable;
import appeng.api.storage.MEStorage;

/**
 * 把仓库地址翻译成一个可用的 {@link MEStorage}。
 *
 * <p>这是一个由适配层（{@code integration/ae2}）实现的接口：{@code storage} 自己不认识 AE2，
 * 也就不会和适配层互相依赖。工厂启动时创建具体实现，由工厂运行时持有，刷新时传给
 * {@code FactoryLive}。</p>
 */
@FunctionalInterface
public interface StorageResolver {
    /**
     * 解析地址。
     *
     * @param anchor 仓库地址
     * @return 仓库；地址无效、网络离线或解析失败时返回 {@code null}
     */
    @Nullable
    MEStorage resolve(StorageAnchor anchor);
}
