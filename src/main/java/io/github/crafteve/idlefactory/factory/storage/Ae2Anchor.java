package io.github.crafteve.idlefactory.factory.storage;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * AE2 仓库地址：量子纠缠奇点的频率 + 环的位置。
 *
 * <p>字段名与第一版完全一致，旧存档没有 {@code Type} 也能直接读。</p>
 *
 * @param frequency 奇点的 {@code long} 频率，AE2 用它做全局查找键
 * @param dimension 环所在维度
 * @param ringPos   环的方块坐标
 */
public record Ae2Anchor(long frequency, ResourceKey<Level> dimension, BlockPos ringPos)
        implements StorageAnchor {

    private static final String TAG_TYPE = "Type";
    private static final String TAG_FREQUENCY = "Frequency";
    private static final String TAG_DIMENSION = "Dimension";
    private static final String TAG_RING_POS = "RingPos";

    @Override
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_TYPE, StorageAnchor.TYPE_AE2);
        tag.putLong(TAG_FREQUENCY, this.frequency);
        tag.putString(TAG_DIMENSION, this.dimension.location().toString());
        tag.putLong(TAG_RING_POS, this.ringPos.asLong());
        return tag;
    }

    @Override
    public String describe() {
        return "ae2 freq=" + this.frequency + " @ " + this.dimension.location()
                + " " + this.ringPos.toShortString();
    }

    /**
     * 从 NBT 读回来。
     *
     * @param tag 存档标签
     * @return 地址；字段缺失或维度名非法时返回 {@code null}
     */
    @Nullable
    public static Ae2Anchor load(CompoundTag tag) {
        if (!tag.contains(TAG_FREQUENCY) || !tag.contains(TAG_DIMENSION) || !tag.contains(TAG_RING_POS)) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(tag.getString(TAG_DIMENSION));
        if (id == null) {
            return null;
        }
        return new Ae2Anchor(tag.getLong(TAG_FREQUENCY),
                ResourceKey.create(Registries.DIMENSION, id),
                BlockPos.of(tag.getLong(TAG_RING_POS)));
    }
}
