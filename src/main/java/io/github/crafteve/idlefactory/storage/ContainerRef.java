package io.github.crafteve.idlefactory.storage;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.location.BlockKey;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * 仓库里的一个容器方块。
 *
 * <p>id 由「维度 + 坐标」派生，和 {@code Machine} 一样是确定性的：同一个方块重复绑定会自然去重。</p>
 *
 * <p>{@code priority} 只在容器组内部比较：高的先填、低的先抽。默认 0。</p>
 *
 * @param id        容器 id
 * @param dimension 所在维度
 * @param pos       方块坐标
 * @param priority  优先级
 */
public record ContainerRef(UUID id, ResourceKey<Level> dimension, BlockPos pos, int priority) {

    private static final String TAG_ID = "Id";
    private static final String TAG_DIMENSION = "Dimension";
    private static final String TAG_POS = "Pos";
    private static final String TAG_PRIORITY = "Priority";

    /**
     * 按坐标生成容器引用。
     *
     * <p>id 和机器用同一套派生（维度 + 坐标，不带角色前缀），所以同一个方块当机器还是
     * 当容器是同一个 id——门禁的前提。</p>
     *
     * @param dimension 维度
     * @param pos       坐标
     * @return 新引用，优先级 0
     */
    public static ContainerRef of(ResourceKey<Level> dimension, BlockPos pos) {
        return new ContainerRef(BlockKey.of(dimension, pos).id(), dimension, pos, 0);
    }

    /**
     * 这个容器的位置键。
     *
     * @return 位置键
     */
    public BlockKey blockKey() {
        return BlockKey.of(this.dimension, this.pos);
    }

    /**
     * 改优先级，返回新实例。
     *
     * @param newPriority 新优先级
     * @return 新引用
     */
    public ContainerRef withPriority(int newPriority) {
        return new ContainerRef(this.id, this.dimension, this.pos, newPriority);
    }

    /** 给指令用的短 id（前 8 位）。 */
    public String shortId() {
        return this.id.toString().substring(0, 8);
    }

    /**
     * 序列化成 NBT。
     *
     * @return 新标签
     */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_ID, this.id);
        tag.putString(TAG_DIMENSION, this.dimension.location().toString());
        tag.putLong(TAG_POS, this.pos.asLong());
        tag.putInt(TAG_PRIORITY, this.priority);
        return tag;
    }

    /**
     * 从 NBT 读回来。
     *
     * @param tag 存档标签
     * @return 引用；字段缺失或维度名非法时返回 {@code null}
     */
    @Nullable
    public static ContainerRef load(CompoundTag tag) {
        if (!tag.hasUUID(TAG_ID) || !tag.contains(TAG_DIMENSION) || !tag.contains(TAG_POS)) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(tag.getString(TAG_DIMENSION));
        if (id == null) {
            return null;
        }
        return new ContainerRef(tag.getUUID(TAG_ID),
                ResourceKey.create(Registries.DIMENSION, id),
                BlockPos.of(tag.getLong(TAG_POS)),
                tag.getInt(TAG_PRIORITY));
    }
}
