package io.github.crafteve.idlefactory.factory.node;

import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEKey;

import net.minecraft.core.Direction;

/**
 * 一整单待写入：AE2 派过来的一单原料，含全部资源与数量（数量比即样板比例）。
 *
 * <p>订单自包含：材料在 pushPattern 时已经从 ME 网络抽走，所以它不依赖样板是否还在，也不
 * 因为目标机器暂时不在而丢弃——机器没加载就等，机器被删就退回网络，退回失败才按孤儿处理。</p>
 *
 * <p>落档，跨重启保留：整单丢失等于那批材料消失、AE2 合成任务永久卡死。</p>
 *
 * @param lines      单行资源（至少一行）
 * @param machineId  目标机器；读档时缺失为 {@code null}
 * @param enqueuedAt 入队时刻，给界面显示「等了多久」
 * @param orphanedAt 变成孤儿订单的时刻；{@code -1} 表示不是孤儿
 */
public record PendingOrder(List<Line> lines, @Nullable UUID machineId, long enqueuedAt,
        long orphanedAt) {
    /** 孤儿订单等网络恢复的最长时刻（tick），超时按「静默丢弃」处理。 */
    public static final long ORPHAN_TIMEOUT_TICKS = 1200L;

    public PendingOrder {
        lines = List.copyOf(lines);
    }

    /**
     * 一单里的一行。
     *
     * @param key    资源
     * @param amount 这一单里该资源的数量（一份样板的比例）
     * @param face   选面时绑定的面；{@code null} 表示投递时再选
     */
    public record Line(AEKey key, long amount, @Nullable Direction face) {
    }

    /** 这一单是不是孤儿（目标机器已删、正在等网络恢复）。 */
    public boolean orphan() {
        return this.orphanedAt >= 0L;
    }

    /**
     * 标记成孤儿并保留原内容。
     *
     * @param now 当前游戏时间
     * @return 新订单
     */
    public PendingOrder asOrphan(long now) {
        return new PendingOrder(this.lines, this.machineId, this.enqueuedAt, now);
    }

    /**
     * 用剩余行替换内容。
     *
     * @param remaining 剩余行
     * @return 新订单
     */
    public PendingOrder withLines(List<Line> remaining) {
        return new PendingOrder(remaining, this.machineId, this.enqueuedAt, this.orphanedAt);
    }
}
