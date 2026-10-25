package io.github.crafteve.idlefactory.factory.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * 仓库地址：工厂自己的仓库插在哪。
 *
 * <p>工厂不能依赖「玩家在线 + 玩家身上有奇点」，否则玩家一离线工厂就找不到仓库。所以工厂把
 * 插头的地址记下来，重启后照着重插。</p>
 *
 * @param frequency 量子纠缠奇点的 {@code long} 频率，AE2 用它做全局查找键
 * @param dimension 环所在维度
 * @param ringPos   环的方块坐标，用来强制加载环所在区块
 */
public record WarehouseAnchor(long frequency, ResourceKey<Level> dimension, BlockPos ringPos) {

    private static final String TAG_FREQUENCY = "Frequency";
    private static final String TAG_DIMENSION = "Dimension";
    private static final String TAG_RING_POS = "RingPos";

    /**
     * 序列化成 NBT。
     *
     * @return 新标签
     */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong(TAG_FREQUENCY, this.frequency);
        tag.putString(TAG_DIMENSION, this.dimension.location().toString());
        tag.putLong(TAG_RING_POS, this.ringPos.asLong());
        return tag;
    }

    /**
     * 从 NBT 读回来。
     *
     * @param tag 存档标签
     * @return 地址；字段缺失或维度名非法时返回 {@code null}
     */
    public static WarehouseAnchor load(CompoundTag tag) {
        if (!tag.contains(TAG_FREQUENCY) || !tag.contains(TAG_DIMENSION) || !tag.contains(TAG_RING_POS)) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(tag.getString(TAG_DIMENSION));
        if (id == null) {
            return null;
        }
        return new WarehouseAnchor(tag.getLong(TAG_FREQUENCY),
                ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, id),
                BlockPos.of(tag.getLong(TAG_RING_POS)));
    }
}
