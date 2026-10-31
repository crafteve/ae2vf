package io.github.crafteve.idlefactory.factory.io;

import io.github.crafteve.idlefactory.IdleFactory;
import appeng.api.storage.MEStorage;
import appeng.api.stacks.AEKey;
import io.github.crafteve.idlefactory.factory.storage.AeKeySupport;

/**
 * IO 的唯一写路径：feed（仓库 → 机器）和 pull（机器 → 仓库）。
 *
 * <p>两个方向共用同一条不变式：<b>搬不进去的东西，永远不离开来源</b>。</p>
 *
 * <ul>
 *   <li>{@link #feed}：先问机器能收多少，只按这个量从仓库扣，塞不进再退款。</li>
 *   <li>{@link #pull}：先问仓库能收多少，只按这个量从机器抽，塞不进再退回机器。</li>
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
     * @param adapter 机器侧资源接口
     * @return 本次结果
     */
    public static IoResult feed(MEStorage storage, ResourceAdapter adapter) {
        long capacity = adapter.capacity();
        if (capacity <= 0L) {
            return new IoResult(0L, IoResult.Reason.NO_SPACE);
        }

        // 0) 先模拟机器到底收不收，收不下就一个子儿都不动仓库
        long accepted = adapter.insert(capacity, true);
        if (accepted <= 0L) {
            // 有位置却不收：面不对（面配置不允许这个方向），不是「满了」
            return new IoResult(0L, IoResult.Reason.REJECTED);
        }

        // 1) 只按机器点头的量从仓库扣
        long available = AeKeySupport.extract(storage, adapter.key(), Math.min(capacity, accepted), true);
        if (available <= 0L) {
            return new IoResult(0L, IoResult.Reason.NO_ITEM);
        }
        long want = Math.min(available, accepted);
        long taken = AeKeySupport.extract(storage, adapter.key(), want, false);
        if (taken <= 0L) {
            return new IoResult(0L, IoResult.Reason.NO_ITEM);
        }

        // 2) 真塞；塞不进的退回仓库
        long inserted = adapter.insert(taken, false);
        long refund = taken - inserted;
        if (refund > 0L) {
            long refunded = AeKeySupport.insert(storage, adapter.key(), refund, false);
            if (refunded < refund) {
                IdleFactory.LOGGER.warn("IdleFactory IO refund failed: {} of {} {} could not go back",
                        refund - refunded, refund, adapter.key().getDisplayName().getString());
            }
        }
        return new IoResult(inserted, inserted > 0L ? IoResult.Reason.OK : IoResult.Reason.NO_SPACE);
    }

    /**
     * 从机器抽产物进仓库。
     *
     * @param storage 仓库
     * @param adapter 机器侧资源接口
     * @return 本次结果
     */
    public static IoResult pull(MEStorage storage, ResourceAdapter adapter) {
        // 1) 先问仓库：这个资源收不收、收多少
        long available = adapter.extract(Long.MAX_VALUE, true);
        if (available <= 0L) {
            return new IoResult(0L, IoResult.Reason.NO_ITEM);
        }
        long accepted = AeKeySupport.insert(storage, adapter.key(), available, true);
        if (accepted <= 0L) {
            return new IoResult(0L, IoResult.Reason.NO_SPACE);
        }

        // 2) 只按仓库点头的量从机器抽
        long want = Math.min(available, accepted);
        long taken = adapter.extract(want, false);
        if (taken <= 0L) {
            // 模拟说抽得出来、真抽却为零：这个面不对
            return new IoResult(0L, IoResult.Reason.REJECTED);
        }

        // 3) 真塞；塞不进的退回机器
        long inserted = AeKeySupport.insert(storage, adapter.key(), taken, false);
        long refund = taken - inserted;
        if (refund > 0L) {
            adapter.insert(refund, false);
        }
        return new IoResult(inserted, inserted > 0L ? IoResult.Reason.OK : IoResult.Reason.NO_SPACE);
    }

}
