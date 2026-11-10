package io.github.crafteve.idlefactory.factory.io;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.crafteve.idlefactory.IdleFactory;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;

import net.minecraft.resources.ResourceLocation;
import appeng.api.stacks.AEItemKey;
import io.github.crafteve.idlefactory.factory.storage.AeKeySupport;

/**
 * 模糊物品规则的变体索引。
 *
 * <p>仓库只认精确 key，所以「同 id 的所有变体」必须先枚举出来。这里按工厂缓存一份
 * {@code 物品注册名 → 精确 key 列表}，由 {@link MEStorage#snapshot()} 构建，TTL 到期才重建，
 * 避免每条模糊规则每 tick 都扫一遍仓库。</p>
 *
 * <p>只用于输入侧（仓库 → 机器）：输出侧的候选直接来自机器库存，不需要索引。</p>
 *
 * <p>索引是派生缓存，不写档；服务器停止、仓库重绑时清空。列表最多
 * {@link #MAX_VARIANTS_PER_ID} 个，并且按 id 轮转起点，让变体极多的物品也能被均匀尝试。</p>
 */
public final class ItemVariantIndex {
    /** 索引刷新间隔（tick）。 */
    public static final long TTL_TICKS = 20L;
    /** 单个物品注册名最多记多少个变体。 */
    public static final int MAX_VARIANTS_PER_ID = 64;
    /** 一次重建最多读多少个仓库 key。 */
    public static final int MAX_KEYS = 2048;

    private final Map<ResourceLocation, List<AEKey>> byItem = new HashMap<>();
    private final Map<ResourceLocation, Integer> rotation = new HashMap<>();
    private long refreshedAt = Long.MIN_VALUE;

    /**
     * 取某个物品的候选变体。
     *
     * @param storage 仓库
     * @param itemId  物品注册名
     * @param now     当前 tick
     * @param limit   最多返回几个
     * @return 候选 key；仓库里没有该物品时为空表
     */
    public List<AEKey> candidates(MEStorage storage, ResourceLocation itemId, long now, int limit) {
        refresh(storage, now);
        List<AEKey> all = this.byItem.get(itemId);
        if (all == null || all.isEmpty() || limit <= 0) {
            return List.of();
        }
        if (all.size() <= limit) {
            return all;
        }
        int start = Math.floorMod(this.rotation.merge(itemId, 1, Integer::sum), all.size());
        List<AEKey> result = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            result.add(all.get((start + i) % all.size()));
        }
        return result;
    }

    /** 清空索引；仓库离线/重绑、服务器停止时调用。 */
    public void clear() {
        this.byItem.clear();
        this.rotation.clear();
        this.refreshedAt = Long.MIN_VALUE;
    }

    private void refresh(MEStorage storage, long now) {
        if (this.refreshedAt != Long.MIN_VALUE && now - this.refreshedAt < TTL_TICKS) {
            return;
        }
        this.refreshedAt = now;
        this.byItem.clear();
        Map<AEKey, Long> snapshot;
        try {
            snapshot = AeKeySupport.snapshot(storage);
        } catch (RuntimeException e) {
            IdleFactory.LOGGER.warn("IdleFactory: 模糊变体索引构建失败", e);
            return;
        }
        int keys = 0;
        for (AEKey key : snapshot.keySet()) {
            if (!(key instanceof AEItemKey)) {
                continue;
            }
            if (++keys > MAX_KEYS) {
                IdleFactory.LOGGER.debug("IdleFactory: 模糊变体索引达到 {} 条上限，后续条目被忽略", MAX_KEYS);
                break;
            }
            List<AEKey> list = this.byItem.computeIfAbsent(key.getId(), id -> new ArrayList<>());
            if (list.size() < MAX_VARIANTS_PER_ID) {
                list.add(key);
            }
        }
        this.byItem.replaceAll((id, list) -> List.copyOf(list));
    }
}
