package io.github.crafteve.idlefactory.factory.io;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import appeng.api.stacks.AEKey;

/**
 * 一个工厂的诊断数据：事件环形缓冲 + 每台机器的搬运统计 + 工厂级资源速率。
 *
 * <p>纯运行时，<b>不写存档</b>。事件只记状态迁移和第一次出错，成功搬运只进计数器。</p>
 *
 * <p>P1 删除产线后统计只剩一层：按机器记「最后搬运 tick、连续阻塞起点、逐规则原因」。
 * 「在搬什么、多快」是工厂级的，直接挂在本类上，不再按线分组。</p>
 */
public final class DiagStats {
    /** 事件容量。 */
    private static final int CAPACITY = 200;

    private final DiagEvent[] events = new DiagEvent[CAPACITY];
    /** 累计写入条数，用来算当前有效条数。 */
    private int count;
    /** 下一条写到哪。 */
    private int next;

    /** 每台机器的统计。 */
    private final Map<UUID, MachineStats> machines = new HashMap<>();
    /** 本秒窗口内的补料量，按资源。 */
    private final Map<AEKey, Long> inWindow = new HashMap<>();
    /** 本秒窗口内的抽取量，按资源。 */
    private final Map<AEKey, Long> outWindow = new HashMap<>();
    /** 上一秒的补料速率，按资源。 */
    private final Map<AEKey, Long> rateIn = new HashMap<>();
    /** 上一秒的抽取速率，按资源。 */
    private final Map<AEKey, Long> rateOut = new HashMap<>();
    /** 整个工厂最后一次成功搬运的时间。 */
    private long lastMoveTick;

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
        this.machines.clear();
        this.inWindow.clear();
        this.outWindow.clear();
        this.rateIn.clear();
        this.rateOut.clear();
        this.lastMoveTick = 0L;
    }

    /**
     * 取一台机器的统计，没有就建。
     *
     * @param machineId 机器 id
     * @return 统计
     */
    public MachineStats machine(UUID machineId) {
        return this.machines.computeIfAbsent(machineId, id -> new MachineStats(this));
    }

    /**
     * 取一台机器的统计；没有时返回 {@code null}，不创建空项。
     *
     * @param machineId 机器 id
     * @return 统计或 {@code null}
     */
    public MachineStats machineOrNull(UUID machineId) {
        return this.machines.get(machineId);
    }

    /** 所有机器的统计（只读）。 */
    public Map<UUID, MachineStats> machines() {
        return Map.copyOf(this.machines);
    }

    /** 整个工厂最后一次成功搬运的时间；从没搬过为 0。 */
    public long lastMoveTick() {
        return this.lastMoveTick;
    }

    /** 上一秒的补料速率，按资源。 */
    public Map<AEKey, Long> rateIn() {
        return Map.copyOf(this.rateIn);
    }

    /** 上一秒的抽取速率，按资源。 */
    public Map<AEKey, Long> rateOut() {
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
     * 当前处于阻塞状态的机器数。
     *
     * @return 机器数
     */
    public int blockedMachines() {
        int blocked = 0;
        for (MachineStats stats : this.machines.values()) {
            if (stats.blockedSince() >= 0L) {
                blocked++;
            }
        }
        return blocked;
    }

    /**
     * 采样一次速率。由 {@code FactoryRuntime} 每秒调一次。
     */
    public void sample() {
        this.rateIn.clear();
        this.rateIn.putAll(this.inWindow);
        this.inWindow.clear();
        this.rateOut.clear();
        this.rateOut.putAll(this.outWindow);
        this.outWindow.clear();
        for (MachineStats stats : this.machines.values()) {
            stats.sample();
        }
    }

    /**
     * 记一次成功搬运到工厂级窗口。
     *
     * @param fed     按资源拆分的补料量
     * @param pulled  按资源拆分的抽取量
     * @param tick    时间
     */
    private void addFactoryMoved(Map<AEKey, Long> fed, Map<AEKey, Long> pulled, long tick) {
        for (Map.Entry<AEKey, Long> entry : fed.entrySet()) {
            this.inWindow.merge(entry.getKey(), entry.getValue(), Long::sum);
        }
        for (Map.Entry<AEKey, Long> entry : pulled.entrySet()) {
            this.outWindow.merge(entry.getKey(), entry.getValue(), Long::sum);
        }
        this.lastMoveTick = tick;
    }

    /** 一台机器的搬运统计。 */
    public static final class MachineStats {
        /** 所属工厂统计；工厂级资源速率记在它身上。 */
        private final DiagStats owner;
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

        private MachineStats(DiagStats owner) {
            this.owner = owner;
        }

        /**
         * 记一次成功搬运。
         *
         * @param fed          按资源拆分的补料量
         * @param pulled       按资源拆分的抽取量
         * @param fedByRule    按规则拆分的补料量
         * @param pulledByRule 按规则拆分的抽取量
         * @param tick         时间
         */
        public void addMoved(Map<AEKey, Long> fed, Map<AEKey, Long> pulled,
                Map<RuleKey, Long> fedByRule, Map<RuleKey, Long> pulledByRule, long tick) {
            this.owner.addFactoryMoved(fed, pulled, tick);
            this.lastMoveTick = tick;
            this.blockedSince = -1L;
            this.inputReason = IoResult.Reason.OK;
            this.outputReason = IoResult.Reason.OK;
            this.inputFull = false;
            if (!fed.isEmpty()) {
                this.lastInputMoveTick = tick;
            }
            for (Map.Entry<RuleKey, Long> entry : fedByRule.entrySet()) {
                this.inRuleWindow.merge(entry.getKey(), entry.getValue(), Long::sum);
            }
            for (Map.Entry<RuleKey, Long> entry : pulledByRule.entrySet()) {
                this.outRuleWindow.merge(entry.getKey(), entry.getValue(), Long::sum);
            }
        }

        /**
         * 记一次「这一轮没搬动」。
         *
         * @param inputReason  输入侧原因
         * @param outputReason 输出侧原因
         * @param inputFull    所有输入规则都无法接收
         * @param tick         时间
         */
        public void markBlocked(IoResult.Reason inputReason,
                IoResult.Reason outputReason, boolean inputFull, long tick) {
            this.inputReason = inputReason;
            this.outputReason = outputReason;
            this.inputFull = inputFull;
            this.lastReason = outputReason != IoResult.Reason.OK
                    ? outputReason : inputReason;
            if (this.blockedSince < 0L) {
                this.blockedSince = tick;
            }
        }

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

        /** 连续没搬动的起点；{@code -1} 表示当前没卡。 */
        public long blockedSince() {
            return this.blockedSince;
        }

        /** 这一轮没搬动的原因。 */
        public IoResult.Reason lastReason() {
            return this.lastReason;
        }

        private void sample() {
            this.inRuleRate.clear();
            this.inRuleRate.putAll(this.inRuleWindow);
            this.inRuleWindow.clear();
            this.outRuleRate.clear();
            this.outRuleRate.putAll(this.outRuleWindow);
            this.outRuleWindow.clear();
        }
    }
}
