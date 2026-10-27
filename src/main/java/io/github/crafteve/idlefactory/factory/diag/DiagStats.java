package io.github.crafteve.idlefactory.factory.diag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.crafteve.idlefactory.factory.io.IoResult;
import io.github.crafteve.idlefactory.factory.io.RuleKey;
import io.github.crafteve.idlefactory.factory.resource.ResourceKey;

/**
 * 一个工厂的诊断数据：事件环形缓冲 + 每条产线的搬运统计。
 *
 * <p>纯运行时，<b>不写存档</b>。事件只记状态迁移和第一次出错，成功搬运只进计数器。</p>
 *
 * <p>统计分两层：按资源（每秒窗口，用来显示「在搬什么、多快」）和按机器（最后搬运 tick、
 * 连续阻塞起点，用来回答「哪台卡了、卡了多久」）。</p>
 */
public final class DiagStats {
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
     * 取某条产线的统计，没有就建。
     *
     * @param lineId 产线 id
     * @return 统计
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

    /** 一条产线的搬运统计。 */
    public static final class LineStats {
        private final Map<ResourceKey, Long> inWindow = new HashMap<>();
        private final Map<ResourceKey, Long> outWindow = new HashMap<>();
        private final Map<ResourceKey, Long> rateIn = new HashMap<>();
        private final Map<ResourceKey, Long> rateOut = new HashMap<>();
        private final Map<UUID, GroupStats> groups = new HashMap<>();
        private long lastMoveTick;

        /**
         * 记一次成功搬运。
         *
         * @param machineId  机器
         * @param fed        按资源拆分的补料量
         * @param pulled     按资源拆分的抽取量
         * @param fedByRule  按规则拆分的补料量
         * @param pulledByRule 按规则拆分的抽取量
         * @param tick       时间
         */
        public void addMoved(UUID machineId, Map<ResourceKey, Long> fed,
                Map<ResourceKey, Long> pulled, Map<RuleKey, Long> fedByRule,
                Map<RuleKey, Long> pulledByRule, long tick) {
            for (Map.Entry<ResourceKey, Long> entry : fed.entrySet()) {
                this.inWindow.merge(entry.getKey(), entry.getValue(), Long::sum);
            }
            for (Map.Entry<ResourceKey, Long> entry : pulled.entrySet()) {
                this.outWindow.merge(entry.getKey(), entry.getValue(), Long::sum);
            }
            this.lastMoveTick = tick;
            GroupStats group = group(machineId);
            group.lastMoveTick = tick;
            group.blockedSince = -1L;
            group.inputReason = IoResult.Reason.OK;
            group.outputReason = IoResult.Reason.OK;
            group.inputFull = false;
            if (!fed.isEmpty()) {
                group.lastInputMoveTick = tick;
            }
            for (Map.Entry<RuleKey, Long> entry : fedByRule.entrySet()) {
                group.inRuleWindow.merge(entry.getKey(), entry.getValue(), Long::sum);
            }
            for (Map.Entry<RuleKey, Long> entry : pulledByRule.entrySet()) {
                group.outRuleWindow.merge(entry.getKey(), entry.getValue(), Long::sum);
            }
        }

        /**
         * 记一次「这一轮没搬动」。
         *
         * @param machineId   机器
         * @param inputReason 输入侧原因
         * @param outputReason 输出侧原因
         * @param inputFull   所有输入规则都无法接收
         * @param tick        时间
         */
        public void markBlocked(UUID machineId, IoResult.Reason inputReason,
                IoResult.Reason outputReason, boolean inputFull, long tick) {
            GroupStats group = group(machineId);
            group.inputReason = inputReason;
            group.outputReason = outputReason;
            group.inputFull = inputFull;
            group.lastReason = outputReason != IoResult.Reason.OK
                    ? outputReason : inputReason;
            if (group.blockedSince < 0L) {
                group.blockedSince = tick;
            }
        }

        /** 这条线最后一次成功搬运的时间。 */
        public long lastMoveTick() {
            return this.lastMoveTick;
        }

        /** 上一秒的补料速率，按资源。 */
        public Map<ResourceKey, Long> rateIn() {
            return Map.copyOf(this.rateIn);
        }

        /** 上一秒的抽取速率，按资源。 */
        public Map<ResourceKey, Long> rateOut() {
            return Map.copyOf(this.rateOut);
        }

        /** 上一秒的总搬运量（不同单位混在一起，只用于粗看）。 */
        public long totalRate() {
            long total = 0L;
            for (long value : this.rateIn.values()) {
                total += value;
            }
            for (long value : this.rateOut.values()) {
                total += value;
            }
            return total;
        }

        /**
         * 取一台机器的统计，没有就建。
         *
         * @param machineId 机器 id
         * @return 统计
         */
        public GroupStats group(UUID machineId) {
            return this.groups.computeIfAbsent(machineId, id -> new GroupStats());
        }

