package io.github.crafteve.idlefactory.factory;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.storage.StorageAnchor;
import io.github.crafteve.idlefactory.factory.storage.StorageMode;
import io.github.crafteve.idlefactory.factory.storage.StorageResolver;

/**
 * 一个工厂实例：id + 配置 + 运行时。
 *
 * <p>它是服务端对象，不感知终端是否存在。配置在 {@link FactoryConfig}，写档；运行时在
 * {@link FactoryLive}，可随时从配置重建。</p>
 */
public final class Factory {
    /** 工厂唯一 id。 */
    private final UUID id;
    /** 写档部分：拥有者、仓库地址与模式、机器库、产线库。 */
    private final FactoryConfig config;
    /** 运行时部分：解析出的仓库、在线标记、诊断计数。 */
    private final FactoryLive live = new FactoryLive();

    /**
     * @param id     工厂 id
     * @param config 写档配置
     */
    public Factory(UUID id, FactoryConfig config) {
        this.id = id;
        this.config = config;
    }

    /** 工厂 id。 */
    public UUID id() {
        return this.id;
    }

    /** 写档配置。 */
    public FactoryConfig config() {
        return this.config;
    }

    /** 运行时数据。 */
    public FactoryLive live() {
        return this.live;
    }

    /**
     * 绑定仓库地址并立即解析一次。
     *
     * @param anchor   新地址；{@code null} 表示解绑
     * @param resolver 仓库解析器；{@code null} 表示运行时未就绪
     */
    public void bindStorage(@Nullable StorageAnchor anchor, @Nullable StorageResolver resolver) {
        this.config.setStorageAnchor(anchor);
        this.live.refresh(this.config.storageAnchor(), resolver);
    }

    /**
     * 改仓库接入模式。调用方负责保证当前地址为空。
     *
     * @param mode 新模式
     */
    void setStorageMode(StorageMode mode) {
        this.config.setStorageMode(mode);
    }

    /**
     * 重新解析仓库地址。定时刷新与手动刷新都走这里。
     *
     * @param resolver 仓库解析器
     * @return 在线状态是否变化
     */
    public boolean refreshStorage(@Nullable StorageResolver resolver) {
        return this.live.refresh(this.config.storageAnchor(), resolver);
    }
}
