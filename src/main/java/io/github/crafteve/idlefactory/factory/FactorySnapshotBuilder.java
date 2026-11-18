package io.github.crafteve.idlefactory.factory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.config.IdleFactoryConfig;
import io.github.crafteve.idlefactory.view.MachineSnapshot;

import io.github.crafteve.idlefactory.io.IoReason;

import io.github.crafteve.idlefactory.io.IoAnalyzer;

import io.github.crafteve.idlefactory.io.IoForm;

import io.github.crafteve.idlefactory.io.IoSide;

import io.github.crafteve.idlefactory.io.PassivePlanner;

import io.github.crafteve.idlefactory.io.IoKey;

import io.github.crafteve.idlefactory.location.BlockKey;

import io.github.crafteve.idlefactory.machine.Machine;

import io.github.crafteve.idlefactory.machine.ResourceSpec;

import io.github.crafteve.idlefactory.node.Node;

import io.github.crafteve.idlefactory.node.NodeHealthResolver;

import io.github.crafteve.idlefactory.node.NodeIo;

import io.github.crafteve.idlefactory.observation.ObservationReader;
import io.github.crafteve.idlefactory.view.TotalState;

import io.github.crafteve.idlefactory.observation.BlockFacts;

import io.github.crafteve.idlefactory.machine.MachineHealth;

import io.github.crafteve.idlefactory.observation.WorkState;


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
 * <p><b>这里不许探世界</b>：所有状态都从 {@link ObservationReader} 的缓存里读。</p>
 */
public final class FactorySnapshotBuilder {
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
        BlockKey anchor = factory.config().storageAnchor();
        String anchorText = anchor == null ? "unbound"
                : anchor.dimension().location() + " " + anchor.pos().toShortString();
        return "工厂 " + factory.id()
                + " | owner=" + factory.config().owner()
                + " | storage=" + (factory.storageOnline() ? "online" : "offline")
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
     * <p>「为什么不动」不再问统计对象：原因与时刻来自表单条目的账本与历史结论，机器在不在
     * 来自观测事实，速率来自每秒采样的 {@link IoAnalyzer.Stats}。三份真相各读各的，不在视图里拼。</p>
     *
     * @param factory      工厂
     * @param observations 观测服务；{@code null} 时所有机器显示为未探测
     * @param form         本 tick 的表单；{@code null} 时没有执行数据
     * @param now          当前服务端 tick
     * @return 视图列表
     */
    public static List<MachineSnapshot> machines(Factory factory, @Nullable ObservationReader observations,
            @Nullable IoForm form, long now) {
        List<MachineSnapshot> views = new ArrayList<>(factory.config().nodes().totalMachineCount());
        long healthNow = observations == null ? 0L : observations.currentTick();
        long staleTicks = IdleFactoryConfig.staleHealthTicks();
        for (Node node : factory.config().nodes().all()) {
            NodeIo io = node.io();
            List<ResourceSpec> inputs = io.inputs();
            List<ResourceSpec> outputs = io.outputs();
            BlockFacts.Source facts = observations == null
                    ? key -> BlockFacts.UNKNOWN : observations;
            NodeHealthResolver.Snapshot nodeHealth = NodeHealthResolver.resolve(node,
                    facts, healthNow, staleTicks);
            for (Machine machine : node.machines()) {
                BlockKey typeBlock = machine.block();
                BlockFacts observation = observations == null
                        ? BlockFacts.UNKNOWN : observations.at(typeBlock);
                NodeHealthResolver.MachineState state = nodeHealth.machine(machine.id());
                MachineHealth health = state == null ? MachineHealth.OFFLINE : state.health();
                boolean probed = state != null && state.probed();
                boolean stale = state != null && state.stale();

                IoAnalyzer.Stats.MachineStats stats = factory.analyzer().stats().machineOrNull(machine.id());
                IoForm.Slot slot = form == null ? null : form.slotOrNull(machine.id());
                List<Long> inputRates = new ArrayList<>(inputs.size());
                for (ResourceSpec spec : inputs) {
                    inputRates.add(stats == null ? 0L
                            : stats.rateIn(new IoKey(IoSide.INPUT, spec.key(), spec.face(), spec.mode())));
                }
                List<Long> outputRates = new ArrayList<>(outputs.size());
                for (ResourceSpec spec : outputs) {
                    outputRates.add(stats == null ? 0L
                            : stats.rateOut(new IoKey(IoSide.OUTPUT, spec.key(), spec.face(), spec.mode())));
                }
                List<IoReason> inputReasons = new ArrayList<>(inputs.size());
                long inputSuccessAt = -1L;
                long blockedSince = -1L;
                for (ResourceSpec spec : inputs) {
                    IoForm.Entry unit = slot == null ? null
                            : slot.entry(new IoKey(IoSide.INPUT, spec.key(), spec.face(), spec.mode()));
                    IoReason reason = unit == null
                            ? IoReason.NO_MATERIAL : PassivePlanner.displayReason(unit, now);
                    inputReasons.add(reason);
                    if (unit != null) {
                        inputSuccessAt = Math.max(inputSuccessAt, unit.conclusion().lastSuccessAt());
                        if (reason != IoReason.RUN && reason != IoReason.SLEEPING) {
                            long since = unit.conclusion().lastAttemptAt();
                            if (since >= 0L) {
                                blockedSince = blockedSince < 0L ? since : Math.min(blockedSince, since);
                            }
                        }
                    }
                }
                List<IoReason> outputReasons = new ArrayList<>(outputs.size());
                long lastSuccessAt = inputSuccessAt;
                long minIdle = Long.MAX_VALUE;
                for (ResourceSpec spec : outputs) {
                    IoForm.Entry unit = slot == null ? null
                            : slot.entry(new IoKey(IoSide.OUTPUT, spec.key(), spec.face(), spec.mode()));
                    IoReason reason = unit == null
                            ? IoReason.NO_MATERIAL : PassivePlanner.displayReason(unit, now);
                    outputReasons.add(reason);
                    if (unit != null) {
                        lastSuccessAt = Math.max(lastSuccessAt, unit.conclusion().lastSuccessAt());
                        if (unit.conclusion().lastAttemptAt() >= 0L) {
                            minIdle = Math.min(minIdle, unit.conclusion().idleTicks(now));
                        }
                        if (reason != IoReason.RUN && reason != IoReason.SLEEPING) {
                            long since = unit.conclusion().lastAttemptAt();
                            if (since >= 0L) {
                                blockedSince = blockedSince < 0L ? since : Math.min(blockedSince, since);
                            }
                        }
                    }
                }

                long sinceMove;
                if (!outputs.isEmpty() && minIdle != Long.MAX_VALUE) {
                    sinceMove = minIdle;
                } else {
                    sinceMove = lastSuccessAt < 0L ? -1L : Math.max(0L, now - lastSuccessAt);
                }
                long blocked = blockedSince < 0L ? -1L : Math.max(0L, now - blockedSince);
                long sinceInput = inputSuccessAt < 0L ? -1L : Math.max(0L, now - inputSuccessAt);
                boolean inputFull = !inputReasons.isEmpty() && inputReasons.stream()
                        .allMatch(reason -> reason == IoReason.FULL || reason == IoReason.REJECTED
                                || reason == IoReason.NO_FACE);
                IoReason inputReason = worst(inputReasons);
                IoReason outputReason = worst(outputReasons);

                // groupBlockId 在未加载时回退到 lastBlockId：分组只看「它本来是什么」。
                ResourceLocation groupBlockId = observation.blockId() != null
                        ? observation.blockId()
                        : (observations == null ? null : observations.lastBlockId(typeBlock));
                WorkState workState = observations == null
                        ? WorkState.UNKNOWN : observations.workState(typeBlock);
                views.add(new MachineSnapshot(node.id(), node.name(), node.typeBlockId(),
                        node.machineCount(), machine.id(), typeBlock,
                        health, probed, stale,
                        observation.blockId(), groupBlockId, observation.beTypeId(),
                        inputs.size() + outputs.size(),
                        inputs, outputs, inputRates, outputRates,
                        sinceMove, blocked, workState, inputFull,
                        sinceInput, inputReason, outputReason,
                        inputReasons, outputReasons));
            }
        }
        return views;
    }

