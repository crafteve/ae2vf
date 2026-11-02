package io.github.crafteve.idlefactory.factory.io;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

/**
 * IO 执行器的睡眠状态。
 *
 * <p>只有运行时，不写存档。服务器停止、配置变更都会清掉。</p>
 *
 * <p>P1 删除产线后睡眠只剩一级：每台机器连续 {@link #SLEEP_AFTER_TICKS} tick 没搬动就睡，
 * 睡着的机器每 {@link #WAKE_INTERVAL} tick 醒一次。原来那层「整条线的机器都睡 → 线也睡」的
 * 状态随产线一起删除。</p>
 *
 * <p>键是机器 id（全局唯一，由维度 + 坐标推导），所以一张表就能覆盖所有工厂。</p>
 *
 * <p>睡眠按 tick 算而不是按轮数：IO 间隔可调，按轮数会在 1 tick 时过于敏感。</p>
 */
public final class RunnerState {
    /** 连续多少 tick 没搬动就睡眠。 */
    public static final long SLEEP_AFTER_TICKS = 100L;
    /** 睡眠后多久醒一次（tick）。 */
    public static final long WAKE_INTERVAL = 200L;
    /**
     * 同一台机器两次「本轮 IO 异常」warn 的最小间隔（tick），1200 tick = 60 秒。
     *
     * <p>坏掉的机器每个 IO 轮次都会抛，逐轮打会把 latest.log 刷满；这里只限日志，
     * 不限制重试：异常轮仍走 {@code sleepIfIdle}，睡醒后照常再搬。</p>
     */
    public static final long FAILURE_LOG_INTERVAL = 1200L;

    private final Map<UUID, MachineState> machines = new HashMap<>();

    /**
     * 取一台机器的状态，没有就建。
     *
     * @param machineId 机器 id
     * @return 机器状态
     */
    public MachineState machine(UUID machineId) {
        return this.machines.computeIfAbsent(machineId, id -> new MachineState());
    }

    /**
     * 只看一眼状态，不创建。
     *
     * @param machineId 机器 id
     * @return 状态；还没跑过时为 {@code null}
     */
    @Nullable
    public MachineState peek(UUID machineId) {
        return this.machines.get(machineId);
    }

    /**
     * 唤醒一台机器。
     *
     * @param machineId 机器 id
     */
    public void wake(UUID machineId) {
        MachineState state = this.machines.get(machineId);
        if (state == null) {
            return;
        }
        state.sleeping = false;
        state.sleepSince = -1L;
        state.idleSince = -1L;
    }

    /**
     * 丢掉一台机器的状态（删机器时）。
     *
     * @param machineId 机器 id
     */
    public void forget(UUID machineId) {
        this.machines.remove(machineId);
    }

    /** 清空全部状态（服务器停止时）。 */
    public void clear() {
        this.machines.clear();
    }

    /** 已登记状态的机器数（诊断用）。 */
    public int size() {
        return this.machines.size();
    }

    /** 已登记状态的机器 id（只读快照，遍历时用）。 */
    public Set<UUID> ids() {
        return Set.copyOf(this.machines.keySet());
    }

    /** 一台机器的睡眠状态。 */
    public static final class MachineState {
        /** 本轮没搬动是从什么时候开始的；{@code -1} 表示还没开始计时。 */
        public long idleSince = -1L;
        /** 是否睡眠。 */
        public boolean sleeping;
        /** 进入睡眠的时间；{@code -1} 表示没在睡。 */
        public long sleepSince = -1L;
        /** 下次唤醒时间（game time）。 */
        public long wakeAt;
        /**
         * 上次因搬运异常打 warn 的 game time；{@code -1} 表示还没打过。
         *
         * <p>刻意不因一次成功清零：成败交替的坏 handler 会因此退化成每轮一行日志，
         * 正是要避免的刷屏。删机器时随整条状态一起清掉（{@code forget}）。</p>
         */
        public long lastFailureLogAt = -1L;
        /** 这台机器的面路由表。 */
        public final RouteTable routes = new RouteTable();
    }
}
