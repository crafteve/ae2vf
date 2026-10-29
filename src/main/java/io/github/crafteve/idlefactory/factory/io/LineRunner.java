package io.github.crafteve.idlefactory.factory.io;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryManager;
import io.github.crafteve.idlefactory.factory.line.PassiveLine;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.machine.MachineRef;
import io.github.crafteve.idlefactory.factory.capability.LevelCapabilityCache;
import io.github.crafteve.idlefactory.factory.observation.Observation;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;
import io.github.crafteve.idlefactory.factory.storage.Storage;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 被动产线执行器：按线 → 组 → 机器的顺序跑 IO。
 *
 * <p>带两级睡眠：组连续几轮没搬动就睡，整条线的组都睡就线睡。睡眠中的单位只等定时唤醒，
 * 不参与每轮遍历。{@code start} / {@code end} / 配置变更会手动唤醒。</p>
 *
 * <p><b>门禁读观测，不读世界</b>：一个组只有「没被玩家断开」且「观测结论是 PRESENT」才会进
 * {@link GroupRunner}。回收站里的、失联的、未加载的组一律直接睡，零世界查询。</p>
 */
public final class LineRunner {
    private final RunnerState state = new RunnerState();
    /** 模糊物品规则的变体索引；按工厂隔离，TTL 到期重建。 */
    private final Map<UUID, ItemVariantIndex> variants = new HashMap<>();

    /**
     * 跑一轮所有运行中的产线。
     *
     * @param server       服务端
     * @param manager      工厂档案
     * @param observations 观测服务；为 {@code null} 时退化成「不做健康门禁」
     */
    public void tick(MinecraftServer server, FactoryManager manager,
            @Nullable ObservationService observations) {
        long now = server.overworld().getGameTime();
        for (Factory factory : manager.all()) {
            if (!factory.live().storageOnline()) {
                continue;
            }
            Storage storage = factory.live().storage();
            for (PassiveLine line : factory.config().lines().all()) {
                if (!line.running()) {
                    continue;
                }
                tickLine(server, factory, storage, line, now, observations);
            }
        }
    }

    private void tickLine(MinecraftServer server, Factory factory, Storage storage,
            PassiveLine line, long now, @Nullable ObservationService observations) {
        RunnerState.LineState lineState = this.state.line(line.id());
        if (lineState.sleeping && now < lineState.wakeAt) {
            return;
        }

        boolean anyAwake = false;
        long earliestWake = Long.MAX_VALUE;

        for (UUID machineId : line.machineIds()) {
            RunnerState.GroupState groupState = lineState.group(machineId);
            if (groupState.sleeping) {
                if (now < groupState.wakeAt) {
                    earliestWake = Math.min(earliestWake, groupState.wakeAt);
                    continue;
                }
                groupState.sleeping = false;
                groupState.idleSince = -1L;
            }
            anyAwake = true;

            Machine machine = factory.config().machines().byId(machineId).orElse(null);
            if (machine == null) {
                sleepIfIdle(groupState, now);
                continue;
            }
            MachineRef target = machine.ref();
            if (factory.config().machines().isRetired(target.id())) {
                // 回收站里的机器不参与 IO；restore 时会显式唤醒
                sleepIfIdle(groupState, now);
                continue;
            }
            if (observations != null && !runnable(observations, target)) {
                sleepIfIdle(groupState, now);
                continue;
            }

            ServerLevel level = server.getLevel(target.dimension());
            if (level == null || !level.isLoaded(target.pos())) {
                // 未加载不代表机器没了，保持醒着，下一轮再看
                continue;
            }

            LevelCapabilityCache caps = observations == null ? null : observations.capabilities(level);
            long generation = observations == null ? 0L : observations.facesGeneration(target.blockKey());
            ItemVariantIndex factoryVariants = this.variants.computeIfAbsent(factory.id(),
                    id -> new ItemVariantIndex());
            GroupRunner.GroupRun result = GroupRunner.run(storage, level, target.pos(), machine.io(), caps,
                    groupState.routes, generation, factoryVariants, now);
            // 熔断器喂食：只有面级拒收（REJECTED）才记——面不对和机器没了是两回事，
            // 这里进去的只刷面表，绝不动健康；其他任何结果（搬通了、没料、满了）
            // 都算“路没问题”，清掉连续计数。详见 noteRejected。
            if (observations != null) {
                BlockKey key = BlockKey.of(target.dimension(), target.pos());
                if (result.inputReason() == IoResult.Reason.REJECTED
                        || result.outputReason() == IoResult.Reason.REJECTED) {
                    observations.noteRejected(key);
                } else {
                    observations.noteSuccess(key);
                }
            }
            DiagStats.LineStats stats = factory.live().stats().line(line.id());
            // 逐规则原因独立于组级搬运结果：部分规则没搬动时也要让界面看到具体哪条卡住。
            stats.group(target.id()).setRuleReasons(result.inputReasons(), result.outputReasons());
            if (result.movedAnything()) {
                stats.addMoved(target.id(), result.fedByKey(), result.pulledByKey(),
                        result.fedByRule(), result.pulledByRule(), now);
                groupState.idleSince = -1L;
                groupState.sleeping = false;
            } else {
                stats.markBlocked(target.id(), result.inputReason(), result.outputReason(),
                        result.inputFull(), now);
                sleepIfIdle(groupState, now);
            }
        }

        if (!anyAwake) {
            lineState.sleeping = true;
            lineState.wakeAt = earliestWake == Long.MAX_VALUE
                    ? now + RunnerState.WAKE_INTERVAL
                    : earliestWake;
            if (lineState.sleepSince < 0L) {
                lineState.sleepSince = now;
            }
        } else {
            lineState.sleeping = false;
            lineState.sleepSince = -1L;
        }
    }

