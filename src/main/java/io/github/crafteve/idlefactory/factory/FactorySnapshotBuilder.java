package io.github.crafteve.idlefactory.factory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.capability.CapabilityKind;
import io.github.crafteve.idlefactory.factory.io.DiagStats;
import io.github.crafteve.idlefactory.factory.io.IoResult;
import io.github.crafteve.idlefactory.factory.io.RuleKey;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.machine.MachineIoGroup;
import io.github.crafteve.idlefactory.factory.machine.MachineRef;
import io.github.crafteve.idlefactory.factory.probe.Health;
import io.github.crafteve.idlefactory.factory.observation.Observation;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;
import io.github.crafteve.idlefactory.factory.probe.WorkState;
import io.github.crafteve.idlefactory.factory.capability.CapabilityKind;
import io.github.crafteve.idlefactory.factory.machine.ResourceSpec;
import io.github.crafteve.idlefactory.factory.storage.StorageAnchor;

import net.minecraft.resources.ResourceLocation;

/**
 * 把工厂当前状态整理成「前端能直接用的东西」：配置（L0）+ 观测（L1）→ 视图（L3）。
 *
 * <p>它不依赖菜单、不依赖会话，谁要摘要都找它。第一版只产出一行可读文本与几个 record，
 * 等 S2C 快照包稳定后再换成 DTO。</p>
 *
 * <p><b>这里不许探世界</b>：所有状态都从 {@link ObservationService} 的缓存里读。之所以能做到，
 * 是因为观测服务已经在 tick 里把该看的都看过了。</p>
 */
public final class FactorySnapshotBuilder {
    /** 界面上的总状态，由 WS + IO 推导。 */
    public enum TotalState {
        RUNNING,
        IDLE,
        WAITING_MATERIAL,
        INPUT_OK,
        INPUT_BLOCKED,
        OFFLINE,
        UNCONFIGURED,
        UNKNOWN
    }

    private FactorySnapshotBuilder() {
        // 纯静态工具类
    }

    /**
     * 生成一行人类可读的工厂摘要。
     *
     * @param factory 工厂
     * @return 摘要文本
     */
    public static String describe(Factory factory) {
        StorageAnchor anchor = factory.config().storageAnchor();
        String anchorText = anchor == null ? "unbound" : anchor.describe();
        return "工厂 " + factory.id()
                + " | owner=" + factory.config().owner()
                + " | storage=" + (factory.live().storageOnline() ? "online" : "offline")
                + " | anchor=" + anchorText;
    }

    /**
     * 一台机器在界面上的样子。
     *
     * <p>P1 删除产线后视图里不再有「挂在哪些产线」「第几组」这类字段：机器就是工厂机器库里
     * 的一台，界面按登记顺序铺开。</p>
     *
     * @param ref       机器引用（坐标 + id）
     * @param machineMissing 方块是否已经消失（观测事实）
     * @param health    观测结论
     * @param blockId   观测到的方块 id；未知时 {@code null}
     * @param groupBlockId 分组用的方块 id：{@code blockId} 为空时回退到「最后一次看到的方块」，
     *                  让区块卸载不会把界面上的组拆散
     * @param beTypeId  方块实体类型 id；没有方块实体时为 {@code null}
     * @param presence  无向能力
     * @param ruleCount 配了几条规则
     * @param inputs    输入规则（界面明细用）
     * @param outputs   输出规则（界面明细用）
     * @param inputRates  与 inputs 顺序一致的规则速率（数量/秒）
     * @param outputRates 与 outputs 顺序一致的规则速率（数量/秒）
     * @param sinceMoveTicks 距上次成功搬运的 tick；-1 表示从没搬过
     * @param blockedTicks   连续阻塞的 tick；-1 表示当前没卡
     * @param workState      Observation 的工作状态
     * @param inputFull      所有输入规则是否都无法接收
     * @param sinceInputMoveTicks 距上次成功输入的 tick；-1 表示从没输入
     * @param inputReason    输入侧原因
     * @param outputReason   输出侧原因
     * @param inputReasons   与 inputs 顺序一致的逐规则原因
     * @param outputReasons  与 outputs 顺序一致的逐规则原因
     */
    public record MachineView(MachineRef ref, boolean machineMissing, Health health,
            @Nullable ResourceLocation blockId, @Nullable ResourceLocation groupBlockId,
            @Nullable ResourceLocation beTypeId, Set<CapabilityKind> presence, int ruleCount,
            List<ResourceSpec> inputs, List<ResourceSpec> outputs,
            List<Long> inputRates, List<Long> outputRates,
            long sinceMoveTicks, long blockedTicks,
            WorkState workState, boolean inputFull, long sinceInputMoveTicks,
            IoResult.Reason inputReason, IoResult.Reason outputReason,
            List<IoResult.Reason> inputReasons, List<IoResult.Reason> outputReasons) {

        public MachineView {
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
            inputRates = List.copyOf(inputRates);
            outputRates = List.copyOf(outputRates);
            inputReasons = alignReasons(inputReasons, inputs.size());
            outputReasons = alignReasons(outputReasons, outputs.size());
        }

        /**
         * 把原因列表补齐到规则条数。
         *
         * <p>旧快照或服务端尚未产生本轮结果时列表可能偏短；补 {@link IoResult.Reason#NO_ITEM}
         * 而不是 {@code OK}，界面才不会把没跑过的规则画成「正常」。</p>
         */
        private static List<IoResult.Reason> alignReasons(List<IoResult.Reason> reasons, int size) {
            List<IoResult.Reason> aligned = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                aligned.add(i < reasons.size() ? reasons.get(i) : IoResult.Reason.NO_ITEM);
            }
            return List.copyOf(aligned);
        }

