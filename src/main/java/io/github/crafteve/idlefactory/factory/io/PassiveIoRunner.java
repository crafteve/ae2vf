package io.github.crafteve.idlefactory.factory.io;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.machine.Machine;

import appeng.api.storage.MEStorage;

import net.minecraft.core.Direction;

/**
 * 被动驱动：拿着本 tick 的表单，问规划器「这一轮跑哪些条目、走哪个面、搬什么」，再把 Job
 * 交给执行器逐条跑。
 *
 * <p>它只做三件事：遍历表单槽位（按轮转起点）、问规划器、执行 Job 并落账。睡眠、退避、游标、
 * 选面、变体展开全在 {@link PassivePlanner}；机器在不在是表单的事，这里不再有健康门禁。</p>
 *
 * <p>异常按机器兜住：第三方能力实现抛异常不能带崩服务端 tick，记一条节流 warn 并按本轮
 * 失败落账，下一轮照常重试。失败结果同时驱动端口失效（{@link PortExecutor}），让下一轮重探面。</p>
 */
public final class PassiveIoRunner {
    /** 坏机器日志节流间隔（tick）。 */
    private static final long FAILURE_LOG_INTERVAL = 1200L;

    private final PortExecutor ports;
    /** 排期。 */
    private final PassivePlanner planner;
    /** 唯一的机器侧写入口。 */
    private final IoExecutor executor;
    /** 模糊物品规则的变体索引；按工厂隔离，TTL 到期重建。 */
    private final Map<UUID, VariantPick> variants = new HashMap<>();
    /** 机器 id → 上次异常日志时刻，按机器节流刷屏。 */
    private final Map<UUID, Long> failureLogAt = new HashMap<>();
    /** 已跑过的轮数，用于槽位轮转起点。 */
    private long rounds;

    /**
     * @param ports 工厂共享的端口缓存
     */
    public PassiveIoRunner(PortExecutor ports) {
        this.ports = ports;
        this.planner = new PassivePlanner(new FacePick(ports));
        this.executor = new IoExecutor(ports);
    }

    /**
     * 跑一个工厂本 tick 的表单。
     *
     * @param factory 工厂
     * @param form    本 tick 的表单
     * @param now     当前服务端 tick
     */
    public void runFactory(Factory factory, Form form, long now) {
        if (!factory.live().storageOnline()) {
            this.rounds++;
            return;
        }
        MEStorage storage = factory.live().storage();
        List<Form.Slot> slots = form.slots();
        if (slots.isEmpty()) {
            this.rounds++;
            return;
        }
        // 机器轮转：每轮从不同的槽位开始，避免固定顺序总是先喂第一台机器。
        int start = (int) Math.floorMod(this.rounds, (long) slots.size());
        for (int index = 0; index < slots.size(); index++) {
            Form.Slot slot = slots.get((start + index) % slots.size());
            this.tickMachine(factory, storage, slot, now);
        }
        this.rounds++;
    }