    /**
     * 观测结论是否允许搬。
     *
     * <p>只读缓存，<b>不探测</b>：UNKNOWN 说明观测服务还没轮到这台机器，先跳过（组会保持醒着，
     * 几十 tick 内就会被探到）。这样 IO 永远不会绕过探测预算去打一个尖峰。</p>
     */
    private static boolean runnable(ObservationService observations, MachineRef target) {
        Observation observation = observations.observe(target.blockKey());
        return observation.health().alive();
    }

    private static void sleepIfIdle(RunnerState.GroupState groupState, long now) {
        if (groupState.idleSince < 0L) {
            groupState.idleSince = now;
            return;
        }
        if (now - groupState.idleSince >= RunnerState.SLEEP_AFTER_TICKS) {
            groupState.sleeping = true;
            groupState.wakeAt = now + RunnerState.WAKE_INTERVAL;
        }
    }

    /**
     * 唤醒整条产线。
     *
     * @param lineId 产线 id
     */
    public void wake(UUID lineId) {
        this.state.wakeLine(lineId);
    }

    /**
     * 唤醒一个组。
     *
     * @param lineId  产线 id
     * @param groupId 组 id
     */
    public void wakeGroup(UUID lineId, UUID groupId) {
        this.state.wakeGroup(lineId, groupId);
    }

    /**
     * 这条线睡到什么时候。
     *
     * @param lineId 产线 id
     * @return 唤醒时间；没在睡时为 {@code 0}
     */
    public long sleepUntil(UUID lineId) {
        RunnerState.LineState state = this.state.peek(lineId);
        if (state == null || !state.sleeping) {
            return 0L;
        }
        return state.wakeAt;
    }

    /**
     * 这条线是什么时候开始睡的。
     *
     * @param lineId 产线 id
     * @return 睡眠起点；没在睡时为 {@code -1}
     */
    public long sleepSince(UUID lineId) {
        RunnerState.LineState state = this.state.peek(lineId);
        return state == null ? -1L : state.sleepSince;
    }

    /**
     * 丢掉一个组的面路由。玩家改规则（加/删输入输出）时调——面可能跟着变了。
     *
     * @param lineId  产线 id
     * @param groupId 组 id
     */
    public void forgetRoutes(UUID lineId, UUID groupId) {
        RunnerState.LineState line = this.state.peek(lineId);
        if (line == null) {
            return;
        }
        RunnerState.GroupState group = line.groups.get(groupId);
        if (group != null) {
            group.routes.clearAll();
        }
    }

    /**
     * 一条产线记住的面路由条数（诊断用）。
     *
     * @param lineId 产线 id
     * @return 路由条数
     */
    public int routeCount(UUID lineId) {
        RunnerState.LineState line = this.state.peek(lineId);
        if (line == null) {
            return 0;
        }
        int total = 0;
        for (RunnerState.GroupState group : line.groups.values()) {
            total += group.routes.size();
        }
        return total;
    }

    /**
     * 丢掉一条产线的睡眠状态。
     *
     * @param lineId 产线 id
     */
    public void forget(UUID lineId) {
        this.state.forget(lineId);
    }

    /** 清空全部状态（服务器停止时）。 */
    public void clear() {
        this.state.clear();
        this.variants.clear();
    }
}
