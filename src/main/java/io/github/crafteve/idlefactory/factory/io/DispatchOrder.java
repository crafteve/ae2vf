package io.github.crafteve.idlefactory.factory.io;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEKey;

/**
 * 一整单待投递：AE2 派下来的一单原料，含每份用量、总份数与投递进度。
 *
 * <p>订单属于<b>节点</b>而不属于某台机器：材料在 pushPattern 时已经从 ME 网络抽走，所以它
 * 不依赖样板是否还在，也不因为目标机器暂时不在而丢弃。一单可以分给多台机器投递，剩余量留到
 * 下一 tick；只有节点销毁、成员机器被删光或孤儿超时才退剩余量。</p>
 *
 * <p><b>进度是整单的，不按机器拆</b>：{@link #donePortions()} 是已完整写进机器的份数；
 * {@link #openBatches()} 是几台机器上还没补完的半份（机器 id → 每行还差多少）。每行已写量与
 * 剩余量都由这两者现算，不另存一份 {@code delivered}——两份账必然会打架。</p>
 *
 * <p>落档，跨重启保留：整单丢失等于那批材料消失、AE2 合成任务永久卡死。</p>
 *
 * @param id           订单 id（跨 tick / 跨重启稳定）
 * @param perPortion   每份的资源与用量，不带面（面在投递时按目标机器解析）
 * @param portions     总份数
 * @param donePortions 已完整写入机器的份数
 * @param openBatches  机器 id → 当前半份还差多少
 * @param nextMachineId 轮询起点；{@code null} 表示从第一台开始
 * @param retryAt      退避到期时刻；{@code 0} 表示随时可试
 * @param enqueuedAt   入队时刻，给界面显示「等了多久」
 * @param orphanedAt   变成孤儿订单的时刻；{@code -1} 表示不是孤儿
 */