        /**
         * 取一台机器的统计；没有时返回 {@code null}，不创建空项。
         *
         * @param machineId 机器 id
         * @return 统计或 {@code null}
         */
        public GroupStats groupOrNull(UUID machineId) {
            return this.groups.get(machineId);
        }

        /** 所有机器的统计（只读）。 */
        public Map<UUID, GroupStats> groups() {
            return Map.copyOf(this.groups);
        }

        private void sample() {
            this.rateIn.clear();
            this.rateIn.putAll(this.inWindow);
            this.inWindow.clear();
            this.rateOut.clear();
            this.rateOut.putAll(this.outWindow);
            this.outWindow.clear();
            for (GroupStats group : this.groups.values()) {
                group.sample();
            }
        }
    }

    /** 一台机器的搬运统计。 */
    public static final class GroupStats {
        private final Map<RuleKey, Long> inRuleWindow = new HashMap<>();
        private final Map<RuleKey, Long> outRuleWindow = new HashMap<>();
        private final Map<RuleKey, Long> inRuleRate = new HashMap<>();
        private final Map<RuleKey, Long> outRuleRate = new HashMap<>();
        private final Map<RuleKey, IoResult.Reason> inRuleReasons = new HashMap<>();
        private final Map<RuleKey, IoResult.Reason> outRuleReasons = new HashMap<>();
        private long lastMoveTick;
        private long lastInputMoveTick;
        private long blockedSince = -1L;
        private boolean inputFull;
        private IoResult.Reason lastReason = IoResult.Reason.NO_ITEM;
        private IoResult.Reason inputReason = IoResult.Reason.OK;
        private IoResult.Reason outputReason = IoResult.Reason.OK;

        /** 最后一次成功搬运的时间；从没搬过为 0。 */
        public long lastMoveTick() {
            return this.lastMoveTick;
        }

        /** 最后一次成功输入的时间；从没输入过为 0。 */
        public long lastInputMoveTick() {
            return this.lastInputMoveTick;
        }

        /** 所有输入规则当前是否都无法接收。 */
        public boolean inputFull() {
            return this.inputFull;
        }

        /** 输入侧最近一次原因。 */
        public IoResult.Reason inputReason() {
            return this.inputReason;
        }

        /** 输出侧最近一次原因。 */
        public IoResult.Reason outputReason() {
            return this.outputReason;
        }

        /**
         * 一条规则上一秒的速率。
         *
         * @param key 规则
         * @return 数量/秒；没有统计为 0
         */
        public long rate(RuleKey key) {
            Long in = this.inRuleRate.get(key);
            Long out = this.outRuleRate.get(key);
            return (in == null ? 0L : in) + (out == null ? 0L : out);
        }

        /**
         * 一条输入规则上一秒的速率。
         *
         * @param key 规则
         * @return 数量/秒
         */
        public long rateIn(RuleKey key) {
            Long value = this.inRuleRate.get(key);
            return value == null ? 0L : value;
        }

        /**
         * 一条输出规则上一秒的速率。
         *
         * @param key 规则
         * @return 数量/秒
         */
        public long rateOut(RuleKey key) {
            Long value = this.outRuleRate.get(key);
            return value == null ? 0L : value;
        }

        /**
         * 覆盖写入逐规则原因。
         *
         * <p>每轮 IO 都调用一次，先清空再写入，避免已删除的规则留下旧诊断。缺省
         * 原因用 {@link IoResult.Reason#NO_ITEM}：界面把它解释成「还没有可搬的东西」，
         * 比「正常」更贴近从未运行过的规则。</p>
         *
         * @param input  逐输入规则原因
         * @param output 逐输出规则原因
         */
        public void setRuleReasons(Map<RuleKey, IoResult.Reason> input,
                Map<RuleKey, IoResult.Reason> output) {
            this.inRuleReasons.clear();
            this.inRuleReasons.putAll(input);
            this.outRuleReasons.clear();
            this.outRuleReasons.putAll(output);
        }

        /**
         * 取一条规则最近一次的原因。
         *
         * @param input 是否输入侧
         * @param key   规则
         * @return 原因；没有统计时为 {@link IoResult.Reason#NO_ITEM}
         */
        public IoResult.Reason ruleReason(boolean input, RuleKey key) {
            IoResult.Reason reason = (input ? this.inRuleReasons : this.outRuleReasons).get(key);
            return reason == null ? IoResult.Reason.NO_ITEM : reason;
        }

        private void sample() {
            this.inRuleRate.clear();
            this.inRuleRate.putAll(this.inRuleWindow);
            this.inRuleWindow.clear();
            this.outRuleRate.clear();
            this.outRuleRate.putAll(this.outRuleWindow);
            this.outRuleWindow.clear();
        }

        /** 连续没搬动的起点；{@code -1} 表示当前没卡。 */
        public long blockedSince() {
            return this.blockedSince;
        }

        /** 这一轮没搬动的原因。 */
        public IoResult.Reason lastReason() {
            return this.lastReason;
        }
    }
}