    /**
     * 跑一台机器的一轮。
     *
     * @return 本轮搬动的数量
     */
    private long tickMachine(Factory factory, MEStorage storage, Form.Slot slot, long now) {
        Machine machine = slot.machine();
        if (!this.ports.loaded(machine)) {
            // 未加载不代表机器没了，下一轮再看
            return 0L;
        }
        PassivePlanner.Plan plan;
        try {
            VariantPick variantPick = this.variants.computeIfAbsent(factory.id(),
                    id -> new VariantPick());
            plan = this.planner.plan(slot, now, variantPick, storage);
        } catch (RuntimeException e) {
            // 规划阶段异常（第三方库存读不出来）：整台机器本轮按未搬动处理，不影响其它机器。
            this.logFailure(machine, now, e);
            return 0L;
        }
        if (plan.isEmpty()) {
            return 0L;
        }
        Analyzer analyzer = factory.live().analyzer();
        // 同一条规则可能展开成多个变体：按条目汇总成一行账，否则界面上一条规则会冒出好几行。
        Map<Form.Entry, EntryRun> runs = new LinkedHashMap<>();
        for (PassivePlanner.Failure failure : plan.failures()) {
            runs.computeIfAbsent(failure.entry(), entry -> new EntryRun())
                    .merge(failure.reason(), 0L, null);
        }
        for (IoJob job : plan.jobs()) {
            MoveResult result;
            try {
                result = this.executor.move(storage, job);
            } catch (RuntimeException e) {
                this.logFailure(machine, now, e);
                result = new MoveResult(0L, Reason.ERROR);
            }
            this.invalidateOnFailure(job, result);
            runs.computeIfAbsent(job.entry(), entry -> new EntryRun())
                    .merge(result.reason(), result.moved(), job.face());
        }
        long total = 0L;
        Map<RuleKey, Long> fedByRule = new LinkedHashMap<>();
        Map<RuleKey, Long> pulledByRule = new LinkedHashMap<>();
        for (Map.Entry<Form.Entry, EntryRun> entry : runs.entrySet()) {
            Form.Entry formEntry = entry.getKey();
            EntryRun run = entry.getValue();
            analyzer.record(formEntry, run.reason == null ? Reason.NO_MATERIAL : run.reason,
                    now, run.moved, run.face);
            total += run.moved;
            if (run.moved > 0L) {
                Map<RuleKey, Long> target = formEntry.side() == IoSide.INPUT
                        ? fedByRule : pulledByRule;
                target.merge(formEntry.key().rule(), run.moved, Long::sum);
            }
        }
        if (!fedByRule.isEmpty() || !pulledByRule.isEmpty()) {
            analyzer.stats().machine(machine.id()).addMoved(fedByRule, pulledByRule);
        }
        return total;
    }

    /**
     * 按失败结果失效端口缓存。
     *
     * <p>{@code REJECTED} / {@code ERROR} 只失效那个面；{@code NO_FACE} 失效整台机器的六个面
     * （连面都没选出来，缓存里的面不再可信）；{@code FULL} 不失效——面是对的，只是满了。</p>
     *
     * @param job    出问题的 Job
     * @param result 执行结果
     */
    private void invalidateOnFailure(IoJob job, MoveResult result) {
        switch (result.reason()) {
            case REJECTED, ERROR -> this.ports.invalidate(job.machine(), job.face());
            case NO_FACE -> this.ports.invalidateMachine(job.machine());
            case RUN, SLEEPING, BUDGET_OUT, NO_MATERIAL, FULL, FACE_CONFIG_INVALID -> {
                // 这些结果说明面本身没问题（配错面是配置问题，不是缓存脏了），不动缓存。
            }
        }
    }

    /**
     * 一台机器本轮搬运异常时记一条 warn，按机器节流。
     *
     * @param machine 出事的机器，日志里只取 short id
     * @param now     当前 game time
     * @param cause   捕获到的异常，作为 throwable 参数打印完整栈
     */
    private void logFailure(Machine machine, long now, RuntimeException cause) {
        Long last = this.failureLogAt.get(machine.id());
        if (last != null && now - last < FAILURE_LOG_INTERVAL) {
            return;
        }
        this.failureLogAt.put(machine.id(), now);
        IdleFactory.LOGGER.warn("Idle Factory: 机器 {} 本轮 IO 异常，已跳过本轮",
                machine.shortId(), cause);
    }

    /**
     * 唤醒一台机器的全部条目：下一轮忽略退避跑一次。
     *
     * @param machineId 机器 id
     */
    public void wake(UUID machineId) {
        this.planner.wake(machineId);
    }

    /**
     * 丢掉一台机器的排期、变体索引与日志节流状态（机器被删时）。
     *
     * @param machineId 机器 id
     */
    public void forget(UUID machineId) {
        this.planner.forget(machineId);
        this.failureLogAt.remove(machineId);
    }

    /** 清空全部状态（服务器停止时）。 */
    public void clear() {
        this.planner.clear();
        this.variants.clear();
        this.failureLogAt.clear();
        this.rounds = 0L;
    }

    /**
     * 一条规则这一轮的汇总：搬了多少、最坏原因、最后一个面。
     */
    private static final class EntryRun {
        private long moved;
        @Nullable
        private Reason reason;
        @Nullable
        private Direction face;

        void merge(@Nullable Reason nextReason, long amount, @Nullable Direction usedFace) {
            this.moved += amount;
            if (nextReason != null
                    && (this.reason == null || nextReason.severity() > this.reason.severity())) {
                this.reason = nextReason;
            }
            if (usedFace != null) {
                this.face = usedFace;
            }
        }
    }
}
