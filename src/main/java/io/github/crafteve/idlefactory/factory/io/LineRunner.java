package io.github.crafteve.idlefactory.factory.io;

import java.util.UUID;

import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryManager;
import io.github.crafteve.idlefactory.factory.diag.DiagStats;
import io.github.crafteve.idlefactory.factory.line.IoGroup;
import io.github.crafteve.idlefactory.factory.line.PassiveLine;
import io.github.crafteve.idlefactory.factory.storage.Storage;
import io.github.crafteve.idlefactory.factory.machine.MachineRef;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 被动产线执行器：按线 → 组 → 机器的顺序跑 IO。
 *
 * <p>带两级睡眠：组连续几轮没搬动就睡，整条线的组都睡就线睡。睡眠中的单位只等定时唤醒，
 * 不参与每轮遍历。{@code start} / {@code end} / 配置变更会手动唤醒。</p>
 */
public final class LineRunner {
    private final RunnerState state = new RunnerState();

    /**
     * 跑一轮所有运行中的产线。
     *
     * @param server  服务端
     * @param manager 工厂档案
     */
    public void tick(MinecraftServer server, FactoryManager manager) {
        long now = server.overworld().getGameTime();
        for (Factory factory : manager.all()) {
            if (!factory.storageLink().online()) {
                continue;
            }
            Storage storage = factory.storage();
            for (PassiveLine line : factory.lines().all()) {
                if (!line.running()) {
                    continue;
                }
                tickLine(server, factory, storage, line, now);
            }
        }
    }

    private void tickLine(MinecraftServer server, Factory factory, Storage storage,
            PassiveLine line, long now) {
        RunnerState.LineState lineState = this.state.line(line.id());
        if (lineState.sleeping && now < lineState.wakeAt) {
            return;
        }

        boolean anyAwake = false;
        long earliestWake = Long.MAX_VALUE;

        for (IoGroup group : line.groups()) {
            RunnerState.GroupState groupState = lineState.group(group.id());
            if (groupState.sleeping) {
                if (now < groupState.wakeAt) {
                    earliestWake = Math.min(earliestWake, groupState.wakeAt);
                    continue;
                }
                groupState.sleeping = false;
                groupState.idleSince = -1L;
            }
            anyAwake = true;

            MachineRef target = factory.machines().byId(group.machineId()).orElse(null);
            if (target == null) {
                sleepIfIdle(groupState, now);
                continue;
            }
            ServerLevel level = server.getLevel(target.dimension());
            if (level == null || !level.isLoaded(target.pos())) {
                // 未加载不代表机器没了，保持醒着，下一轮再看
                continue;
            }

            GroupRunner.GroupRun result = GroupRunner.run(storage, level, target.pos(), group);
            DiagStats.LineStats stats = factory.stats().line(line.id());
            if (result.movedAnything()) {
                stats.addMoved(target.id(), result.fedByKey(), result.pulledByKey(), now);
                groupState.idleSince = -1L;
                groupState.sleeping = false;
            } else {
                stats.markBlocked(target.id(), result.lastReason(), now);
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
    }
}
