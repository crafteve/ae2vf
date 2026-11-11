package io.github.crafteve.idlefactory.factory.node;

/**
 * 节点级历史结论：主动侧的可观测面。
 *
 * <p>主动侧没有表单单元，所以失败不写单元账本，只在这里留摘要：有没有单卡住、卡了多久、
 * 最近一次为什么失败、一共送进去多少。界面据此显示「有单卡住」，不需要任务台账（D23）。</p>
 */
public final class NodeDigest {
    private int pendingCount;
    private long pendingOldestAt = -1L;

    /** 队列里还有几单。 */
    public int pendingCount() {
        return this.pendingCount;
    }

    /** 队首入队的时刻；没有单时为 {@code -1}。 */
    public long pendingOldestAt() {
        return this.pendingOldestAt;
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
}
