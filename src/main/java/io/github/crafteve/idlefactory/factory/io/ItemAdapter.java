package io.github.crafteve.idlefactory.factory.io;

import io.github.crafteve.idlefactory.factory.resource.ResourceKey;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 机器的物品接口。
 */
public final class ItemAdapter implements ResourceAdapter {
    private final IItemHandler handler;
    private final ItemStack sample;

    /**
     * @param handler 物品处理器
     * @param sample  物品样本（数量忽略）
     */
    public ItemAdapter(IItemHandler handler, ItemStack sample) {
        this.handler = handler;
        this.sample = sample;
    }

    @Override
    public ResourceKey key() {
        return ResourceKey.item(this.sample);
    }

    @Override
    public long capacity() {
        long total = 0L;
        for (int slot = 0; slot < this.handler.getSlots(); slot++) {
            ItemStack inSlot = this.handler.getStackInSlot(slot);
            if (!inSlot.isEmpty() && !ItemStack.isSameItem(inSlot, this.sample)) {
                continue;
            }
            int limit = Math.min(this.handler.getSlotLimit(slot), this.sample.getMaxStackSize());
            total += Math.max(0, limit - inSlot.getCount());
        }
        return total;
    }

    @Override
    public long insert(long amount, boolean simulate) {
        long remaining = amount;
        for (int slot = 0; slot < this.handler.getSlots() && remaining > 0L; slot++) {
            ItemStack inSlot = this.handler.getStackInSlot(slot);
            if (!inSlot.isEmpty() && !ItemStack.isSameItem(inSlot, this.sample)) {
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
            if (inSlot.isEmpty() || !ItemStack.isSameItem(inSlot, this.sample)) {
                continue;
            }
            int want = (int) Math.min(remaining, inSlot.getCount());
            ItemStack got = this.handler.extractItem(slot, want, simulate);
            taken += got.getCount();
            remaining -= got.getCount();
        }
        return taken;
    }
}
