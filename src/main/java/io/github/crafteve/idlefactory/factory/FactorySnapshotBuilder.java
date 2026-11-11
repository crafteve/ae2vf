package io.github.crafteve.idlefactory.factory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.capability.CapabilityKind;
import io.github.crafteve.idlefactory.factory.io.Reason;
import io.github.crafteve.idlefactory.factory.io.DiagStats;
import io.github.crafteve.idlefactory.factory.io.IoResult;
import io.github.crafteve.idlefactory.factory.io.RuleKey;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.machine.ResourceSpec;
import io.github.crafteve.idlefactory.factory.node.Node;
import io.github.crafteve.idlefactory.factory.node.NodeHealthResolver;
import io.github.crafteve.idlefactory.factory.node.NodeIoGroup;
import io.github.crafteve.idlefactory.factory.observation.Observation;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;
import io.github.crafteve.idlefactory.factory.probe.BlockFacts;
import io.github.crafteve.idlefactory.factory.probe.MachineHealth;
import io.github.crafteve.idlefactory.factory.probe.WorkState;
import io.github.crafteve.idlefactory.factory.storage.StorageAnchor;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * 把工厂当前状态整理成「前端能直接用的东西」：配置（L0）+ 观测（L1）→ 视图（L3）。
 *
 * <p>遍历顺序是节点 → 机器：一个节点下的每台机器摊平成一行，规则从节点读。视图带节点归属，
 * v3 机器表用 {@code nodeId} 归组、用 {@code nodeMachineCount} 画机器数。</p>
 *
 * <p><b>这里不许探世界</b>：所有状态都从 {@link ObservationService} 的缓存里读。</p>
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
     * <p>机器视图带节点归属：{@code nodeId} / {@code nodeName} / {@code nodeIconBlockId} /
     * {@code nodeMachineCount} 供 v3 按节点分组，{@code machineId} 与 {@code primaryBlock}
     * 定位这台机器。健康只有 {@link MachineHealth} 两个值，{@code probed} 表示是否探测过，
     * {@code stale} 表示探过但过期；「方块没了」不再单独开字段，由健康表达。</p>
     *
     * @param nodeId         所属节点 id
     * @param nodeName       节点名字；{@code null} 表示用方块名
     * @param nodeIconBlockId 节点类型方块 id，节点卡片图标用
     * @param nodeMachineCount 节点机器数
     * @param machineId      机器 id
     * @param primaryBlock   类型 / 展示方块（维度 + 坐标）
     * @param health         机器健康
     * @param probed         是否探测过
     * @param stale          健康是否过期
     * @param blockId        观测到的方块 id；未知时 {@code null}
     * @param groupBlockId   分组用的方块 id：{@code blockId} 为空时回退到「最后一次看到的方块」
     * @param beTypeId       方块实体类型 id；没有方块实体时为 {@code null}
     * @param presence       无向能力
     * @param ruleCount      节点配了几条规则
     * @param inputs         输入规则（界面明细用）
     * @param outputs        输出规则（界面明细用）
     * @param inputRates     与 inputs 顺序一致的规则速率
     * @param outputRates    与 outputs 顺序一致的规则速率
     * @param sinceMoveTicks 距上次成功搬运的 tick；-1 表示从没搬过
     * @param blockedTicks   连续阻塞的 tick；-1 表示当前没卡
     * @param workState      观测的工作状态
     * @param inputFull      所有输入规则是否都无法接收
     * @param sinceInputMoveTicks 距上次成功输入的 tick；-1 表示从没输入
     * @param inputReason    输入侧原因
     * @param outputReason   输出侧原因
     * @param inputReasons   与 inputs 顺序一致的逐规则原因
     * @param outputReasons  与 outputs 顺序一致的逐规则原因
     */
    public record MachineView(UUID nodeId, @Nullable String nodeName,
            @Nullable ResourceLocation nodeIconBlockId, int nodeMachineCount,
            UUID machineId, BlockKey primaryBlock,
            MachineHealth health, boolean probed, boolean stale,
            @Nullable ResourceLocation blockId, @Nullable ResourceLocation groupBlockId,
            @Nullable ResourceLocation beTypeId, Set<CapabilityKind> presence, int ruleCount,
            List<ResourceSpec> inputs, List<ResourceSpec> outputs,
            List<Long> inputRates, List<Long> outputRates,
            long sinceMoveTicks, long blockedTicks,
            WorkState workState, boolean inputFull, long sinceInputMoveTicks,
            Reason inputReason, Reason outputReason,
            List<Reason> inputReasons, List<Reason> outputReasons) {

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
         * <p>旧快照或服务端尚未产生本轮结果时列表可能偏短；补 {@link Reason#NO_MATERIAL}
         * 而不是 {@code OK}，界面才不会把没跑过的规则画成「正常」。</p>
         */
        private static List<Reason> alignReasons(List<Reason> reasons, int size) {
            List<Reason> aligned = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                aligned.add(i < reasons.size() ? reasons.get(i) : Reason.NO_MATERIAL);
            }
            return List.copyOf(aligned);
        }

        /** 机器所在维度。 */
        public ResourceKey<Level> dimension() {
            return this.primaryBlock.dimension();
        }

        /** 机器类型方块坐标。 */
        public BlockPos pos() {
            return this.primaryBlock.pos();
        }

        /** 是否在线。 */
        public boolean online() {
            return this.health.isOnline();
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
         * View 层的总状态：健康优先，UNKNOWN 时用 IO 输入能力回退。
         *
         * @return 总状态
         */
        public TotalState totalState() {
            if (!this.health.isOnline() || this.stale) {
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
                    && this.inputReason == Reason.NO_MATERIAL
                    && this.outputReason != Reason.FULL
                    && this.outputReason != Reason.REJECTED) {
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
     * @param total   机器总数
     * @param active  在线机器数
     * @param offline 离线机器数
     */
    public record FactoryHealth(int total, int active, int offline) {

        /**
         * 一行摘要，给 {@code info} / {@code diag} 的头部用。
         *
         * @return 摘要文本
         */
        public String describe() {
            StringBuilder builder = new StringBuilder();
            builder.append("机器 ").append(this.total).append(" 台 · 在线 ").append(this.active);
            if (this.offline > 0) {
                builder.append(" · 离线 ").append(this.offline);
            }
            return builder.toString();
        }
    }

    /**
     * 机器视图列表：节点 → 机器摊平，顺序沿用节点库与机器加入顺序。
     *
     * @param factory      工厂
     * @param observations 观测服务；{@code null} 时所有机器显示为未探测
     * @param now          当前服务端 tick
     * @return 视图列表
     */
    public static List<MachineView> machines(Factory factory, @Nullable ObservationService observations,
            long now) {
        List<MachineView> views = new ArrayList<>(factory.config().nodes().totalMachineCount());
        long healthNow = observations == null ? 0L : observations.currentTick();
        long staleTicks = FactoryLimitsConfig.staleHealthTicks();
        for (Node node : factory.config().nodes().all()) {
            NodeIoGroup io = node.io();
            List<ResourceSpec> inputs = io.inputs();
            List<ResourceSpec> outputs = io.outputs();
            BlockFacts.Source facts = observations == null
                    ? key -> Observation.UNKNOWN : observations;
            NodeHealthResolver.Snapshot nodeHealth = NodeHealthResolver.resolve(node,
                    facts, healthNow, staleTicks);
            for (Machine machine : node.machines()) {
                BlockKey typeBlock = machine.block();
                Observation observation = observations == null
                        ? Observation.UNKNOWN : observations.observe(typeBlock);
                NodeHealthResolver.MachineState state = nodeHealth.machine(machine.id());
                MachineHealth health = state == null ? MachineHealth.OFFLINE : state.health();
                boolean probed = state != null && state.probed();
                boolean stale = state != null && state.stale();

                DiagStats.MachineStats stats = factory.live().stats().machineOrNull(machine.id());
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
                List<Reason> inputReasons = new ArrayList<>(inputs.size());
                for (ResourceSpec spec : inputs) {
                    inputReasons.add(stats == null ? Reason.NO_MATERIAL
                            : stats.ruleReason(true,
                                    new RuleKey(true, spec.key(), spec.face(), spec.mode())));
                }
                List<Reason> outputReasons = new ArrayList<>(outputs.size());
                for (ResourceSpec spec : outputs) {
                    outputReasons.add(stats == null ? Reason.NO_MATERIAL
                            : stats.ruleReason(false,
                                    new RuleKey(false, spec.key(), spec.face(), spec.mode())));
                }

                long sinceMove = stats != null && stats.lastMoveTick() > 0L
                        ? Math.max(0L, now - stats.lastMoveTick()) : -1L;
                long blocked = stats != null && stats.blockedSince() >= 0L
                        ? Math.max(0L, now - stats.blockedSince()) : -1L;
                long sinceInput = stats != null && stats.lastInputMoveTick() > 0L
                        ? Math.max(0L, now - stats.lastInputMoveTick()) : -1L;
                boolean inputFull = stats != null && stats.inputFull();
                Reason inputReason = stats == null
                        ? Reason.RUN : stats.inputReason();
                Reason outputReason = stats == null
                        ? Reason.RUN : stats.outputReason();

                // groupBlockId 在未加载时回退到 lastBlockId：分组只看「它本来是什么」。
                ResourceLocation groupBlockId = observation.blockId() != null
                        ? observation.blockId() : observation.lastBlockId();
                views.add(new MachineView(node.id(), node.name(), node.typeBlockId(),
                        node.machineCount(), machine.id(), typeBlock,
                        health, probed, stale,
                        observation.blockId(), groupBlockId, observation.beTypeId(),
                        observation.presence(), inputs.size() + outputs.size(),
                        inputs, outputs, inputRates, outputRates,
                        sinceMove, blocked, observation.workState(), inputFull,
                        sinceInput, inputReason, outputReason,
                        inputReasons, outputReasons));
            }
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
        for (MachineView view : views) {
            if (view.health().isOnline()) {
                active++;
            }
        }
        return new FactoryHealth(factory.config().nodes().totalMachineCount(), active,
                views.size() - active);
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
