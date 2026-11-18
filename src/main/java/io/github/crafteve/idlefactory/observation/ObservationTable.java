package io.github.crafteve.idlefactory.observation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.location.BlockKey;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * 观测表：按位置索引的行集合。
 *
 * <p>行是可变对象、原地更新，不替换、不新建：一 tick 会改几十行，每次 alloc 一个新记录只会
 * 制造垃圾。表同时维护一张「区块 → 位置」索引，世界事件监听器据此只处理真正登记过的区块，
 * 不再全表扫描。</p>
 *
 * <p>表的写入口只有三个：{@link #reconcile}（按被引用集合补行 / 删行）、{@link #remove}、
 * 以及行自己的探测写入。线程模型与服务端一致——只有服务器线程碰它。</p>
 */
public final class ObservationTable {
    /** 区块坐标 + 维度：世界事件用它筛位置。 */
    public record ChunkId(ResourceKey<Level> dimension, long packed) {
        /**
         * 一个位置所在的区块。
         *
         * @param key 位置键
         * @return 区块标识
         */
        public static ChunkId of(BlockKey key) {
            long packed = ChunkPos.asLong(key.pos().getX() >> 4, key.pos().getZ() >> 4);
            return new ChunkId(key.dimension(), packed);
        }
    }

    private final Map<BlockKey, Entry> byKey = new HashMap<>();
    private final List<Entry> entries = new ArrayList<>();
    private final Map<ChunkId, Set<BlockKey>> byChunk = new HashMap<>();

    /**
     * 取一行。
     *
     * @param key 位置键
     * @return 行；没有登记时为 {@code null}
     */
    @Nullable
    public Entry get(BlockKey key) {
        return this.byKey.get(key);
    }

    /**
     * 是否登记过。
     *
     * @param key 位置键
     * @return 登记过为 {@code true}
     */
    public boolean contains(BlockKey key) {
        return this.byKey.containsKey(key);
    }

    /** 行数。 */
    public int size() {
        return this.entries.size();
    }

    /** 全部行的只读视图，顺序稳定（追加顺序）；只能读取，不能增删。 */
    public List<Entry> entries() {
        return Collections.unmodifiableList(this.entries);
    }

    /**
     * 补一行；已存在时直接返回旧行。
     *
     * @param key      位置键
     * @param dueTick  首次探测时刻
     * @return 行
     */
    public Entry create(BlockKey key, long dueTick) {
        Entry existing = this.byKey.get(key);
        if (existing != null) {
            return existing;
        }
        Entry entry = new Entry(key);
        entry.nextProbeTick = dueTick;
        this.byKey.put(key, entry);
        this.entries.add(entry);
        this.byChunk.computeIfAbsent(ChunkId.of(key), ignored -> new java.util.HashSet<>()).add(key);
        return entry;
    }

    /**
     * 删一行。
     *
     * @param key 位置键
     * @return 被删掉的行；没有登记时为 {@code null}
     */
    @Nullable
    public Entry remove(BlockKey key) {
        Entry removed = this.byKey.remove(key);
        if (removed == null) {
            return null;
        }
        this.entries.remove(removed);
        Set<BlockKey> chunk = this.byChunk.get(ChunkId.of(key));
        if (chunk != null) {
            chunk.remove(key);
            if (chunk.isEmpty()) {
                this.byChunk.remove(ChunkId.of(key));
            }
        }
        return removed;
    }

    /**
     * 按「当前被引用的位置集合」对账：补缺失的行（标成立即该探），删多余的行。
     *
     * <p>返回被删行在旧列表里的下标（升序），供游标按叠加算法回退：删在游标之前要减，
     * 删的正是游标行则不前进。</p>
     *
     * @param wanted 被引用的位置集合
     * @param now    当前 tick
     * @return 被删行的旧下标，升序
     */
    public int[] reconcile(Set<BlockKey> wanted, long now) {
        List<Integer> removedIndices = new ArrayList<>();
        // 从尾往头删：前面行的下标不会被后面的删除影响，记录的仍是旧下标。
        for (int i = this.entries.size() - 1; i >= 0; i--) {
            BlockKey key = this.entries.get(i).key();
            if (!wanted.contains(key)) {
                removedIndices.add(i);
                this.entries.remove(i);
                this.byKey.remove(key);
                Set<BlockKey> chunk = this.byChunk.get(ChunkId.of(key));
                if (chunk != null) {
                    chunk.remove(key);
                    if (chunk.isEmpty()) {
                        this.byChunk.remove(ChunkId.of(key));
                    }
                }
            }
        }
        for (BlockKey key : wanted) {
            this.create(key, now);
        }
        Collections.reverse(removedIndices);
        int[] result = new int[removedIndices.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = removedIndices.get(i);
        }
        return result;
    }

    /**
     * 某区块里登记过的位置。
     *
     * @param id 区块标识
     * @return 位置集合；该区块没有登记时为空集
     */
    public Set<BlockKey> keysInChunk(ChunkId id) {
        Set<BlockKey> keys = this.byChunk.get(id);
        return keys == null ? Set.of() : Collections.unmodifiableSet(keys);
    }

    /** 清空整表；停服时调。 */
    public void clear() {
        this.byKey.clear();
        this.entries.clear();
        this.byChunk.clear();
    }

    /**
     * 一行观测：一个位置的世界事实。
     *
     * <p>刻意做成可变类而不是 record：原地更新才不会每 tick 制造垃圾。健康与工态的写入只由
     * {@link ObservationRunner} 执行。</p>
     */
    public static final class Entry implements BlockFacts {
        /** 正常状态的复探间隔（tick）：必须短于健康过期阈值，否则安静机器会自己判过期。 */
        private static final int PRESENT_INTERVAL = 120;
        /** 未加载时的复探间隔（tick）。 */
        private static final int UNLOADED_INTERVAL = 120;
        /** 失联复探的起始间隔与上限。 */
        private static final int MISSING_MIN_INTERVAL = 120;
        private static final int MISSING_MAX_INTERVAL = 1200;
        /** 工作状态复探间隔（tick）。 */
        private static final int WORK_INTERVAL = 5;

        private final BlockKey key;
        private Health health = Health.UNKNOWN;
        private WorkState workState = WorkState.UNKNOWN;
        private long observedAtTick = -1L;
        private long nextProbeTick;
        private long workObservedAtTick = -1L;
        private long nextWorkProbeTick;
        private long missingSince = -1L;
        private int goneProbes;
        private int probeInterval = PRESENT_INTERVAL;
        @Nullable
        private ResourceLocation blockId;
        @Nullable
        private ResourceLocation lastBlockId;
        @Nullable
        private ResourceLocation beTypeId;
        private boolean machineCapable;
        private boolean energyCapable;
        private boolean legacyContainer;

        Entry(BlockKey key) {
            this.key = key;
        }

        /** 位置键。 */
        public BlockKey key() {
            return this.key;
        }

        @Override
        public Health health() {
            return this.health;
        }

        /** 观测到的工作状态；没有可靠探针时为 {@link WorkState#UNKNOWN}。 */
        public WorkState workState() {
            return this.workState;
        }

        @Override
        @Nullable
        public ResourceLocation blockId() {
            return this.blockId;
        }

        /**
         * 最后一次真的探到的方块 id；未加载时保留旧值，只给界面分组兜底用。
         *
         * @return 方块 id；从没探到过时为 {@code null}
         */
        @Nullable
        public ResourceLocation lastBlockId() {
            return this.lastBlockId;
        }

        @Override
        @Nullable
        public ResourceLocation beTypeId() {
            return this.beTypeId;
        }

        /** 是否具备机器能力（方块实体 + 任一能力 / 原版容器）。 */
        public boolean machineCapable() {
            return this.machineCapable;
        }

        /** 是否具备能量能力（不限面）。 */
        public boolean energyCapable() {
            return this.energyCapable;
        }

        /** 是不是原版 {@code Container}（没注册能力的模组方块兜底）。 */
        public boolean legacyContainer() {
            return this.legacyContainer;
        }

        /** 最后一次探测的 tick；从没探过为 {@code -1}。 */
        public long observedAtTick() {
            return this.observedAtTick;
        }

        /** 下一次该探测的 tick。 */
        public long nextProbeTick() {
            return this.nextProbeTick;
        }

        /** 下一次该探测工作状态的 tick。 */
        public long nextWorkProbeTick() {
            return this.nextWorkProbeTick;
        }

        /** 这一 tick 是否该做健康探测。 */
        public boolean healthDue(long now) {
            return this.nextProbeTick <= now;
        }

        /** 这一 tick 是否该做工作状态探测。 */
        public boolean workDue(long now) {
            return this.health == Health.PRESENT && this.nextWorkProbeTick <= now;
        }

        @Override
        public boolean isStale(long now, long staleHealthTicks) {
            if (!this.probed() || this.observedAtTick < 0L) {
                return false;
            }
            return now - this.observedAtTick > staleHealthTicks;
        }

        /**
         * 写一次探测结果，并安排下一次。
         *
         * @param result 探测结果
         * @param tick   当前 tick
         */
        void applyProbe(BlockProbe.Presence result, long tick) {
            Health before = this.health;
            this.health = result.health();
            this.blockId = result.blockId();
            if (result.blockId() != null) {
                // 只有真的看到方块才刷新「上次是什么」；未加载时保留，否则界面分组会散架。
                this.lastBlockId = result.blockId();
            }
            this.beTypeId = result.beTypeId();
            this.machineCapable = result.machineCapable();
            this.energyCapable = result.energyCapable();
            this.legacyContainer = result.legacyContainer();
            this.observedAtTick = tick;

            if (result.health().gone()) {
                if (this.missingSince < 0L) {
                    this.missingSince = tick;
                }
                this.goneProbes++;
                int shift = Math.min(Math.max(this.goneProbes - 1, 0), 4);
                this.probeInterval = Math.min(MISSING_MIN_INTERVAL << shift, MISSING_MAX_INTERVAL);
            } else if (result.health() == Health.PRESENT) {
                this.missingSince = -1L;
                this.goneProbes = 0;
                this.probeInterval = PRESENT_INTERVAL;
                if (before != Health.PRESENT) {
                    this.workState = WorkState.UNKNOWN;
                    this.workObservedAtTick = -1L;
                    this.nextWorkProbeTick = tick;
                } else if (this.nextWorkProbeTick <= 0L) {
                    this.nextWorkProbeTick = tick;
                }
            } else {
                this.goneProbes = 0;
                this.probeInterval = UNLOADED_INTERVAL;
                this.workState = WorkState.UNKNOWN;
                this.workObservedAtTick = -1L;
                this.nextWorkProbeTick = 0L;
            }
            this.nextProbeTick = tick + this.probeInterval;
        }

        /**
         * 写一次工作状态探测结果。
         *
         * @param state 工作状态
         * @param tick  当前 tick
         */
        void applyWorkState(WorkState state, long tick) {
            this.workState = state;
            this.workObservedAtTick = tick;
            this.nextWorkProbeTick = tick + WORK_INTERVAL;
        }

        /**
         * 立即安排下一次健康探测。
         *
         * @param tick 当前 tick
         */
        void markDue(long tick) {
            this.nextProbeTick = tick;
        }
    }
}
