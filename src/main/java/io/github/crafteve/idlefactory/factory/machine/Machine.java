package io.github.crafteve.idlefactory.factory.machine;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.location.BlockKey;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * 一台机器：随机 id + 它所在的唯一方块。
 *
 * <p>多方块删除后「机器 = 一个方块」成为硬约束：节点仍可持有 1..N 台机器，但一台机器不再
 * 占多个方块，因此不再有主方块 / 类型方块的区分，也没有方块列表、方块数上限这些概念。</p>
 *
 * <p>机器不可变；规则与样板不在这里，它们归节点。</p>
 *
 * @param id    机器 id，随机生成，与方块位置无关
 * @param block 机器所在的方块
 */
public record Machine(UUID id, BlockKey block) {
    private static final String TAG_ID = "Id";
    private static final String TAG_DIMENSION = "Dimension";
    private static final String TAG_POS = "Pos";

    /**
     * 按单个方块新建机器；id 随机，和坐标没有派生关系。
     *
     * @param dimension 方块所在维度
     * @param pos       方块坐标
     * @return 新机器
     */
    public static Machine of(ResourceKey<Level> dimension, BlockPos pos) {
        return new Machine(UUID.randomUUID(), BlockKey.of(dimension, pos));
    }

    /** 给界面和指令用的短 id（前 8 位）。 */
    public String shortId() {
        return this.id.toString().substring(0, 8);
    }

    /**
     * 序列化成 NBT：id + 方块维度与坐标。
     *
     * @return 新标签
     */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_ID, this.id);
        tag.putString(TAG_DIMENSION, this.block.dimension().location().toString());
        tag.putLong(TAG_POS, this.block.pos().asLong());
        return tag;
    }

    /**
     * 从 NBT 读回来；缺 id、缺方块或维度名非法时丢弃。
     *
     * <p>旧档存的是 {@code Blocks} 列表（一台机器多个方块）。开发版不考虑旧存档：读不到单方块
     * 字段就返回 {@code null}，该机器不会进内存。</p>
     *
     * @param tag 存档标签
     * @return 机器；无法恢复时为 {@code null}
     */
    @Nullable
    public static Machine load(CompoundTag tag) {
        if (!tag.hasUUID(TAG_ID) || !tag.contains(TAG_POS)) {
            return null;
        }
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString(TAG_DIMENSION));
        if (dimension == null) {
            return null;
        }
        BlockKey block = BlockKey.of(ResourceKey.create(Registries.DIMENSION, dimension),
                BlockPos.of(tag.getLong(TAG_POS)));
        return new Machine(tag.getUUID(TAG_ID), block);
    }
}
