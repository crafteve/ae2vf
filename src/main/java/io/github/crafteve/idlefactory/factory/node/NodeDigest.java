package io.github.crafteve.idlefactory.factory.node;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.io.Reason;

/**
 * 节点级历史结论：主动侧的可观测面。
 *
 * <p>主动侧没有表单单元，所以失败不写单元账本，只在这里留摘要：有没有单卡住、卡了多久、
 * 最近一次为什么失败、一共送进去多少。界面据此显示「有单卡住」，不需要任务台账（D23）。</p>
 */
public final class NodeDigest {
    private int pendingCount;
    private long pendingOldestAt = -1L;
    private int entryAttempts;
    @Nullable
    private Reason entryLastError;
    private long deliveredTotal;

    /** 队列里还有几单。 */
    public int pendingCount() {
        return this.pendingCount;
    }

    /** 队首入队的时刻；没有单时为 {@code -1}。 */
    public long pendingOldestAt() {
        return this.pendingOldestAt;
    }

    /** 累计投递尝试次数。 */
    public int entryAttempts() {
        return this.entryAttempts;
    }

    /** 最近一次投递失败的原因；没失败过为 {@code null}。 */
    @Nullable
    public Reason entryLastError() {
        return this.entryLastError;
    }

    /** 累计投递进机器的数量。 */
    public long deliveredTotal() {
        return this.deliveredTotal;
    }

    /**
     * 更新队列长度与队首时刻。
     *
     * @param count     当前队列单数
     * @param oldestAt  队首入队时刻；队列空时传 {@code -1}
     */
    public void pending(int count, long oldestAt) {
        this.pendingCount = count;
        this.pendingOldestAt = count == 0 ? -1L : oldestAt;
    }

    /**
     * 记一次投递尝试。
     *
     * @param delivered 本次送进机器的数量
     * @param error     失败原因；成功为 {@code null}
     */
    public void attempted(long delivered, @Nullable Reason error) {
        this.entryAttempts++;
        this.deliveredTotal += delivered;
        this.entryLastError = error;
    }
}
