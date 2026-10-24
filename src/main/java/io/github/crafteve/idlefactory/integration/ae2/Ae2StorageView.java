package io.github.crafteve.idlefactory.integration.ae2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.github.crafteve.idlefactory.IdleFactory;

import appeng.api.networking.IGrid;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;

/**
 * ME 库存读取：把网络库存摊平成固定大小的快照。
 *
 * <p>M1 只保留物品（{@link AEItemKey}），流体先跳过。</p>
 */
public final class Ae2StorageView {
    /** 一条库存记录。 */
    public record Entry(AEKey key, long amount) {
    }

    private Ae2StorageView() {
        // 纯静态工具类
    }

    /**
     * 读取当前网络库存，按数量降序取前 {@code limit} 条物品。
     *
     * @param grid  ME 网络
     * @param limit 最多返回多少条（包大小上限）
     * @return 只含物品的库存快照；网关注销或读取失败时返回空列表
     */
    public static List<Entry> snapshot(IGrid grid, int limit) {
        if (grid == null || limit <= 0) {
            return List.of();
        }

        try {
            KeyCounter counter = new KeyCounter();
            grid.getStorageService().getInventory().getAvailableStacks(counter);

            List<Entry> entries = new ArrayList<>();
            for (var stack : counter) {
                if (stack.getKey() instanceof AEItemKey) {
                    entries.add(new Entry(stack.getKey(), stack.getLongValue()));
                }
            }

            entries.sort(Comparator.comparingLong(Entry::amount).reversed());
            if (entries.size() > limit) {
                return List.copyOf(entries.subList(0, limit));
            }
            return List.copyOf(entries);
        } catch (RuntimeException e) {
            // 网络正好在重建，降级成空库存而不是把界面炸掉
            IdleFactory.LOGGER.warn("Failed to read AE2 storage snapshot", e);
            return List.of();
        }
    }
}
