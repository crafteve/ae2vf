package io.github.crafteve.idlefactory.factory.io;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import appeng.api.stacks.AEKey;

/**
 * 一个工厂的搬运统计：每台机器一份「最后搬运 tick、连续阻塞起点、逐规则原因与速率」。
 *
 * <p>纯运行时，<b>不写存档</b>。事件流与诊断文本出口在移除 /idf 指令时一并删除，
 * 本类只保留界面快照读得动的机器级数据。</p>
 */
public final class DiagStats {
    /** 每台机器的统计。 */
    private final Map<UUID, MachineStats> machines = new HashMap<>();

    /**
     * 取一台机器的统计，没有就建。
     *
     * @param machineId 机器 id
     * @return 统计
     */
    public MachineStats machine(UUID machineId) {
        return this.machines.computeIfAbsent(machineId, id -> new MachineStats());
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

    /**
     * 采样一次速率。由 {@code FactoryTickRunner} 每秒调一次。
     */
    public void sample() {
        for (MachineStats stats : this.machines.values()) {
            stats.sample();
        }
    }

    /** 一台机器的搬运统计。 */
    public static final class MachineStats {
        private final Map<RuleKey, Long> inRuleWindow = new HashMap<>();
        private final Map<RuleKey, Long> outRuleWindow = new HashMap<>();
        private final Map<RuleKey, Long> inRuleRate = new HashMap<>();
        private final Map<RuleKey, Long> outRuleRate = new HashMap<>();
        private final Map<RuleKey, Reason> inRuleReasons = new HashMap<>();
        private final Map<RuleKey, Reason> outRuleReasons = new HashMap<>();
        private long lastMoveTick;
        private long lastInputMoveTick;
        private long blockedSince = -1L;
        private boolean inputFull;
        private Reason lastReason = Reason.NO_MATERIAL;
        private Reason inputReason = Reason.RUN;
        private Reason outputReason = Reason.RUN;

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
            this.lastMoveTick = tick;
            this.blockedSince = -1L;
            this.inputReason = Reason.RUN;
            this.outputReason = Reason.RUN;
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
        public void markBlocked(Reason inputReason,
                Reason outputReason, boolean inputFull, long tick) {
            this.inputReason = inputReason;
            this.outputReason = outputReason;
            this.inputFull = inputFull;
            this.lastReason = outputReason != Reason.RUN
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
        public Reason inputReason() {
            return this.inputReason;
        }

        /** 输出侧最近一次原因。 */
        public Reason outputReason() {
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
         * 原因用 {@link Reason#NO_MATERIAL}：界面把它解释成「还没有可搬的东西」，
         * 比「正常」更贴近从未运行过的规则。</p>
         *
         * @param input  逐输入规则原因
         * @param output 逐输出规则原因
         */
        public void setRuleReasons(Map<RuleKey, Reason> input,
                Map<RuleKey, Reason> output) {
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
         * @return 原因；没有统计时为 {@link Reason#NO_MATERIAL}
         */
        public Reason ruleReason(boolean input, RuleKey key) {
            Reason reason = (input ? this.inRuleReasons : this.outRuleReasons).get(key);
            return reason == null ? Reason.NO_MATERIAL : reason;
        }

        /** 连续没搬动的起点；{@code -1} 表示当前没卡。 */
        public long blockedSince() {
            return this.blockedSince;
        }

        /** 这一轮没搬动的原因。 */
        public Reason lastReason() {
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
