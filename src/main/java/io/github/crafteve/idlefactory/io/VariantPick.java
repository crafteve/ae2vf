package io.github.crafteve.idlefactory.io;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.machine.MatchMode;
import io.github.crafteve.idlefactory.machine.IoRuleSpec;
import io.github.crafteve.idlefactory.key.AeKeyAdapter;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;

import net.minecraft.resources.ResourceLocation;

/**
 * 模糊物品规则的变体查询：把一条规则展开成「同注册名的多个精确 key」。
 *
 * <p>仓库只认精确 key，所以「同 id 的所有变体」必须先枚举出来。输入侧按工厂缓存一份
 * {@code 物品注册名 → 精确 key 列表}（由 {@code MEStorage#getAvailableStacks} 构建，TTL 到期
 * 才重建），输出侧直接扫机器库存——机器不会塞下成千上万个变体。</p>
 *
 * <p>候选数量受单规则与整台机器每 tick 的额度约束，防止变体爆炸时一轮打满；额度由调用方
 * 用单元素数组传入并扣减。只读，不写档。</p>
 */
public final class VariantPick {
    /** 单条模糊规则每 tick 最多试几个变体。 */
    public static final int MAX_VARIANTS_PER_RULE_TICK = 8;
    /** 单台机器每 tick 的变体总量上限；输入侧与输出侧扣减合计不超过它。 */
    public static final int MAX_VARIANTS_PER_MACHINE_TICK = 16;
    /** 输出侧的预留额度；输出实际可用 = 单机器总量 - 输入实际用量，不固定为它。 */
    public static final int VARIANT_BUDGET_RESERVED_FOR_OUTPUT = 4;
    /** 输出侧需要预留时输入侧每 tick 的额度；余量由输出借用。 */
    public static final int MAX_VARIANTS_PER_INPUT_TICK =
            MAX_VARIANTS_PER_MACHINE_TICK - VARIANT_BUDGET_RESERVED_FOR_OUTPUT;
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
     * 展开一条规则的候选资源。
     *
     * @param network 仓库，输入侧用来查变体索引
     * @param machine 机器侧存储，输出侧用来扫库存；为 {@code null} 时输出侧没有候选
     * @param spec    规则
     * @param side    方向
     * @param now     当前 tick
     * @param budget  本侧剩余额度，单元素数组，调用后扣减
     * @return 候选 key；空表且 {@code budget} 仍是正数表示确实没有可搬的变体
     */
    public List<AEKey> candidates(MEStorage network, @Nullable MEStorage machine, IoRuleSpec spec,
            IoSide side, long now, int[] budget) {
        if (spec.mode() == MatchMode.EXACT) {
            // 精确规则没有变体概念，也不扣额度。
            return List.of(spec.key());
        }
        int limit = Math.min(MAX_VARIANTS_PER_RULE_TICK, budget[0]);
        if (limit <= 0) {
            return List.of();
        }
        List<AEKey> result = new ArrayList<>();
        if (side == IoSide.INPUT) {
            result.addAll(this.fromNetwork(network, spec.key().getId(), now, limit));
        } else if (machine != null) {
            result.addAll(this.fromMachine(machine, spec.key().getId(), limit));
        }
        budget[0] -= result.size();
        return result;
    }

    /** 清空索引；仓库离线 / 重绑、服务器停止时调用。 */
    public void clear() {
        this.byItem.clear();
        this.rotation.clear();
        this.refreshedAt = Long.MIN_VALUE;
    }

    /**
     * 输入侧：从仓库变体索引取候选，按 id 轮转起点。
     */
    private List<AEKey> fromNetwork(MEStorage network, ResourceLocation itemId, long now, int limit) {
        this.refresh(network, now);
        List<AEKey> all = this.byItem.get(itemId);
        if (all == null || all.isEmpty()) {
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

    /**
     * 输出侧：直接扫机器库存，按注册名取不同变体（去重后按出现顺序）。
     */
    private List<AEKey> fromMachine(MEStorage machine, ResourceLocation itemId, int limit) {
        Map<AEKey, Long> snapshot;
        try {
            snapshot = AeKeyAdapter.snapshot(machine);
        } catch (RuntimeException e) {
            IdleFactory.LOGGER.warn("IdleFactory: 扫描机器变体失败", e);
            return List.of();
        }
        List<AEKey> result = new ArrayList<>();
        for (AEKey key : snapshot.keySet()) {
            if (result.size() >= limit) {
                break;
            }
            if (!(key instanceof AEItemKey) || !key.getId().equals(itemId)) {
                continue;
            }
            if (!result.contains(key)) {
                result.add(key);
            }
        }
        return result;
    }

    private void refresh(MEStorage network, long now) {
        if (this.refreshedAt != Long.MIN_VALUE && now - this.refreshedAt < TTL_TICKS) {
            return;
        }
        this.refreshedAt = now;
        this.byItem.clear();
        Map<AEKey, Long> snapshot;
        try {
            snapshot = AeKeyAdapter.snapshot(network);
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
                IdleFactory.LOGGER.trace("IdleFactory: 模糊变体索引达到 {} 条上限，后续条目被忽略", MAX_KEYS);
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
