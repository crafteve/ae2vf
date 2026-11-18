package io.github.crafteve.idlefactory.node;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.node.NodeDispatchQueue;
import io.github.crafteve.idlefactory.node.NodeDispatchOrder;
import io.github.crafteve.idlefactory.resource.AeKeyAdapter;

import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;

/**
 * 一个节点的待投递队列：多单排队，只认 {@link NodeDispatchOrder}。
 *
 * <p>它是内存与存档的<b>唯一写入口</b>：入队、取队首若干单、按 id 更新、出队都走这里。调用方
 * 拿到的订单是值对象，改完必须 {@link #update(NodeDispatchOrder)} 才会生效——绕过这里直接改内部
 * 列表会让进度与存档悄悄分叉。</p>
 *
 * <p>队列属于节点，不属于某台机器：删一台机器不影响订单；只有节点销毁或整单超时才退剩余量。</p>
 */
public final class NodeDispatchQueue {
    /** 队列单数硬顶，不可由 config 调整；满了这单不收（CPU 另找别家）。 */
    public static final int MAX_PENDING = 64;

    private final List<NodeDispatchOrder> orders = new ArrayList<>();
    /** 自上次收口以来有没有改过内容；节点据此决定要不要落盘。 */
    private boolean dirty;

    /**
     * 追加一单。
     *
     * @param order 订单
     * @return 是否入队；队列已满或订单已投完时返回 {@code false}
     */
    public boolean enqueue(NodeDispatchOrder order) {
        if (order.finished() || this.orders.size() >= MAX_PENDING) {
            return false;
        }
        this.orders.add(order);
        this.dirty = true;
        return true;
    }

    /**
     * 取队首若干单（只读快照）。
     *
     * @param limit 最多几单；{@code <= 0} 返回空表
     * @return 订单列表，顺序即队列顺序
     */
    public List<NodeDispatchOrder> head(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        int count = Math.min(limit, this.orders.size());
        return List.copyOf(this.orders.subList(0, count));
    }

    /**
     * 队首订单。
     *
     * @return 队首；队列空时为 {@code null}
     */
    @Nullable
    public NodeDispatchOrder first() {
        return this.orders.isEmpty() ? null : this.orders.get(0);
    }

    /** 队列里的单数。 */
    public int size() {
        return this.orders.size();
    }

    /** 队列是否为空。 */
    public boolean isEmpty() {
        return this.orders.isEmpty();
    }

    /** 全部订单的只读快照。 */
    public List<NodeDispatchOrder> all() {
        return List.copyOf(this.orders);
    }

    /**
     * 按 id 出队。
     *
     * @param id 订单 id
     * @return 是否移除了
     */
    public boolean remove(UUID id) {
        boolean removed = this.orders.removeIf(order -> order.id().equals(id));
        this.dirty |= removed;
        return removed;
    }

    /**
     * 按 id 原地替换一单。
     *
     * @param order 新订单（id 不变）
     * @return 是否找到了原单
     */
    public boolean update(NodeDispatchOrder order) {
        for (int index = 0; index < this.orders.size(); index++) {
            if (this.orders.get(index).id().equals(order.id())) {
                this.orders.set(index, order);
                this.dirty = true;
                return true;
            }
        }
        return false;
    }

    /**
     * 整体替换队列；读档用。
     *
     * @param loaded 读出来的订单；超过 {@link #MAX_PENDING} 的部分丢弃
     */
    public void replaceAll(List<NodeDispatchOrder> loaded) {
        this.orders.clear();
        this.dirty = false;
        for (NodeDispatchOrder order : loaded) {
            if (this.orders.size() >= MAX_PENDING) {
                break;
            }
            if (!order.finished()) {
                this.orders.add(order);
            }
        }
    }

    /** 清空队列（已完成的单）。 */
    public void clear() {
        this.orders.clear();
        this.dirty = true;
    }

    /**
     * 取走「内容变过」的标记；节点每 tick 调一次，决定要不要标脏落盘。
     *
     * @return 上次收口后改过为 {@code true}
     */
    public boolean consumeDirty() {
        boolean was = this.dirty;
        this.dirty = false;
        return was;
    }

    /**
     * 把整条队列的剩余量尽力退回网络；退不回按 D21 记日志丢弃。
     *
     * <p>供删节点与以后的手动「清空队列」按钮用。退回的是每行剩余量（含半份里没写进去的
     * 部分），已经进机器的部分不退。</p>
     *
     * @param network 仓库；离线时为 {@code null}，整条队列按丢弃处理
     * @param now     当前游戏时间
     * @return 退回报告
     */
    public RefundReport refundAll(@Nullable MEStorage network, long now) {
        int orderCount = this.orders.size();
        int lineCount = 0;
        long lost = 0L;
        for (NodeDispatchOrder order : this.orders) {
            long[] remaining = order.remainingAmounts();
            for (int index = 0; index < remaining.length; index++) {
                long amount = remaining[index];
                if (amount <= 0L) {
                    continue;
                }
                lineCount++;
                AEKey key = order.perPortion().get(index).key();
                long inserted = this.refund(network, key, amount);
                lost += amount - inserted;
            }
        }
        this.orders.clear();
        this.dirty = true;
        if (lost > 0L) {
            IdleFactory.LOGGER.warn("Idle Factory: 队列退回网络失败，{} 件已按丢弃处理（{}）",
                    lost, now);
        }
        return new RefundReport(orderCount, lineCount, lost);
    }

    /**
     * 一次退回；网络不收时返回 0，不抛异常。
     */
    private long refund(@Nullable MEStorage network, AEKey key, long amount) {
        if (network == null) {
            // 仓库离线：没有可退回的目标，按丢弃记账，由调用方汇总记日志
            return 0L;
        }
        try {
            return network.insert(key, amount, appeng.api.config.Actionable.MODULATE,
                    AeKeyAdapter.SOURCE);
        } catch (RuntimeException e) {
            IdleFactory.LOGGER.warn("Idle Factory: 退回队列材料异常（{}）：{}",
                    AeKeyAdapter.describe(key), e.toString());
            return 0L;
        }
    }

    /**
     * 退回结果。
     *
     * @param orders 处理了几单
     * @param lines  处理了几行
     * @param lost   退回失败、按丢弃处理的量
     */
    public record RefundReport(int orders, int lines, long lost) {
    }
}
