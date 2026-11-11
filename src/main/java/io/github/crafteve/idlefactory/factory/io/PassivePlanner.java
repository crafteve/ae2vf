package io.github.crafteve.idlefactory.factory.io;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 被动规划器：决定表单上的哪些单元这一轮跑、按什么顺序跑。
 *
 * <p>排期颗粒度是<b>规则</b>，不是机器：一条规则缺料不会拖累同一台机器的其它规则。择时每 tick
 * 用「现在 − 上次尝试」重算，不排未来；跨轮只记两样东西：同侧规则的游标，以及被显式唤醒的
 * 机器。执行器本身不认识「上次」。</p>
 *
 * <p>退避不是拍脑袋：满了要等机器自己消耗，缺料要等仓库变化（没有仓库事件，只能定时抬头），
 * 面错要重探（探面贵）。这些数字集中在本类的常量里，改退避只改这里。</p>
 */
public final class PassivePlanner {
    /** 缺料后多少 tick 再看一眼：仓库没有变化事件，只能定时抬头。 */
    private static final long NO_MATERIAL_TICKS = 5L;
    /** 满了后多少 tick 再试：等机器自己消耗。 */
    private static final long FULL_TICKS = 10L;
    /** 面不对后多少 tick 重探：探面是 21 次能力查询，不能每 tick 来。 */
    private static final long FACE_TICKS = 20L;
    /** 睡眠标记的兜底间隔（历史账本里有 SLEEPING 行时用）。 */
    private static final long SLEEPING_TICKS = 200L;
    /** 异常基础退避。 */
    private static final long ERROR_BASE_TICKS = 20L;
    /** 异常退避封顶。 */
    private static final long ERROR_CAP_TICKS = 100L;

    /** 一台机器每侧的游标：保证同侧多条规则轮流先跑，后面的不会被永久饿死。 */
    private final Map<UUID, int[]> cursors = new HashMap<>();
    /** 被显式唤醒的机器：下一轮忽略退避跑一次。 */
    private final Set<UUID> forced = new HashSet<>();
    /** 每台机器复用的执行清单，避免每 tick 新建列表。 */
    private final Map<UUID, Plan> plans = new HashMap<>();

    /**
     * 唤醒一台机器：下一轮所有单元都跑一次，不管退避。
     *
     * @param machineId 机器 id
     */
    public void wake(UUID machineId) {
        this.forced.add(machineId);
    }

    /**
     * 丢掉一台机器的游标与强制唤醒标记（机器被删时）。
     *
     * @param machineId 机器 id
     */
    public void forget(UUID machineId) {
        this.cursors.remove(machineId);
        this.forced.remove(machineId);
        this.plans.remove(machineId);
    }

    /** 清空全部排期状态（服务器停止时）。 */
    public void clear() {
        this.cursors.clear();
        this.forced.clear();
        this.plans.clear();
    }

    /**
     * 排一台机器这一轮的活。
     *
     * @param slot 本台机器的槽位
     * @param now  当前服务端 tick
     * @return 本轮的输入 / 输出清单（复用对象，调用方只读）
     */
    public Plan plan(IoForm.Slot slot, long now) {
        UUID machineId = slot.machine().id();
        Plan plan = this.plans.computeIfAbsent(machineId, id -> new Plan());
        plan.clear();
        boolean force = this.forced.remove(machineId);
        int[] cursor = this.cursors.computeIfAbsent(machineId, id -> new int[2]);
        int inputStart = cursor[0]++;
        int outputStart = cursor[1]++;
        this.collect(plan.inputs, slot.inputs(), inputStart, now, force);
        this.collect(plan.outputs, slot.outputs(), outputStart, now, force);
        return plan;
    }

    private void collect(List<IoUnit> target, List<IoUnit> units, int start, long now,
            boolean force) {
        int size = units.size();
        if (size == 0) {
            return;
        }
        int offset = Math.floorMod(start, size);
        for (int index = 0; index < size; index++) {
            IoUnit unit = units.get((offset + index) % size);
            if (force || this.due(unit, now)) {
                target.add(unit);
            }
        }
    }

    /**
     * 这个单元这一轮该不该跑。
     *
     * @param unit 单元
     * @param now  当前服务端 tick
     * @return 该跑为 {@code true}
     */
    private boolean due(IoUnit unit, long now) {
        long last = unit.digest().lastAttemptAt();
        if (last < 0L || last > now) {
            return true;
        }
        return now - last >= this.backoff(unit);
    }

    /**
     * 按上一轮结果算退避。
     *
     * @param unit 单元
     * @return 多少 tick 后允许再试
     */
    private long backoff(IoUnit unit) {
        IoLedger.Row row = unit.ledger().current();
        if (row == null) {
            return 0L;
        }
        return switch (row.reason()) {
            case RUN -> 0L;
            case SLEEPING -> SLEEPING_TICKS;
            case BUDGET_OUT -> 1L;
            case NO_MATERIAL -> NO_MATERIAL_TICKS;
            case FULL -> FULL_TICKS;
            case NO_FACE -> FACE_TICKS;
            case REJECTED -> Math.min(FACE_TICKS * Math.max(1, unit.digest().rejectStreak()), 100L);
            case ERROR -> Math.min(
                    ERROR_BASE_TICKS << Math.min(Math.max(unit.digest().countOf(Reason.ERROR) - 1, 0), 3),
                    ERROR_CAP_TICKS);
        };
    }

    /**
     * 一台机器这一轮要跑的单元。
     */
    public static final class Plan {
        private final List<IoUnit> inputs = new ArrayList<>();
        private final List<IoUnit> outputs = new ArrayList<>();

        /** 本轮的输入单元。 */
        public List<IoUnit> inputs() {
            return this.inputs;
        }

        /** 本轮的输出单元。 */
        public List<IoUnit> outputs() {
            return this.outputs;
        }

        /** 这一轮有没有活。 */
        public boolean isEmpty() {
            return this.inputs.isEmpty() && this.outputs.isEmpty();
        }

        private void clear() {
            this.inputs.clear();
            this.outputs.clear();
        }
    }
}
