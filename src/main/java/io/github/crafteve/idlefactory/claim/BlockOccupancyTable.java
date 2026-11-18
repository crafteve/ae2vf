package io.github.crafteve.idlefactory.claim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.location.BlockKey;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * 全服方块占用表：一个位置同一时刻只归一个工厂、一种角色。
 *
 * <p>它只是真相的索引，不是真相本身——真相永远在各工厂的配置里。{@link #rebuild} 用一份
 * 完整快照整体替换：谁占着、以什么角色，全部由调用方按配置现拼，不落盘、不增量维护，
 * 因此不可能和存档分歧。</p>
 *
 * <p>绑定前查它：只有「空位」和「自己的同角色」放行，别的工厂、自己的另一种角色都拒绝。
 * 表内维护区块索引，世界事件只需要问「这个区块里有没有登记过的位置」，不用全表扫描。</p>
 */
public final class BlockOccupancyTable {
    /** 占用角色：一台机器，或一个仓库锚点。 */
    public enum Role {
        /** 机器方块。 */
        MACHINE,
        /** 仓库锚点方块。 */
        STORAGE
    }

    /**
     * 一条占用：谁的工厂、以什么角色占着哪个位置。
     *
     * @param factoryId 归属工厂 id
     * @param role      占用角色
     * @param block     位置
     */
    public record Entry(UUID factoryId, Role role, BlockKey block) {
    }

    private final Map<UUID, Entry> byId = new HashMap<>();
    private final Map<ChunkKey, Set<BlockKey>> byChunk = new HashMap<>();

    /** 区块索引键：维度 + 区块坐标。 */
    private record ChunkKey(ResourceKey<Level> dimension, long packed) {
        static ChunkKey of(BlockKey block) {
            return new ChunkKey(block.dimension(),
                    ChunkPos.asLong(block.pos().getX() >> 4, block.pos().getZ() >> 4));
        }
    }

    /**
     * 用一份完整快照重建整张表。
     *
     * <p>键一律按坐标现派生（{@link BlockKey#id()}），不信任记录里存的 id。冲突按「先登记的
     * 保留、后到的忽略」处理：同一位置出现第二条时只记日志由调用方负责。</p>
     *
     * @param entries 全量占用条目
     */
    public void rebuild(List<Entry> entries) {
        this.byId.clear();
        this.byChunk.clear();
        for (Entry entry : entries) {
            UUID id = entry.block().id();
            this.byId.putIfAbsent(id, entry);
            ChunkKey chunk = ChunkKey.of(entry.block());
            this.byChunk.computeIfAbsent(chunk, ignored -> new HashSet<>()).add(entry.block());
        }
    }

    /**
     * 按统一 id 查占用。
     *
     * @param id 统一 id（{@link BlockKey#id()} 派生）
     * @return 占用；没人占时为空
     */
    public Optional<Entry> get(UUID id) {
        return Optional.ofNullable(this.byId.get(id));
    }

    /** 表是否为空。 */
    public boolean isEmpty() {
        return this.byId.isEmpty();
    }

    /**
     * 某区块内所有占用位置。
     *
     * @param dimension 维度
     * @param chunk     区块坐标
     * @return 位置集合；没有登记时为空集
     */
    public Set<BlockKey> byChunk(ResourceKey<Level> dimension, ChunkPos chunk) {
        Set<BlockKey> keys = this.byChunk.get(new ChunkKey(dimension,
                ChunkPos.asLong(chunk.x, chunk.z)));
        return keys == null ? Set.of() : Collections.unmodifiableSet(keys);
    }

    /**
     * 查一个位置对某个工厂、某个角色的占用情况。
     *
     * @param key       位置键
     * @param factoryId 发起操作的工厂 id
     * @param role      这次想以什么角色占用
     * @return 判定结果
     */
    public ClaimCheck check(BlockKey key, UUID factoryId, Role role) {
        Entry entry = this.byId.get(key.id());
        if (entry == null) {
            return new ClaimCheck(null, ClaimCheck.Kind.FREE);
        }
        if (!entry.factoryId().equals(factoryId)) {
            return new ClaimCheck(entry, ClaimCheck.Kind.OTHER);
        }
        return new ClaimCheck(entry,
                entry.role() == role ? ClaimCheck.Kind.SELF_SAME : ClaimCheck.Kind.SELF_OTHER);
    }

    /**
     * 按占用表过滤一批目标：空地和自己的同角色目标放行，别人的和别人角色的分别计数。
     *
     * <p>过滤后不补位、不重扫：半径与数量上限已经按筛选前算过。</p>
     *
     * @param factoryId 发起操作的工厂 id
     * @param role      本次操作角色
     * @param dimension 目标维度
     * @param targets   候选坐标
     * @return 过滤结果
     */
    public Filter filter(UUID factoryId, Role role, ResourceKey<Level> dimension,
            List<BlockPos> targets) {
        List<BlockPos> open = new ArrayList<>(targets.size());
        int blockedOther = 0;
        int blockedRole = 0;
        for (BlockPos targetPos : targets) {
            BlockKey key = BlockKey.of(dimension, targetPos);
            ClaimCheck check = this.check(key, factoryId, role);
            switch (check.kind()) {
                case FREE, SELF_SAME -> open.add(targetPos);
                case OTHER -> blockedOther++;
                case SELF_OTHER -> blockedRole++;
            }
        }
        return new Filter(open, blockedOther, blockedRole);
    }

    /**
     * 一次过滤的结果。
     *
     * @param open         放行的目标坐标
     * @param blockedOther 被别的工厂占了的数量
     * @param blockedRole  位置属于自己、但角色不是本次操作角色的数量
     */
    public record Filter(List<BlockPos> open, int blockedOther, int blockedRole) {
    }

    /**
     * 一次查表的结果。
     *
     * @param entry 当前占用；{@link Kind#FREE} 时为 {@code null}
     * @param kind  判定
     */
    public record ClaimCheck(@Nullable Entry entry, Kind kind) {

        /** 判定种类。 */
        public enum Kind {
            /** 没人占，可以绑定。 */
            FREE,
            /** 自己占着同角色：可以释放，或提示「已经接入」。 */
            SELF_SAME,
            /** 自己占着异角色：拒绝，提示先解除另一角色。 */
            SELF_OTHER,
            /** 别的工厂占着：拒绝。 */
            OTHER
        }

        /** 是否应该拒绝这次操作。 */
        public boolean blocked() {
            return this.kind == Kind.SELF_OTHER || this.kind == Kind.OTHER;
        }
    }
}
