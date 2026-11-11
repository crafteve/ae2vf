package io.github.crafteve.idlefactory.factory.io;

import java.util.ArrayList;
import java.util.List;

import appeng.api.stacks.AEKey;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import io.github.crafteve.idlefactory.factory.storage.AeKeySupport;

/**
 * 机器的物品接口。
 *
 * <p>一个实例只认一个精确变体（{@code sample} 的组件也参与比较）。模糊规则由
 * {@link IoExecutor} 展开成多个实例，每个变体走一遍同样的事务逻辑。</p>
 */
public final class ItemAdapter implements ResourceAdapter {
    /** 扫机器槽位时的上限，避免超大库存一次遍历几千格。 */
    public static final int MAX_SCAN_SLOTS = 256;

    private final IItemHandler handler;
    private final ItemStack sample;

    /**
     * @param handler 物品处理器
     * @param sample  物品样本（数量忽略，组件参与匹配）
     */
    public ItemAdapter(IItemHandler handler, ItemStack sample) {
        this.handler = handler;
        this.sample = sample;
    }

    /** 底层处理器，给模糊规则按变体重建适配器用。 */
    public IItemHandler handler() {
        return this.handler;
    }

    @Override
    public AEKey key() {
        return AeKeySupport.item(this.sample);
    }

    /**
     * 这台机器当前真正能收下多少。
     *
     * <p>不能把所有空槽直接当成容量：熔炉的燃料槽、输出槽同样是空槽，却不会收铁矿石。
     * 旧实现按空位数累计容量，模拟插入又为 0，于是被误判成「该面拒收」。这里逐槽调用
     * {@code insertItem(..., simulate = true)}，只累计处理器真正点头的数量；容量为 0 时
     * {@link IoTransfer#feed} 会走「机器已满」，而不是「该面拒收」。</p>
     *
     * @return 当前可接受的数量
     */
    @Override
    public long capacity() {
        long total = 0L;
        for (int slot = 0; slot < this.handler.getSlots(); slot++) {
            ItemStack inSlot = this.handler.getStackInSlot(slot);
            if (!inSlot.isEmpty() && !ItemStack.isSameItemSameComponents(inSlot, this.sample)) {
                continue;
            }
            int limit = Math.min(this.handler.getSlotLimit(slot), this.sample.getMaxStackSize());
            int room = limit - inSlot.getCount();
            if (room <= 0) {
                continue;
            }
            ItemStack leftover = this.handler.insertItem(slot, this.sample.copyWithCount(room), true);
            total += room - leftover.getCount();
        }
        return total;
    }

    @Override
    public long insert(long amount, boolean simulate) {
        long remaining = amount;
        for (int slot = 0; slot < this.handler.getSlots() && remaining > 0L; slot++) {
            ItemStack inSlot = this.handler.getStackInSlot(slot);
            if (!inSlot.isEmpty() && !ItemStack.isSameItemSameComponents(inSlot, this.sample)) {
                continue;
            }
            int limit = Math.min(this.handler.getSlotLimit(slot), this.sample.getMaxStackSize());
            int room = limit - inSlot.getCount();
            if (room <= 0) {
                continue;
            }
            int attempt = (int) Math.min(remaining, room);
            ItemStack left = this.handler.insertItem(slot, this.sample.copyWithCount(attempt), simulate);
            remaining -= attempt - left.getCount();
        }
        return amount - remaining;
    }

    @Override
    public long extract(long amount, boolean simulate) {
        long remaining = amount;
        long taken = 0L;
        for (int slot = 0; slot < this.handler.getSlots() && remaining > 0L; slot++) {
            ItemStack inSlot = this.handler.getStackInSlot(slot);
            if (inSlot.isEmpty() || !ItemStack.isSameItemSameComponents(inSlot, this.sample)) {
                continue;
            }
            int want = (int) Math.min(remaining, inSlot.getCount());
            ItemStack got = this.handler.extractItem(slot, want, simulate);
            taken += got.getCount();
            remaining -= got.getCount();
        }
        return taken;
    }

    /**
     * 列出机器里与给定物品同注册名的不同变体。
     *
     * <p>按槽位顺序取，去重，最多 {@code limit} 个；样本数量统一成 1，组件原样保留。
     * 这是模糊输出规则的候选来源。</p>
     *
     * @param handler 物品处理器
     * @param item    物品
     * @param limit   最多返回几个变体
     * @return 变体样本列表
     */
    public static List<ItemStack> samples(IItemHandler handler, Item item, int limit) {
        List<ItemStack> result = new ArrayList<>();
        if (limit <= 0) {
            return result;
        }
        int slots = Math.min(handler.getSlots(), MAX_SCAN_SLOTS);
        for (int slot = 0; slot < slots && result.size() < limit; slot++) {
            ItemStack inSlot = handler.getStackInSlot(slot);
            if (inSlot.isEmpty() || inSlot.getItem() != item) {
                continue;
            }
            boolean duplicated = false;
            for (ItemStack seen : result) {
                if (ItemStack.isSameItemSameComponents(seen, inSlot)) {
                    duplicated = true;
                    break;
                }
            }
            if (!duplicated) {
                result.add(inSlot.copyWithCount(1));
            }
        }
        return result;
    }
}
