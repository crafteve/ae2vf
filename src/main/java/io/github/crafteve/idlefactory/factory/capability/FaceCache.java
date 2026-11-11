package io.github.crafteve.idlefactory.factory.capability;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.location.BlockKey;

/**
 * 面表缓存：位置 → 面表。
 *
 * <p>它是 handler 缓存的邻居：面表本来就是能力的面视图，失效时机和 handler 完全一致
 * （能力失效一起清，方块换了、健康变了一起清），住一起天经地义。需要内容时调用方显式建表——
 * 禁止在读路径里偷偷建表，那是原来 {@code faces()} 最大的毛病。</p>
 *
 * <p>包依赖说明：键类型 {@link BlockKey} 住在最底层的 {@code location} 包，本包只向下
 * 依赖它；从不调用 {@code ObservationService} 的任何方法。</p>
 */
public final class FaceCache {
    private final Map<BlockKey, FaceTable> tables = new HashMap<>();

    /**
     * 取缓存的面表。
     *
     * @param key 位置
     * @return 面表；没建过或已作废时为 {@code null}（调用方负责退回全六面）
     */
    @Nullable
    public FaceTable get(BlockKey key) {
        return this.tables.get(key);
    }

    /**
     * 存入建好的面表。
     *
     * @param key   位置
     * @param table 建好的面表
     */
    public void put(BlockKey key, FaceTable table) {
        this.tables.put(key, table);
    }

    /**
     * 作废：丢表。
     *
     * @param key 位置
     */
    public void invalidate(BlockKey key) {
        this.tables.put(key, null);
    }

    /**
     * mark-sweep：引用集之外的表一起丢，和观察表同一拍。
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
