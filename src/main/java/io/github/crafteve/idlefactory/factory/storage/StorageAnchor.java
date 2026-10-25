package io.github.crafteve.idlefactory.factory.storage;

import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;

/**
 * 仓库地址：工厂的仓库插在哪。
 *
 * <p>工厂不能依赖「玩家在线」，所以把插头的地址记下来，重启后照着重插。地址现在有两种：
 * AE2 量子环（{@link Ae2Anchor}）和方块容器组（{@link ContainerAnchor}）。</p>
 *
 * <p>当前阶段两者互斥，一个工厂只挂一种；未来要组合时新增一个复合地址即可，旧存档不受影响。</p>
 */
public sealed interface StorageAnchor permits Ae2Anchor, ContainerAnchor {
    /** AE2 量子环。 */
    String TYPE_AE2 = "ae2";
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
     * <p>只认带 {@code Type} 的新格式；当前阶段不兼容旧存档。</p>
     *
     * @param tag 存档标签
     * @return 地址；类型未知或字段缺失时返回 {@code null}
     */
    @Nullable
    static StorageAnchor load(CompoundTag tag) {
        return switch (tag.getString("Type")) {
            case TYPE_AE2 -> Ae2Anchor.load(tag);
            case TYPE_CONTAINER -> ContainerAnchor.load(tag);
            default -> null;
        };
    }
}
