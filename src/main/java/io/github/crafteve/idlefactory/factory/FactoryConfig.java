package io.github.crafteve.idlefactory.factory;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.machine.MachineLibrary;
import io.github.crafteve.idlefactory.factory.storage.ContainerAnchor;
import io.github.crafteve.idlefactory.factory.storage.StorageAnchor;
import io.github.crafteve.idlefactory.factory.storage.StorageMode;

/**
 * 工厂的配置部分：需要写进存档、重启后必须原样恢复的内容。
 *
 * <p>只描述「玩家配置了什么」：拥有者、仓库地址与模式、机器库。仓库解析结果、在线标记与诊断
 * 计数都在 {@link FactoryLive}，不写档。</p>
 *
 * <p>P1 删除产线后配置里不再有产线库：机器库就是工厂的全部机器，IO 直接按机器跑。</p>
 *
 * <p>机器库本身可变，所以本类不做 record + 复制；它的边界是「不含运行时」。</p>
 */
public final class FactoryConfig {
    /** 拥有者玩家 uuid；鉴权只看这个字段。 */
    private final UUID owner;
    /** 仓库接入模式；清空地址后仍保留，供绑定杖切换显示。 */
    private StorageMode storageMode;
    /** 仓库地址；未绑定为 {@code null}。 */
    @Nullable
    private StorageAnchor storageAnchor;
    /** 机器库：工厂接管的 IO 目标。 */
    private final MachineLibrary machines = new MachineLibrary();

    /**
     * @param owner         拥有者玩家 uuid
     * @param storageMode   仓库接入模式
     * @param storageAnchor 仓库地址；未绑定为 {@code null}
     */
    public FactoryConfig(UUID owner, StorageMode storageMode, @Nullable StorageAnchor storageAnchor) {
        this.owner = owner;
        this.storageMode = storageMode;
        this.storageAnchor = storageAnchor;
    }

    /** 拥有者玩家 uuid。 */
    public UUID owner() {
        return this.owner;
    }

    /** 仓库接入模式。 */
    public StorageMode storageMode() {
        return this.storageMode;
    }

    /**
     * 改仓库接入模式；只由 {@link Factory} 调用，调用方负责保证当前地址为空。
     *
     * @param mode 新模式
     */
    void setStorageMode(StorageMode mode) {
        this.storageMode = mode;
    }

    /** 仓库地址；未绑定为 {@code null}。 */
    @Nullable
    public StorageAnchor storageAnchor() {
        return this.storageAnchor;
    }

    /**
     * 设置仓库地址；只由 {@link Factory#bindStorage} 调用。非空地址会顺手把模式推到对应类型，
     * 解绑时保留当前模式。
     *
     * @param anchor 新地址；{@code null} 表示解绑
     */
    void setStorageAnchor(@Nullable StorageAnchor anchor) {
        this.storageAnchor = anchor;
        if (anchor instanceof ContainerAnchor) {
            this.storageMode = StorageMode.CONTAINER;
        } else if (anchor != null) {
            this.storageMode = StorageMode.ME;
        }
    }

    /** 机器库。 */
    public MachineLibrary machines() {
        return this.machines;
    }
}