    /**
     * 一列原因里最需要注意的那个（按拒绝 &gt; 无面 &gt; 满 &gt; 缺料 &gt; 其它）。
     *
     * @param reasons 原因列表
     * @return 最坏原因；空列表按正常
     */
    private static IoReason worst(List<IoReason> reasons) {
        IoReason worst = IoReason.RUN;
        int severity = -1;
        for (IoReason reason : reasons) {
            if (reason.severity() > severity) {
                severity = reason.severity();
                worst = reason;
            }
        }
        return worst;
    }

    /**
     * 兼容旧调用：不提供当前 tick，速率/时间字段为空。
     *
     * @param factory      工厂
     * @param observations 观测服务
     * @return 视图列表
     */
    public static List<MachineSnapshot> machines(Factory factory,
            @Nullable ObservationReader observations) {
        return machines(factory, observations, null, 0L);
    }

    /**
     * 汇总健康度。
     *
     * @param factory      工厂
     * @param observations 观测服务；{@code null} 时全部算未探测
     * @return 汇总
     */
    public static FactoryHealth health(Factory factory, @Nullable ObservationReader observations) {
        return health(factory, machines(factory, observations));
    }

    /**
     * 汇总健康度（视图已经算好时用这个，省一次遍历）。
     *
     * @param factory 工厂
     * @param views   机器视图
     * @return 汇总
     */
    public static FactoryHealth health(Factory factory, List<MachineSnapshot> views) {
        int active = 0;
        for (MachineSnapshot view : views) {
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
    public static String describe(MachineSnapshot view) {
        return view.health().describe();
    }
}
