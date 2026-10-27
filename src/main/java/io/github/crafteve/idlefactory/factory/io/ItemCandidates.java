package io.github.crafteve.idlefactory.factory.io;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.resource.MatchMode;
import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;
import io.github.crafteve.idlefactory.factory.storage.Storage;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 把一条物品规则展开成「一个变体一个适配器」。
 *
 * <p>精确规则只有一个候选；模糊规则在输入侧取仓库变体索引，在输出侧扫机器库存。
 * 候选数量受单规则与单组预算约束，防止变体爆炸时一轮打满。</p>
 */
public final class ItemCandidates {
    /** 单条模糊规则每 tick 最多试几个变体。 */
    public static final int MAX_VARIANTS_PER_RULE_TICK = 8;
    /** 单个 IO 组每 tick 最多试几个变体（输入+输出共享）。 */
    public static final int MAX_VARIANTS_PER_GROUP_TICK = 16;

    /**
     * 展开结果。
     *
     * @param adapters      候选适配器
     * @param budgetStarved 是否因为组预算耗尽而没展开；这种空结果不能当成「缺料」
     */
    public record Candidates(List<ItemAdapter> adapters, boolean budgetStarved) {
        public Candidates {
            adapters = List.copyOf(adapters);
        }
    }

    private ItemCandidates() {
        // 纯静态工具类
    }

    /**
     * 展开候选。
     *
     * @param storage  仓库，输入侧用来查变体索引
     * @param base     规则本身解析出的适配器（提供 handler，精确模式下直接作为唯一候选）
     * @param spec     规则
     * @param input    {@code true} 表示输入方向
     * @param variants 变体索引；{@code null} 时模糊输入没有候选
     * @param now      当前 tick
     * @param budget   组级剩余预算，单元素数组，调用后扣减
     * @return 候选；空且 {@code budgetStarved=false} 表示仓库/机器当前确实没有可搬的变体
     */
    public static Candidates of(Storage storage, ItemAdapter base, ResourceSpec spec,
            boolean input, @Nullable ItemVariantIndex variants, long now, int[] budget) {
        if (spec.mode() == MatchMode.EXACT) {
            return new Candidates(List.of(base), false);
        }
        int limit = Math.min(MAX_VARIANTS_PER_RULE_TICK, budget[0]);
        if (limit <= 0) {
            return new Candidates(List.of(), true);
        }
        List<ItemAdapter> result = new ArrayList<>();
        if (input) {
            if (variants == null) {
                return new Candidates(List.of(), false);
            }
            for (ResourceKey key : variants.candidates(storage, spec.key().id(), now, limit)) {
                ItemStack sample = key.toStack();
                if (!sample.isEmpty()) {
                    result.add(new ItemAdapter(base.handler(), sample));
                }
            }
        } else {
            Item item = BuiltInRegistries.ITEM.get(spec.key().id());
            if (item == Items.AIR) {
                return new Candidates(List.of(), false);
            }
            for (ItemStack sample : ItemAdapter.samples(base.handler(), item, limit)) {
                result.add(new ItemAdapter(base.handler(), sample));
            }
        }
        budget[0] -= result.size();
        return new Candidates(result, false);
    }
}
