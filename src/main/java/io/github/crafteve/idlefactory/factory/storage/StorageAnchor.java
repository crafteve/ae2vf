package io.github.crafteve.idlefactory.factory.storage;

import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;

/**
 * 仓库地址：工厂的仓库插在哪。
 *
 * <p>工厂不能依赖「玩家在线」，所以把插头的地址记下来，重启后照着重插。地址只有两种：
 * ME 网络方块坐标（{@link MeAnchor}）和方块容器组（{@link ContainerAnchor}）。两者互斥，
 * 一个工厂只挂一种。</p>
 */
public sealed interface StorageAnchor permits MeAnchor, ContainerAnchor {
    /** ME 网络方块坐标。 */
    String TYPE_ME = "me";
    /** 方块容器组。 */
    String TYPE_CONTAINER = "container";

    /**
     * 序列化成 NBT。
     *
     * @return 新标签
     */
    CompoundTag save();

    /**
     * 给界面和日志用的一句话描述。
     *
     * @return 描述文本
     */
    String describe();

    /**
     * 从 NBT 读回来。
     *
     * @param tag 存档标签
     * @return 地址；类型未知或字段缺失时返回 {@code null}
     */
    @Nullable
    static StorageAnchor load(CompoundTag tag) {
        return switch (tag.getString("Type")) {
            case TYPE_ME -> MeAnchor.load(tag);
            case TYPE_CONTAINER -> ContainerAnchor.load(tag);
            default -> null;
        };
    }
}
