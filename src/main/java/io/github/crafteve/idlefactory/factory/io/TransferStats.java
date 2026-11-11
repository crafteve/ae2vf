package io.github.crafteve.idlefactory.factory.io;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 一个工厂的搬运速率统计：每台机器一份「按规则的一秒窗口与速率」。
 *
 * <p>纯运行时，不写存档。原因、上次成功、连续空转都不在这里——那些是账本与历史结论的职责，
 * 本类只回答「每秒搬了多少」这一个界面问题。</p>
 */
public final class TransferStats {
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

    /** 采样一次速率。由编排层每秒调一次。 */
    public void sample() {
        for (MachineStats stats : this.machines.values()) {
            stats.sample();
        }
    }

    /** 一台机器的搬运速率。 */
    public static final class MachineStats {
        private final Map<RuleKey, Long> inRuleWindow = new HashMap<>();
        private final Map<RuleKey, Long> outRuleWindow = new HashMap<>();
        private final Map<RuleKey, Long> inRuleRate = new HashMap<>();
        private final Map<RuleKey, Long> outRuleRate = new HashMap<>();

        /**
         * 记一次成功搬运（进窗口，采样时结算成速率）。
         *
         * @param fedByRule    按规则拆分的补料量
         * @param pulledByRule 按规则拆分的抽取量
         */
        public void addMoved(Map<RuleKey, Long> fedByRule, Map<RuleKey, Long> pulledByRule) {
            for (Map.Entry<RuleKey, Long> entry : fedByRule.entrySet()) {
                this.inRuleWindow.merge(entry.getKey(), entry.getValue(), Long::sum);
            }
            for (Map.Entry<RuleKey, Long> entry : pulledByRule.entrySet()) {
                this.outRuleWindow.merge(entry.getKey(), entry.getValue(), Long::sum);
            }
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
