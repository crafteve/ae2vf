package io.github.crafteve.ae2vf.io;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.ae2vf.machine.Machine;
import io.github.crafteve.ae2vf.machine.IoRuleSpec;

import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;

/**
 * 被动规划器：决定表单上的哪些条目这一轮跑、走哪个面、搬什么，产出可直接执行的
 * {@link IoJob}。
 *
 * <p>排期颗粒度是<b>规则</b>，不是机器：一条规则缺料不会拖累同一台机器的其它规则。择时每 tick
 * 用「现在 − 上次尝试」重算，不排未来；跨轮只记两样东西：同侧规则的游标，以及被显式唤醒的
 * 机器。执行器本身不认识「上次」。</p>
 *
 * <p>选面与变体展开都在这里完成：执行器拿到的 Job 已经带面、带具体变体 key，它只跑事务。
 * 退避、变体额度、游标这些「择时」逻辑也就集中在本类，不再散在执行器里。</p>
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

    private final FacePick facePick;
    /** 一台机器每侧的游标：保证同侧多条规则轮流先跑，后面的不会被永久饿死。 */
    private final Map<UUID, int[]> cursors = new HashMap<>();
    /** 被显式唤醒的机器：下一轮忽略退避跑一次。 */
    private final Set<UUID> forced = new HashSet<>();
    /** 每台机器复用的执行清单，避免每 tick 新建列表。 */
    private final Map<UUID, Plan> plans = new HashMap<>();

    /**
     * @param facePick 选面；本类不直接碰端口缓存
     */
    public PassivePlanner(FacePick facePick) {
        this.facePick = facePick;
    }

    /**
     * 唤醒一台机器：下一轮所有条目都跑一次，不管退避。
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

    /**
     * 按配置机器集合保留：只有离开配置的机器才释放游标、强制标记与执行清单。
     *
     * @param keep 当前配置引用的机器 id（含 UNLOADED）
     */
    public void retainMachines(Set<UUID> keep) {
        this.cursors.keySet().removeIf(id -> !keep.contains(id));
        this.forced.removeIf(id -> !keep.contains(id));
        this.plans.keySet().removeIf(id -> !keep.contains(id));
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
     * @param slot     本台机器的槽位
     * @param now      当前服务端 tick
     * @param variants 模糊物品的变体查询（每工厂一份）
     * @param network  仓库
     * @return 本轮的 Job 清单与失败清单（复用对象，调用方只读）
     */
    public Plan plan(IoForm.Slot slot, long now, VariantPick variants, MEStorage network) {
        UUID machineId = slot.machine().id();
        Plan plan = this.plans.computeIfAbsent(machineId, id -> new Plan());
        plan.clear();
        boolean force = this.forced.remove(machineId);
        int[] cursor = this.cursors.computeIfAbsent(machineId, id -> new int[2]);
        Machine machine = slot.machine();

        // 变体额度按侧分配：输出侧有模糊规则时给输入留 4 个，与旧执行器的口径一致。
        int[] budget = {VariantPick.MAX_VARIANTS_PER_MACHINE_TICK};
        int inputQuota = this.sideAllowance(slot, IoSide.INPUT);
        budget[0] = inputQuota;
        this.collect(plan, slot, slot.inputs(), cursor[0]++, now, force, machine, variants, network,
                budget);
        // 输出实际额度 = 整台机器额度 - 输入实际用量，不固定为预留值。
        int inputUsed = inputQuota - budget[0];
        budget[0] = Math.max(0, VariantPick.MAX_VARIANTS_PER_MACHINE_TICK - inputUsed);
        this.collect(plan, slot, slot.outputs(), cursor[1]++, now, force, machine, variants, network,
                budget);
        return plan;
    }

    /**
     * 输入侧这一轮能用的变体额度。
     *
     * <p>只有模糊物品规则会扣额度：输出侧没有模糊规则时输入可以拿满整台机器的额度，否则给
     * 输出预留 {@link VariantPick#VARIANT_BUDGET_RESERVED_FOR_OUTPUT} 个，避免两条模糊输入就把
     * 池子用光、输出规则每 tick 都被跳过。</p>
     */
    private int sideAllowance(IoForm.Slot slot, IoSide side) {
        if (side == IoSide.INPUT) {
            return this.usesVariants(slot.outputs())
                    ? VariantPick.MAX_VARIANTS_PER_INPUT_TICK
                    : VariantPick.MAX_VARIANTS_PER_MACHINE_TICK;
        }
        return VariantPick.MAX_VARIANTS_PER_MACHINE_TICK;
    }

    private void collect(Plan plan, IoForm.Slot slot, List<IoForm.Entry> entries, int start, long now,
            boolean force, Machine machine, VariantPick variants, MEStorage network, int[] budget) {
        int size = entries.size();
        if (size == 0) {
            return;
        }
        int offset = Math.floorMod(start, size);
        for (int index = 0; index < size; index++) {
            IoForm.Entry entry = entries.get((offset + index) % size);
            if (!force && !due(entry, now)) {
                continue;
            }
            this.planEntry(plan, entry, machine, variants, network, now, budget);
        }
    }

    /**
     * 排一条规则：选面 → 展开变体 → 逐变体产出一个 Job。
     */
    private void planEntry(Plan plan, IoForm.Entry entry, Machine machine, VariantPick variants,
            MEStorage network, long now, int[] budget) {
        IoRuleSpec spec = entry.spec();
        IoSide side = entry.side();
        FacePick.Result pick = this.facePick.pick(machine, spec.key(), side, spec.face(),
                entry.conclusion().faceHint());
        if (!pick.picked()) {
            plan.failures.add(new Failure(entry, pick.failure()));
            return;
        }
        MEStorage machineStorage = side == IoSide.OUTPUT
                ? this.facePick.storageOf(machine, pick.face()) : null;
        List<AEKey> candidates = variants.candidates(network, machineStorage, spec, side, now,
                budget);
        if (candidates.isEmpty()) {
            // 额度耗尽是「没排上」，不是「没料」：两者不能混，否则界面会报错。
            plan.failures.add(new Failure(entry,
                    budget[0] <= 0 ? IoReason.BUDGET_OUT : IoReason.NO_MATERIAL));
            return;
        }
        long amount = spec.amount() <= 0L ? Long.MAX_VALUE : spec.amount();
        for (AEKey key : candidates) {
            plan.jobs.add(new IoJob(entry, pick.face(), key, amount));
        }
    }

    /**
     * 这一侧的规则里是否有会扣变体额度的模糊物品规则。
     */
    private boolean usesVariants(List<IoForm.Entry> entries) {
        for (IoForm.Entry entry : entries) {
            if (entry.spec().fuzzy()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 界面显示用的原因：还在退避里就报「休息中」，否则报上一轮结果。
     *
     * <p>这条合成只服务视图：账本只记执行层真的跑过的那一轮，退避中的空白轮不写账。
     * </p>
     *
     * @param entry 条目
     * @param now   当前服务端 tick
     * @return 原因
     */
    public static IoReason displayReason(IoForm.Entry entry, long now) {
        IoAnalyzer.Ledger.Row row = entry.ledger().current();
        if (row == null) {
            return IoReason.NO_MATERIAL;
        }
        return due(entry, now) ? row.reason() : IoReason.SLEEPING;
    }

    /**
     * 这个条目这一轮该不该跑。
     *
     * @param entry 条目
     * @param now   当前服务端 tick
     * @return 该跑为 {@code true}
     */
    public static boolean due(IoForm.Entry entry, long now) {
        long last = entry.conclusion().lastAttemptAt();
        if (last < 0L || last > now) {
            return true;
        }
        return now - last >= backoff(entry);
    }

    /**
     * 按上一轮结果算退避。
     *
     * @param entry 条目
     * @return 多少 tick 后允许再试
     */
    private static long backoff(IoForm.Entry entry) {
        IoAnalyzer.Ledger.Row row = entry.ledger().current();
        if (row == null) {
            return 0L;
        }
        return switch (row.reason()) {
            case RUN -> 0L;
            case SLEEPING -> SLEEPING_TICKS;
            case BUDGET_OUT -> 1L;
            case NO_MATERIAL -> NO_MATERIAL_TICKS;
            case FULL -> FULL_TICKS;
            case NO_FACE, FACE_CONFIG_INVALID -> FACE_TICKS;
            case REJECTED -> Math.min(FACE_TICKS * Math.max(1, entry.conclusion().rejectStreak()), 100L);
            case ERROR -> Math.min(
                    ERROR_BASE_TICKS << Math.min(Math.max(entry.conclusion().countOf(IoReason.ERROR) - 1, 0), 3),
                    ERROR_CAP_TICKS);
        };
    }

    /**
     * 一条没法产出 Job 的规则与原因。
     *
     * @param entry  条目
     * @param reason 为什么这一轮没排上
     */
    public record Failure(IoForm.Entry entry, @Nullable IoReason reason) {
    }

    /**
     * 一台机器这一轮要跑的 Job 与失败项。
     */
    public static final class Plan {
        private final List<IoJob> jobs = new ArrayList<>();
        private final List<Failure> failures = new ArrayList<>();

        /** 本轮的 Job。 */
        public List<IoJob> jobs() {
            return this.jobs;
        }

        /** 本轮没排上 Job 的规则。 */
        public List<Failure> failures() {
            return this.failures;
        }

        /** 这一轮有没有活。 */
        public boolean isEmpty() {
            return this.jobs.isEmpty() && this.failures.isEmpty();
        }

        private void clear() {
            this.jobs.clear();
            this.failures.clear();
        }
    }
}