public record DispatchOrder(UUID id, List<Line> perPortion, long portions, long donePortions,
        Map<UUID, OpenBatch> openBatches, @Nullable UUID nextMachineId, long retryAt,
        long enqueuedAt, long orphanedAt) {

    public DispatchOrder {
        if (id == null || perPortion == null || portions <= 0L || donePortions < 0L) {
            throw new IllegalArgumentException("订单字段非法");
        }
        perPortion = List.copyOf(perPortion);
        openBatches = Map.copyOf(openBatches);
        if (perPortion.isEmpty()) {
            throw new IllegalArgumentException("订单至少要有一行");
        }
    }

    /**
     * 新建一单：全部分数都没投，没有半份。
     *
     * @param id         订单 id
     * @param perPortion 每份用量
     * @param portions   总份数
     * @param now        入队时刻
     * @return 新订单
     */
    public static DispatchOrder create(UUID id, List<Line> perPortion, long portions, long now) {
        return new DispatchOrder(id, perPortion, portions, 0L, Map.of(), null, 0L, now, -1L);
    }

    /**
     * 一单里的一行：资源 + 一份的用量。
     *
     * @param key    资源
     * @param amount 一份的用量，必须为正
     */
    public record Line(AEKey key, long amount) {
        public Line {
            if (key == null || amount <= 0L) {
                throw new IllegalArgumentException("订单行必须带资源与正数量");
            }
        }
    }

    /**
     * 一台机器上的半份：每行还差多少。
     *
     * <p>数组长度与 {@link #perPortion()} 对齐。构造时防御性拷贝，读取用
     * {@link #missing(int)}；不要直接改返回的数组。</p>
     *
     * @param missingPerLine 每行还差多少
     */
    public record OpenBatch(long[] missingPerLine) {
        public OpenBatch {
            missingPerLine = missingPerLine.clone();
        }

        /** 第 {@code index} 行还差多少。 */
        public long missing(int index) {
            return this.missingPerLine[index];
        }

        /** 行数。 */
        public int size() {
            return this.missingPerLine.length;
        }

        /** 是否还有没写进去的量。 */
        public boolean hasMissing() {
            for (long value : this.missingPerLine) {
                if (value > 0L) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 这一单是不是孤儿（目标机器已删、正在等网络恢复）。 */
    public boolean orphan() {
        return this.orphanedAt >= 0L;
    }

    /** 还没完整交出去的份数。 */
    public long remainingPortions() {
        return Math.max(0L, this.portions - this.donePortions);
    }

    /** 这一单是否已经投完（含半份已补齐）。 */
    public boolean finished() {
        return this.donePortions >= this.portions && this.openBatches.isEmpty();
    }

    /**
     * 标记成孤儿并保留原内容。
     *
     * @param now 当前游戏时间
     * @return 新订单
     */
    public DispatchOrder asOrphan(long now) {
        return new DispatchOrder(this.id, this.perPortion, this.portions, this.donePortions,
                this.openBatches, this.nextMachineId, this.retryAt, this.enqueuedAt, now);
    }

    /**
     * 把某个时刻设为退避到期点。
     *
     * @param at 到期 tick；{@code 0} 表示立刻可试
     * @return 新订单
     */
    public DispatchOrder withRetryAt(long at) {
        return new DispatchOrder(this.id, this.perPortion, this.portions, this.donePortions,
                this.openBatches, this.nextMachineId, at, this.enqueuedAt, this.orphanedAt);
    }

    /**
     * 更新轮询起点。
     *
     * @param machineId 下一轮从这台机器的下一台开始；{@code null} 表示从头
     * @return 新订单
     */
    public DispatchOrder withNextMachine(@Nullable UUID machineId) {
        return new DispatchOrder(this.id, this.perPortion, this.portions, this.donePortions,
                this.openBatches, machineId, this.retryAt, this.enqueuedAt, this.orphanedAt);
    }

    /**
     * 记录已完整交出的份数，并清掉孤儿标记。
     *
     * @param done 新的已完成份数
     * @return 新订单
     */
    public DispatchOrder withDone(long done) {
        return new DispatchOrder(this.id, this.perPortion, this.portions, done,
                this.openBatches, this.nextMachineId, this.retryAt, this.enqueuedAt, -1L);
    }

    /**
     * 写入 / 更新一台机器上的半份。
     *
     * @param machineId 机器 id
     * @param missing   每行还差多少
     * @return 新订单
     */
    public DispatchOrder withOpenBatch(UUID machineId, long[] missing) {
        Map<UUID, OpenBatch> next = new LinkedHashMap<>(this.openBatches);
        next.put(machineId, new OpenBatch(missing));
        return new DispatchOrder(this.id, this.perPortion, this.portions, this.donePortions,
                next, this.nextMachineId, this.retryAt, this.enqueuedAt, this.orphanedAt);
    }

    /**
     * 补完一份：半份清单加一，并去掉这台机器的半份记录。
     *
     * @param machineId 机器 id
     * @return 新订单
     */
    public DispatchOrder completeBatch(UUID machineId) {
        Map<UUID, OpenBatch> next = new LinkedHashMap<>(this.openBatches);
        next.remove(machineId);
        return new DispatchOrder(this.id, this.perPortion, this.portions, this.donePortions + 1L,
                next, this.nextMachineId, this.retryAt, this.enqueuedAt, -1L);
    }

    /**
     * 去掉一台机器的半份记录，不动已完成份数（退款在别处做）。
     *
     * @param machineId 机器 id
     * @return 新订单
     */
    public DispatchOrder removeBatch(UUID machineId) {
        Map<UUID, OpenBatch> next = new LinkedHashMap<>(this.openBatches);
        next.remove(machineId);
        return new DispatchOrder(this.id, this.perPortion, this.portions, this.donePortions,
                next, this.nextMachineId, this.retryAt, this.enqueuedAt, this.orphanedAt);
    }

    /**
     * 把一条订单的所有行摊成 {@code perPortion × n}。
     *
     * @param count 份数
     * @return 逐行数量，顺序与 {@link #perPortion()} 一致
     */
    public long[] amountsFor(long count) {
        long[] amounts = new long[this.perPortion.size()];
        for (int index = 0; index < amounts.length; index++) {
            amounts[index] = this.perPortion.get(index).amount() * count;
        }
        return amounts;
    }

    /**
     * 每行还没交出去的量（含半份里没写进去的部分）。
     *
     * @return 逐行剩余量，顺序与 {@link #perPortion()} 一致
     */
    public long[] remainingAmounts() {
        long[] remaining = this.amountsFor(this.remainingPortions());
        for (OpenBatch batch : this.openBatches.values()) {
            for (int index = 0; index < batch.size() && index < remaining.length; index++) {
                remaining[index] += batch.missing(index);
            }
        }
        return remaining;
    }
}
