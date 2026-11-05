package io.github.crafteve.idlefactory.factory.machine;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.location.BlockKey;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * 一台机器：随机 id + 它占用的全部方块。
 *
 * <p>机器不存维度：维度挂在每个 {@link BlockKey} 上，同一台机器内部的方块允许跨维度。
 * 第 0 个方块是类型 / 展示方块（多方块机器的主方块），单方块机器就是唯一方块；所有需要
 * 「这台机器是什么」的地方都用 {@link #typeBlock()}，不再区分有没有主方块。</p>
 *
 * <p>机器不可变：加 / 删方块返回新实例，机器 id 不变。规则与样板不在这里，它们归节点。</p>
 *
 * @param id     机器 id，随机生成，与方块位置无关
 * @param blocks 机器占用的方块，第 0 个是类型方块；至少一个
 */
public record Machine(UUID id, List<BlockKey> blocks) {
    /** 单台机器的方块数硬上限，不可由 config 关掉。 */
    public static final int HARD_MAX_BLOCKS = 256;

    private static final String TAG_ID = "Id";
    private static final String TAG_BLOCKS = "Blocks";
    private static final String TAG_DIMENSION = "Dimension";
    private static final String TAG_POS = "Pos";

    /**
     * 规范化方块列表：复制成不可变列表，并守住「至少一个方块」的不变量。
     *
     * @param id     机器 id
     * @param blocks 方块列表
     */
    public Machine {
        blocks = List.copyOf(blocks);
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("机器至少要有一个方块");
        }
    }

    /**
     * 按单个方块新建机器；id 随机，和坐标没有派生关系。
     *
     * @param dimension 方块所在维度
     * @param pos       方块坐标
     * @return 新机器
     */
    public static Machine of(ResourceKey<Level> dimension, BlockPos pos) {
        return new Machine(UUID.randomUUID(), List.of(BlockKey.of(dimension, pos)));
    }

    /**
     * 类型 / 展示方块：{@code blocks.get(0)}。
     *
     * <p>单方块机器就是唯一方块，多方块机器就是主方块。所有图标、类型判定与定位都读它。</p>
     *
     * @return 类型方块
     */
    public BlockKey typeBlock() {
        return this.blocks.get(0);
    }

    /**
     * 按方块位置找这台机器里的方块下标。
     *
     * @param key 方块位置
     * @return 下标；不属于这台机器时为 -1
     */
    public int indexOf(BlockKey key) {
        return this.blocks.indexOf(key);
    }

    /**
     * 加一个方块；已存在时返回自身。
     *
     * @param key 新方块
     * @return 新机器
     */
    public Machine withAddedBlock(BlockKey key) {
        if (this.blocks.contains(key)) {
            return this;
        }
        List<BlockKey> next = new ArrayList<>(this.blocks);
        next.add(key);
        return new Machine(this.id, next);
    }

    /**
     * 删一个方块；删完就没有方块时返回自身（机器至少一个方块）。
     *
     * @param key 要删的方块
     * @return 新机器
     */
    public Machine withRemovedBlock(BlockKey key) {
        if (this.blocks.size() <= 1 || !this.blocks.contains(key)) {
            return this;
        }
        List<BlockKey> next = new ArrayList<>(this.blocks);
        next.remove(key);
        return new Machine(this.id, next);
    }

    /**
     * 换第 0 个方块（类型方块）。
     *
     * @param key 新类型方块
     * @return 新机器；列表为空时返回自身
     */
    public Machine withTypeBlock(BlockKey key) {
        if (this.blocks.isEmpty()) {
            return this;
        }
        List<BlockKey> next = new ArrayList<>(this.blocks);
        next.set(0, key);
        return new Machine(this.id, next);
    }

    /** 给界面和指令用的短 id（前 8 位）。 */
    public String shortId() {
        return this.id.toString().substring(0, 8);
    }

    /**
     * 序列化成 NBT：id + 每个方块的维度与坐标。
     *
     * @return 新标签
     */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_ID, this.id);
        ListTag list = new ListTag();
        for (BlockKey block : this.blocks) {
            CompoundTag entry = new CompoundTag();
            entry.putString(TAG_DIMENSION, block.dimension().location().toString());
            entry.putLong(TAG_POS, block.pos().asLong());
            list.add(entry);
        }
        tag.put(TAG_BLOCKS, list);
        return tag;
    }

    /**
     * 从 NBT 读回来；缺 id、缺方块或维度名非法时丢弃。
     *
     * <p>坏方块逐个丢弃：方块读不出来只丢方块，读完一个不剩才丢弃整台机器。</p>
     *
     * @param tag 存档标签
     * @return 机器；无法恢复时为 {@code null}
     */
    @Nullable
    public static Machine load(CompoundTag tag) {
        if (!tag.hasUUID(TAG_ID)) {
            return null;
        }
        ListTag list = tag.getList(TAG_BLOCKS, Tag.TAG_COMPOUND);
        List<BlockKey> blocks = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            BlockKey key = readBlock(list.getCompound(i));
            if (key != null && !blocks.contains(key)) {
                blocks.add(key);
            }
        }
        if (blocks.isEmpty()) {
            return null;
        }
        return new Machine(tag.getUUID(TAG_ID), blocks);
    }

    /**
     * 读一个方块条目。
     *
     * @param entry 方块标签
     * @return 方块位置；字段缺失或维度名非法时为 {@code null}
     */
    @Nullable
    private static BlockKey readBlock(CompoundTag entry) {
        ResourceLocation dimension = ResourceLocation.tryParse(entry.getString(TAG_DIMENSION));
        if (dimension == null || !entry.contains(TAG_POS)) {
            return null;
        }
        return BlockKey.of(ResourceKey.create(Registries.DIMENSION, dimension),
                BlockPos.of(entry.getLong(TAG_POS)));
    }
}
