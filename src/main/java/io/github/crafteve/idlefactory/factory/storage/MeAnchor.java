package io.github.crafteve.idlefactory.factory.storage;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * ME 仓库地址：直接记录接入 ME 网络的方块坐标。
 *
 * <p>这是存储链接杖 A 模式绑定的地址：解析时按坐标拿 {@code IGridNode}，再读整个网络的
 * 存储。</p>
 *
 * @param dimension 方块所在维度
 * @param pos       方块坐标
 */
public record MeAnchor(ResourceKey<Level> dimension, BlockPos pos) implements StorageAnchor {
    private static final String TAG_TYPE = "Type";
    private static final String TAG_DIMENSION = "Dimension";
    private static final String TAG_POS = "Pos";

    @Override
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_TYPE, StorageAnchor.TYPE_ME);
        tag.putString(TAG_DIMENSION, this.dimension.location().toString());
        tag.putLong(TAG_POS, this.pos.asLong());
        return tag;
    }

    @Override
    public String describe() {
        return "me @ " + this.dimension.location() + " " + this.pos.toShortString();
    }

    /**
     * 从 NBT 读回来。
     *
     * @param tag 存档标签
     * @return 地址；字段缺失或维度名非法时返回 {@code null}
     */
    @Nullable
    public static MeAnchor load(CompoundTag tag) {
        if (!tag.contains(TAG_DIMENSION) || !tag.contains(TAG_POS)) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(tag.getString(TAG_DIMENSION));
        if (id == null) {
            return null;
        }
        return new MeAnchor(ResourceKey.create(Registries.DIMENSION, id),
                BlockPos.of(tag.getLong(TAG_POS)));
    }
}