        /** 输入侧总速率。 */
        public long rateIn() {
            long total = 0L;
            for (long rate : this.inputRates) {
                total += rate;
            }
            return total;
        }

        /** 输出侧总速率。 */
        public long rateOut() {
            long total = 0L;
            for (long rate : this.outputRates) {
                total += rate;
            }
            return total;
        }

        /** 两侧总速率。 */
        public long totalRate() {
            return rateIn() + rateOut();
        }

        /**
         * 取一条规则的速率。
         *
         * @param input 是否输入
         * @param index 规则下标
         * @return 数量/秒；越界时为 0
         */
        public long ruleRate(boolean input, int index) {
            List<Long> rates = input ? this.inputRates : this.outputRates;
            return index >= 0 && index < rates.size() ? rates.get(index) : 0L;
        }

        /**
         * View 层的总状态：WS 优先，UNKNOWN 时用 IO 输入能力回退。
         *
         * @return 总状态
         */
        public TotalState totalState() {
            if (this.machineMissing || this.health != Health.PRESENT) {
                return TotalState.OFFLINE;
            }
            if (this.ruleCount == 0) {
                return TotalState.UNCONFIGURED;
            }
            if (!this.inputs.isEmpty() && this.inputFull
                    && (this.sinceInputMoveTicks < 0L || this.sinceInputMoveTicks > 200L)) {
                return TotalState.INPUT_BLOCKED;
            }
            if (this.workState == WorkState.WORKING) {
                return TotalState.RUNNING;
            }
            // 缺料是「等仓库」，不是「机器不收」；输出满了不算缺料，避免误报。
            if (!this.inputs.isEmpty()
                    && this.inputReason == IoResult.Reason.NO_ITEM
                    && this.outputReason != IoResult.Reason.NO_SPACE
                    && this.outputReason != IoResult.Reason.REJECTED) {
                return TotalState.WAITING_MATERIAL;
            }
            if (this.workState == WorkState.IDLE) {
                return TotalState.IDLE;
            }
            if (this.inputs.isEmpty()) {
                return TotalState.UNKNOWN;
            }
            return TotalState.INPUT_OK;
        }
    }

    /**
     * 工厂健康度汇总。
     *
     * @param total        机器总数
     * @param active       参与 IO 的数量（方块在、未失联）
     * @param unloaded     未加载
     * @param missing      失联（含维度不存在）
     */
    public record FactoryHealth(int total, int active, int unloaded, int missing) {

        /**
         * 一行摘要，给 {@code info} / {@code diag} 的头部用。
         *
         * @return 摘要文本
         */
        public String describe() {
            StringBuilder builder = new StringBuilder();
            builder.append("机器 ").append(this.total).append(" 台 · 活跃 ").append(this.active);
            if (this.unloaded > 0) {
                builder.append(" · 未加载 ").append(this.unloaded);
            }
            if (this.missing > 0) {
                builder.append(" · 失联 ").append(this.missing);
            }
            return builder.toString();
        }
    }

    /**
     * 机器库视图。
     *
     * @param factory      工厂
     * @param observations 观测服务；{@code null} 时所有机器显示为未探测
     * @param now          当前服务端 tick
     * @return 视图列表，按登记顺序
     */
    public static List<MachineView> machines(Factory factory, @Nullable ObservationService observations,
            long now) {
        List<MachineView> views = new ArrayList<>(factory.config().machines().size());
        for (Machine machine : factory.config().machines().all()) {
            MachineRef ref = machine.ref();
            Observation observation = observations == null
                    ? Observation.UNKNOWN : observations.observe(ref.blockKey());
            MachineIoGroup io = machine.io();
            List<ResourceSpec> inputs = io.inputs();
            List<ResourceSpec> outputs = io.outputs();

            DiagStats.MachineStats stats = factory.live().stats().machineOrNull(ref.id());
            List<Long> inputRates = new ArrayList<>(inputs.size());
            for (ResourceSpec spec : inputs) {
                inputRates.add(stats == null ? 0L
                        : stats.rateIn(new RuleKey(true, spec.key(), spec.face(), spec.mode())));
            }
            List<Long> outputRates = new ArrayList<>(outputs.size());
            for (ResourceSpec spec : outputs) {
                outputRates.add(stats == null ? 0L
                        : stats.rateOut(new RuleKey(false, spec.key(), spec.face(), spec.mode())));
            }
            List<IoResult.Reason> inputReasons = new ArrayList<>(inputs.size());
            for (ResourceSpec spec : inputs) {
                inputReasons.add(stats == null ? IoResult.Reason.NO_ITEM
                        : stats.ruleReason(true, new RuleKey(true, spec.key(), spec.face(), spec.mode())));
            }
            List<IoResult.Reason> outputReasons = new ArrayList<>(outputs.size());
            for (ResourceSpec spec : outputs) {
                outputReasons.add(stats == null ? IoResult.Reason.NO_ITEM
                        : stats.ruleReason(false, new RuleKey(false, spec.key(), spec.face(), spec.mode())));
            }

            long sinceMove = stats != null && stats.lastMoveTick() > 0L
                    ? Math.max(0L, now - stats.lastMoveTick()) : -1L;
            long blocked = stats != null && stats.blockedSince() >= 0L
                    ? Math.max(0L, now - stats.blockedSince()) : -1L;
            long sinceInput = stats != null && stats.lastInputMoveTick() > 0L
                    ? Math.max(0L, now - stats.lastInputMoveTick()) : -1L;
            boolean inputFull = stats != null && stats.inputFull();
            IoResult.Reason inputReason = stats == null
                    ? IoResult.Reason.OK : stats.inputReason();
            IoResult.Reason outputReason = stats == null
                    ? IoResult.Reason.OK : stats.outputReason();

            // groupBlockId 在未加载时回退到 lastBlockId：分组只看「它本来是什么」，
            // 不做可用性判断；可用性仍然读 health。
            ResourceLocation groupBlockId = observation.blockId() != null
                    ? observation.blockId() : observation.lastBlockId();
            views.add(new MachineView(ref,
                    factory.live().isMachineMissing(ref.id()),
                    observation.health(),
                    observation.blockId(),
                    groupBlockId,
                    observation.beTypeId(),
                    observation.presence(),
                    inputs.size() + outputs.size(),
                    inputs, outputs, inputRates, outputRates,
                    sinceMove, blocked, observation.workState(), inputFull,
                    sinceInput, inputReason, outputReason,
                    inputReasons, outputReasons));
        }
        return views;
    }

    /**
     * 兼容旧调用：不提供当前 tick，速率/时间字段为空。
     *
     * @param factory      工厂
     * @param observations 观测服务
     * @return 视图列表
     */
    public static List<MachineView> machines(Factory factory,
            @Nullable ObservationService observations) {
        return machines(factory, observations, 0L);
    }

    /**
     * 汇总健康度。
     *
     * @param factory      工厂
     * @param observations 观测服务；{@code null} 时全部算未探测
     * @return 汇总
     */
    public static FactoryHealth health(Factory factory, @Nullable ObservationService observations) {
        return health(factory, machines(factory, observations));
    }

    /**
     * 汇总健康度（视图已经算好时用这个，省一次遍历）。
     *
     * @param factory 工厂
     * @param views   机器视图
     * @return 汇总
     */
    public static FactoryHealth health(Factory factory, List<MachineView> views) {
        int active = 0;
        int unloaded = 0;
        int missing = 0;
        for (MachineView view : views) {
            switch (view.health()) {
                case PRESENT -> {
                    if (view.machineMissing()) {
                        missing++;
                    } else {
                        active++;
                    }
                }
                case UNLOADED -> unloaded++;
                case MISSING, NO_DIMENSION -> missing++;
                default -> {
                    // UNKNOWN 不计入任何一档
                }
            }
        }
        int running = 0;
        return new FactoryHealth(factory.config().machines().size(), active, unloaded, missing);
    }

    /**
     * 一台机器的一行状态文本。
     *
     * @param view 视图
     * @return 状态文本
     */
    public static String describe(MachineView view) {
        return view.health().describe();
    }
}
