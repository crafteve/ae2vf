package io.github.crafteve.idlefactory.factory.diag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一个工厂的诊断数据：事件环形缓冲 + 每条产线的搬运计数。
 *
 * <p>纯运行时，<b>不写存档</b>。事件只记状态迁移和第一次出错，成功搬运只进计数器——
 * 一条线一分钟搬几百次，每次都记事件的话，聊天栏会刷屏，{@code tail} 也失去意义。</p>
 *
 * <p>缓冲区满了覆盖最旧的一条，容量固定，内存有界。</p>
 */
public final class Diagnostics {
    /** 事件容量。 */
    private static final int CAPACITY = 200;

    private final DiagEvent[] events = new DiagEvent[CAPACITY];
    /** 累计写入条数，用来算当前有效条数。 */
    private int count;
    /** 下一条写到哪。 */
    private int next;

    private final Map<UUID, LineStats> lines = new HashMap<>();

    /**
     * 记一条事件。
     *
     * @param event 事件
     */
    public void record(DiagEvent event) {
        this.events[this.next] = event;
        this.next = (this.next + 1) % CAPACITY;
        this.count++;
    }

    /**
     * 取最近的若干条，按时间从旧到新。
     *
     * @param max 最多几条
     * @return 事件列表
     */
    public List<DiagEvent> recent(int max) {
        int available = Math.min(this.count, CAPACITY);
        int wanted = Math.min(Math.max(max, 0), available);
        List<DiagEvent> result = new ArrayList<>(wanted);
        for (int i = wanted - 1; i >= 0; i--) {
            int index = Math.floorMod(this.next - 1 - i, CAPACITY);
            DiagEvent event = this.events[index];
            if (event != null) {
                result.add(event);
            }
        }
        return result;
    }

    /** 当前有效条数。 */
    public int size() {
        return Math.min(this.count, CAPACITY);
    }

    /** 清空。 */
    public void clear() {
        for (int i = 0; i < CAPACITY; i++) {
            this.events[i] = null;
        }
        this.count = 0;
        this.next = 0;
        this.lines.clear();
    }

    /**
     * 取某条产线的计数，没有就建。
     *
     * @param lineId 产线 id
     * @return 计数
     */
    public LineStats line(UUID lineId) {
        return this.lines.computeIfAbsent(lineId, id -> new LineStats());
    }

    /**
     * 采样一次速率。由 {@code FactoryRuntime} 每秒调一次。
     */
    public void sample() {
        for (LineStats stats : this.lines.values()) {
            stats.sample();
        }
    }

    /** 一条产线的搬运计数。只记总量，输入输出明细由体检现场探测。 */
    public static final class LineStats {
        private long movedInTotal;
        private long movedOutTotal;
        private long inWindow;
        private long outWindow;
        private long lastMoveTick;
        private long rateIn;
        private long rateOut;

        /**
         * 记一次搬运。
         *
         * @param fed    补进机器的数量
         * @param pulled 从机器抽出的数量
         * @param tick   时间
         */
        public void addMoved(long fed, long pulled, long tick) {
            this.movedInTotal += fed;
            this.movedOutTotal += pulled;
            this.inWindow += fed;
            this.outWindow += pulled;
            this.lastMoveTick = tick;
        }

        /** 累计补进机器的数量。 */
        public long movedInTotal() {
            return this.movedInTotal;
        }

        /** 累计从机器抽出的数量。 */
        public long movedOutTotal() {
            return this.movedOutTotal;
        }

        /** 最近一次搬运的时间。 */
        public long lastMoveTick() {
            return this.lastMoveTick;
        }

        /** 上一秒补进机器的数量。 */
        public long rateIn() {
            return this.rateIn;
        }

        /** 上一秒从机器抽出的数量。 */
        public long rateOut() {
            return this.rateOut;
        }

        /** 上一秒的总搬运量。 */
        public long ratePerSecond() {
            return this.rateIn + this.rateOut;
        }

        private void sample() {
            this.rateIn = this.inWindow;
            this.rateOut = this.outWindow;
            this.inWindow = 0L;
            this.outWindow = 0L;
        }
    }
}
