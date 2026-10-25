package io.github.crafteve.idlefactory.factory.io;

import io.github.crafteve.idlefactory.factory.storage.Storage;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * IO 的唯一写路径：feed（ME → 机器）和 pull（机器 → ME）。
 *
 * <p>两个方向共用同一条不变式：<b>搬不进去的东西，永远不离开来源</b>。</p>
 *
 * <ul>
 *   <li>{@link #feed}：先问机器能收多少，只按这个量从 ME 扣，塞不进再退款。</li>
 *   <li>{@link #pull}：先问 ME 能收多少，只按这个量从机器抽，塞不进再退回机器。</li>
 * </ul>
 */
public final class IoTransfer {
    private IoTransfer() {
        // 纯静态工具类
    }

    /**
     * 从仓库补料进机器。
     *
     * @param storage 仓库
     * @param handler 目标面的物品处理器
     * @param preview 物品样本（数量忽略）
     * @return 本次结果
     */
    public static IoResult feed(Storage storage, IItemHandler handler, ItemStack preview) {
        if (handler == null || preview.isEmpty()) {
            return new IoResult(0, IoResult.Reason.NO_HANDLER);
        }

        // 1) 问机器：每个能收该物品的槽还能收多少
        int capacity = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack inSlot = handler.getStackInSlot(slot);
            if (!inSlot.isEmpty() && !ItemStack.isSameItem(inSlot, preview)) {
                continue;
            }
            ItemStack attempt = preview.copyWithCount(preview.getMaxStackSize());
            ItemStack refused = handler.insertItem(slot, attempt, true);
            capacity += attempt.getCount() - refused.getCount();
        }
        if (capacity <= 0) {
            return new IoResult(0, IoResult.Reason.NO_SPACE);
        }

        // 2) 只按机器点头的量从仓库扣
        long available = storage.extract(preview, capacity, true);
        if (available <= 0) {
            return new IoResult(0, IoResult.Reason.NO_ITEM);
        }
        int want = (int) Math.min(available, capacity);
        long taken = storage.extract(preview, want, false);
        if (taken <= 0) {
            return new IoResult(0, IoResult.Reason.NO_ITEM);
        }

        // 3) 真塞；塞不进的退回仓库
        int remaining = (int) taken;
        for (int slot = 0; slot < handler.getSlots() && remaining > 0; slot++) {
            ItemStack inSlot = handler.getStackInSlot(slot);
            if (!inSlot.isEmpty() && !ItemStack.isSameItem(inSlot, preview)) {
                continue;
            }
            ItemStack payload = preview.copyWithCount(remaining);
            ItemStack left = handler.insertItem(slot, payload, false);
            remaining = left.getCount();
        }

        int moved = (int) taken - remaining;
        if (remaining > 0) {
            storage.insert(preview, remaining, false);
        }
        return new IoResult(moved, moved > 0 ? IoResult.Reason.OK : IoResult.Reason.NO_SPACE);
    }

    /**
     * 从机器抽产物进仓库。
     *
     * @param storage 仓库
     * @param handler 来源面的物品处理器
     * @param preview 物品样本（数量忽略）
     * @return 本次结果
     */
    public static IoResult pull(Storage storage, IItemHandler handler, ItemStack preview) {
        if (handler == null || preview.isEmpty()) {
            return new IoResult(0, IoResult.Reason.NO_HANDLER);
        }

        int moved = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack inSlot = handler.getStackInSlot(slot);
            if (inSlot.isEmpty() || !ItemStack.isSameItem(inSlot, preview)) {
                continue;
            }

            // 1) 先问仓库：这个物品收不收、收多少
            ItemStack peek = handler.extractItem(slot, inSlot.getCount(), true);
            if (peek.isEmpty()) {
                continue;
            }
            long accepted = storage.insert(peek, peek.getCount(), true);
            if (accepted <= 0) {
                continue;
            }

            // 2) 只按仓库点头的量从机器抽
            ItemStack taken = handler.extractItem(slot, (int) accepted, false);
            if (taken.isEmpty()) {
                continue;
            }

            // 3) 真塞；塞不进的退回机器
            long inserted = storage.insert(taken, taken.getCount(), false);
            int remainder = taken.getCount() - (int) inserted;
            if (remainder > 0) {
                handler.insertItem(slot, taken.copyWithCount(remainder), false);
            }
            moved += (int) inserted;
        }
        return new IoResult(moved, moved > 0 ? IoResult.Reason.OK : IoResult.Reason.NO_ITEM);
    }
}
