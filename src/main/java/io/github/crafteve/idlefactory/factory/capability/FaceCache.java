package io.github.crafteve.idlefactory.factory.capability;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.location.BlockKey;

/**
 * 面表缓存：位置 →（面表，代数）。
 *
 * <p>它是 handler 缓存的邻居：面表本来就是能力的面视图，失效时机和 handler 完全一致
 * （能力失效一起清，方块换了、健康变了一起清），住一起天经地义。路由表只读这里的
 * 代数判过期，需要内容时调用方显式建表——禁止在读路径里偷偷建表，那是原来
 * {@code faces()} 最大的毛病。</p>
 *
 * <p>包依赖说明：键类型 {@link BlockKey} 住在最底层的 {@code location} 包，本包只向下
 * 依赖它；从不调用 {@code ObservationService} 的任何方法。</p>
 */
public final class FaceCache {
    private record Entry(@Nullable FaceTable table, long generation) {
    }

    private final Map<BlockKey, Entry> tables = new HashMap<>();

    /**
     * 取缓存的面表。
     *
     * @param key 位置
     * @return 面表；没建过时为 {@code null}（调用方负责退回全六面）
     */
    @Nullable
    public FaceTable get(BlockKey key) {
        Entry entry = this.tables.get(key);
        return entry == null ? null : entry.table();
    }

    /**
     * 存入建好的面表；代数保持不动（建表不是失效）。
     *
     * @param key   位置
     * @param table 建好的面表
     */
    public void put(BlockKey key, FaceTable table) {
        Entry entry = this.tables.get(key);
        long generation = entry == null ? 0L : entry.generation();
        this.tables.put(key, new Entry(table, generation));
    }

    /**
     * 作废：丢表，代数 +1。从没见过的位置代数从 1 起（和原来保持一致，
     * 路由表靠这个认出“过期”）。
     *
     * @param key 位置
     */
    public void invalidate(BlockKey key) {
        Entry entry = this.tables.get(key);
        long generation = entry == null ? 1L : entry.generation() + 1L;
        this.tables.put(key, new Entry(null, generation));
    }

    /**
     * 代数；没见过的位置为 0。
     *
     * @param key 位置
     * @return 代数
     */
    public long generation(BlockKey key) {
        Entry entry = this.tables.get(key);
        return entry == null ? 0L : entry.generation();
    }

    /**
     * mark-sweep：引用集之外的连表带代数一起丢，和观察表同一拍。
     *
     * @param referenced 当前所有工厂引用到的位置
     */
    public void sweep(Set<BlockKey> referenced) {
        this.tables.keySet().removeIf(key -> !referenced.contains(key));
    }

    /**
     * 缓存条目数（诊断用）。
     *
     * @return 条目数
     */
    public int size() {
        return this.tables.size();
    }
}
