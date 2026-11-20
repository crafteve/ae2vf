package io.github.crafteve.ae2vf.config;

import io.github.crafteve.ae2vf.node.Node;
import io.github.crafteve.ae2vf.node.NodeIo;
import io.github.crafteve.ae2vf.node.NodeLibrary;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 工厂玩法上限与观测调优配置。
 *
 * <p>所有玩法上限默认 {@code -1}，表示不设上限；正数表示上限；{@code 0} 非法。
 * 负数一律按「用硬上限」处理，也就是实际值 = {@code min(config, 硬上限)}。
 * config 改小不会删已有数据，只阻止继续新增。</p>
 *
 * <p>硬上限不可关：它们同时是网络与存档解码的上限，用来防溢出与恶意包。
 * {@link #observationHealthBudgetPerTick}、{@link #observationWorkBudgetPerTick}、
 * {@link #observationEventBudgetPerTick} 与 {@link #staleHealthTicks} 是调优项，
 * 不是玩法上限，默认值非 -1。</p>
 */
public final class VoidFactoryConfig {
    /** 节点数硬上限。 */
    public static final int HARD_MAX_NODES = NodeLibrary.HARD_MAX_NODES;
    /** 单节点机器数硬上限。 */
    public static final int HARD_MAX_MACHINES_PER_NODE = NodeLibrary.HARD_MAX_MACHINES_PER_NODE;
    /** 全工厂机器数硬上限。 */
    public static final int HARD_MAX_TOTAL_MACHINES = NodeLibrary.HARD_MAX_TOTAL_MACHINES;
    /** 每侧规则数硬上限。 */
    public static final int HARD_MAX_RULES_PER_SIDE = NodeIo.MAX_RULES_PER_SIDE;
    /** 名字长度硬上限。 */
    public static final int HARD_MAX_NAME_LENGTH = Node.HARD_MAX_NAME_LENGTH;
    /** 健康过期阈值硬上限（tick）。 */
    public static final int HARD_MAX_STALE_HEALTH_TICKS = 2400;

    /** 观测三条通道的默认预算：每 tick 各 128 次。预算不设硬上限，只保留下限 1。 */
    public static final int DEFAULT_OBSERVATION_CHANNEL_BUDGET = 128;
    /** 健康过期默认阈值：1200 tick（约 60 秒）。 */
    public static final int DEFAULT_STALE_HEALTH_TICKS = 1200;

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** 节点数上限；-1 表示无上限。 */
    public static final ModConfigSpec.IntValue MAX_NODES = limited("maxNodes",
            "节点数上限；-1 表示无上限。 / Max node count per factory; -1 = unlimited.",
            HARD_MAX_NODES);
    /** 单节点机器数上限；-1 表示无上限。 */
    public static final ModConfigSpec.IntValue MAX_MACHINES_PER_NODE = limited("maxMachinesPerNode",
            "单节点机器数上限；-1 表示无上限。 / Max machines per node; -1 = unlimited.",
            HARD_MAX_MACHINES_PER_NODE);
    /** 全工厂机器数上限；-1 表示无上限。 */
    public static final ModConfigSpec.IntValue MAX_TOTAL_MACHINES = limited("maxTotalMachines",
            "全工厂机器数上限；-1 表示无上限。 / Max machines per factory; -1 = unlimited.",
            HARD_MAX_TOTAL_MACHINES);
    /** 每侧规则数上限；-1 表示无上限。 */
    public static final ModConfigSpec.IntValue MAX_RULES_PER_SIDE = limited("maxRulesPerSide",
            "输入或输出每侧规则数上限；-1 表示无上限。 / Max rules per side; -1 = unlimited.",
            HARD_MAX_RULES_PER_SIDE);
    /** 节点名字长度上限；-1 表示用硬上限。 */
    public static final ModConfigSpec.IntValue MAX_NAME_LENGTH = limited("maxNameLength",
            "节点名字长度上限；-1 表示用硬上限。 / Max node name length; -1 = hard cap.",
            HARD_MAX_NAME_LENGTH);

    /** 每 tick 健康探测预算；调优项，热改下一 tick 生效。 */
    public static final ModConfigSpec.IntValue OBSERVATION_HEALTH_BUDGET_PER_TICK = BUILDER
            .comment("每 tick 最多做多少次健康探测；调优项，热改下一 tick 生效，不设硬上限。 /",
                    "Health probes per tick; tuning knob, applies on the next tick.")
            .defineInRange("observationHealthBudgetPerTick",
                    DEFAULT_OBSERVATION_CHANNEL_BUDGET, 1, Integer.MAX_VALUE);
    /** 每 tick 工态探测预算；调优项，热改下一 tick 生效。 */
    public static final ModConfigSpec.IntValue OBSERVATION_WORK_BUDGET_PER_TICK = BUILDER
            .comment("每 tick 最多做多少次工作状态探测；调优项，不设硬上限。 /",
                    "Work-state probes per tick; tuning knob.")
            .defineInRange("observationWorkBudgetPerTick",
                    DEFAULT_OBSERVATION_CHANNEL_BUDGET, 1, Integer.MAX_VALUE);
    /** 每 tick 事件队列消化预算；调优项，热改下一 tick 生效。 */
    public static final ModConfigSpec.IntValue OBSERVATION_EVENT_BUDGET_PER_TICK = BUILDER
            .comment("每 tick 最多消化多少条事件标脏；调优项，不设硬上限。 /",
                    "Dirty events drained per tick; tuning knob.")
            .defineInRange("observationEventBudgetPerTick",
                    DEFAULT_OBSERVATION_CHANNEL_BUDGET, 1, Integer.MAX_VALUE);
    /** 健康过期阈值；调优项，热改下一 tick 生效。 */
    public static final ModConfigSpec.IntValue STALE_HEALTH_TICKS = BUILDER
            .comment("方块健康超过多少 tick 未探测就算过期；调优项。 /",
                    "Ticks without a probe before health is stale; tuning knob.")
            .defineInRange("staleHealthTicks", DEFAULT_STALE_HEALTH_TICKS, 1,
                    HARD_MAX_STALE_HEALTH_TICKS);

    /** COMMON 配置规格；由 {@code VoidFactory} 注册。 */
    public static final ModConfigSpec SPEC = BUILDER.build();

    private VoidFactoryConfig() {
        // 纯配置容器，禁止实例化
    }

    /**
     * 定义一个「-1 = 无上限」的整数项。
     *
     * @param name     配置键
     * @param comment  中英双语注释
     * @param hardCap  该项的硬上限
     * @return 配置项
     */
    private static ModConfigSpec.IntValue limited(String name, String comment, int hardCap) {
        return BUILDER.comment(comment)
                .defineInRange(name, -1, -1, hardCap);
    }

    /**
     * 把配置值折算成有效上限：负数用硬上限，正数取与硬上限的较小者。
     *
     * @param value   配置值
     * @param hardCap 硬上限
     * @return 有效上限
     */
    public static int effective(int value, int hardCap) {
        if (value < 0) {
            return hardCap;
        }
        return Math.min(value, hardCap);
    }

    /** @return 有效节点数上限 */
    public static int maxNodes() {
        return effective(MAX_NODES.get(), HARD_MAX_NODES);
    }

    /** @return 有效单节点机器数上限 */
    public static int maxMachinesPerNode() {
        return effective(MAX_MACHINES_PER_NODE.get(), HARD_MAX_MACHINES_PER_NODE);
    }

    /** @return 有效全工厂机器数上限 */
    public static int maxTotalMachines() {
        return effective(MAX_TOTAL_MACHINES.get(), HARD_MAX_TOTAL_MACHINES);
    }

    /** @return 有效每侧规则数上限 */
    public static int maxRulesPerSide() {
        return effective(MAX_RULES_PER_SIDE.get(), HARD_MAX_RULES_PER_SIDE);
    }

    /** @return 有效名字长度上限 */
    public static int maxNameLength() {
        return effective(MAX_NAME_LENGTH.get(), HARD_MAX_NAME_LENGTH);
    }

    /** @return 当前健康探测预算 */
    public static int observationHealthBudgetPerTick() {
        return OBSERVATION_HEALTH_BUDGET_PER_TICK.get();
    }

    /** @return 当前工态探测预算 */
    public static int observationWorkBudgetPerTick() {
        return OBSERVATION_WORK_BUDGET_PER_TICK.get();
    }

    /** @return 当前事件消化预算 */
    public static int observationEventBudgetPerTick() {
        return OBSERVATION_EVENT_BUDGET_PER_TICK.get();
    }

    /** @return 当前健康过期阈值 */
    public static int staleHealthTicks() {
        return STALE_HEALTH_TICKS.get();
    }
}
