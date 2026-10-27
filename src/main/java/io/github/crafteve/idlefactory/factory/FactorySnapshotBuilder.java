package io.github.crafteve.idlefactory.factory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.diag.DiagStats;
import io.github.crafteve.idlefactory.factory.io.IoResult;
import io.github.crafteve.idlefactory.factory.io.RuleKey;
import io.github.crafteve.idlefactory.factory.line.IoGroup;
import io.github.crafteve.idlefactory.factory.line.PassiveLine;
import io.github.crafteve.idlefactory.factory.machine.MachineRef;
import io.github.crafteve.idlefactory.factory.observation.Health;
import io.github.crafteve.idlefactory.factory.observation.Observation;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;
import io.github.crafteve.idlefactory.factory.observation.WorkState;
import io.github.crafteve.idlefactory.factory.resource.ResourceKind;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;
import io.github.crafteve.idlefactory.factory.storage.StorageAnchor;
import io.github.crafteve.idlefactory.factory.storage.StorageLink;

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
        StorageLink link = factory.storageLink();
        StorageAnchor anchor = link.anchor();
        String anchorText = anchor == null ? "unbound" : anchor.describe();
        return "工厂 " + factory.id()
                + " | owner=" + factory.owner()
                + " | state=" + factory.state()
                + " | storage=" + (link.online() ? "online" : "offline")
                + " | anchor=" + anchorText;
    }

    /**
     * 工厂摘要的结构化版本，留给以后的 S2C 包。
     *
     * @param id            工厂 id
     * @param owner         拥有者
     * @param state         状态
     * @param storageOnline 仓库是否在线
     * @param anchor        仓库地址
     */
    public record Summary(UUID id, UUID owner, FactoryState state,
            boolean storageOnline, @Nullable StorageAnchor anchor) {
    }

    /**
     * 生成结构化摘要。
     *
     * @param factory 工厂
     * @return 摘要
     */
    public static Summary build(Factory factory) {
        return new Summary(factory.id(), factory.owner(), factory.state(),
                factory.storageLink().online(), factory.storageLink().anchor());
    }

    /**
     * 一台机器在界面上的样子。
     *
     * @param ref       机器引用（坐标 + id）
     * @param detached  玩家是否已断开（回收站）
     * @param health    观测结论
     * @param blockId   观测到的方块 id；未知时 {@code null}
     * @param presence  无向能力
     * @param lineNames 挂在哪些产线上
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
     */
    public record MachineView(MachineRef ref, boolean detached, Health health,
            @Nullable ResourceLocation blockId, Set<ResourceKind> presence,
            List<String> lineNames, int ruleCount,
            List<ResourceSpec> inputs, List<ResourceSpec> outputs,
            List<Long> inputRates, List<Long> outputRates,
            long sinceMoveTicks, long blockedTicks,
            WorkState workState, boolean inputFull, long sinceInputMoveTicks,
            IoResult.Reason inputReason, IoResult.Reason outputReason) {

        public MachineView {
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
            inputRates = List.copyOf(inputRates);
            outputRates = List.copyOf(outputRates);
        }

        /** 是否在回收站里（断开 或 确实不在了）。 */
        public boolean binned() {
            return FactoryBin.binned(this.detached, this.health);
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
            if (binned() || this.health != Health.PRESENT) {
                return TotalState.OFFLINE;
            }
            if (this.lineNames.isEmpty() || this.ruleCount == 0) {
                return TotalState.UNCONFIGURED;
            }
            if (!this.inputs.isEmpty() && this.inputFull
                    && (this.sinceInputMoveTicks < 0L || this.sinceInputMoveTicks > 200L)) {
                return TotalState.INPUT_BLOCKED;
            }
            if (this.workState == WorkState.WORKING) {
                return TotalState.RUNNING;
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
     * @param total        机器总数（含回收站）
     * @param active       参与 IO 的数量（在、且没被断开）
     * @param unloaded     未加载
     * @param missing      失联（含维度不存在）
     * @param detached     玩家断开的数量
     * @param bin          回收站总量（断开 + 失联，去重）
     * @param lines        产线数
     * @param runningLines 运行中的产线数
     */
    public record FactoryHealth(int total, int active, int unloaded, int missing,
            int detached, int bin, int lines, int runningLines) {

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
            if (this.bin > 0) {
                builder.append(" · 回收站 ").append(this.bin);
            }
            return builder.toString();
        }
    }

    /**
     * 机器库视图（含回收站）。
     *
     * @param factory      工厂
     * @param observations 观测服务；{@code null} 时所有机器显示为未探测
     * @param now          当前服务端 tick
     * @return 视图列表，按登记顺序
     */
    public static List<MachineView> machines(Factory factory, @Nullable ObservationService observations,
            long now) {
        List<MachineView> views = new ArrayList<>(factory.machines().size());
        for (MachineRef ref : factory.machines().all()) {
            Observation observation = observations == null ? Observation.UNKNOWN : observations.observe(ref);
            Optional<IoGroup> group = FactoryRefs.groupOf(factory, ref.id());
            List<ResourceSpec> inputs = group.map(IoGroup::inputs).orElse(List.of());
            List<ResourceSpec> outputs = group.map(IoGroup::outputs).orElse(List.of());

            DiagStats.GroupStats stats = FactoryRefs.lineOf(factory, ref.id())
                    .map(line -> factory.stats().line(line.id()).groupOrNull(ref.id()))
                    .orElse(null);
            List<Long> inputRates = new ArrayList<>(inputs.size());
            for (ResourceSpec spec : inputs) {
                inputRates.add(stats == null ? 0L
                        : stats.rateIn(new RuleKey(true, spec.key(), spec.face())));
            }
            List<Long> outputRates = new ArrayList<>(outputs.size());
            for (ResourceSpec spec : outputs) {
                outputRates.add(stats == null ? 0L
                        : stats.rateOut(new RuleKey(false, spec.key(), spec.face())));
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

            views.add(new MachineView(ref,
                    factory.machines().isDetached(ref.id()),
                    observation.health(),
                    observation.blockId(),
                    observation.presence(),
                    FactoryRefs.lineNamesOf(factory, ref.id()),
                    inputs.size() + outputs.size(),
                    inputs, outputs, inputRates, outputRates,
                    sinceMove, blocked, observation.workState(), inputFull,
                    sinceInput, inputReason, outputReason));
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
     * 回收站视图（断开 或 失联）。
     *
     * @param factory      工厂
     * @param observations 观测服务
     * @return 视图列表
     */
    public static List<MachineView> bin(Factory factory, @Nullable ObservationService observations) {
        List<MachineView> entries = new ArrayList<>();
        for (MachineView view : machines(factory, observations)) {
            if (view.binned()) {
                entries.add(view);
            }
        }
        return entries;
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
        int detached = 0;
        int bin = 0;
        for (MachineView view : views) {
            if (view.detached()) {
                detached++;
            } else {
                switch (view.health()) {
                    case PRESENT -> active++;
                    case UNLOADED -> unloaded++;
                    case MISSING, NO_DIMENSION -> missing++;
                    default -> {
                        // UNKNOWN 不计入任何一档
                    }
                }
            }
            if (view.binned()) {
                bin++;
            }
        }
        int running = 0;
        for (PassiveLine line : factory.lines().all()) {
            if (line.running()) {
                running++;
            }
        }
        return new FactoryHealth(factory.machines().size(), active, unloaded, missing,
                detached, bin, factory.lines().size(), running);
    }

    /**
     * 一台机器的一行状态文本。
     *
     * @param view 视图
     * @return 状态文本
     */
    public static String describe(MachineView view) {
        if (!view.detached()) {
            return view.health().describe();
        }
        return view.health() == Health.PRESENT ? "已断开" : "已断开 · " + view.health().describe();
    }
}
