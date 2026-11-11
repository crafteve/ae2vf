package io.github.crafteve.idlefactory.factory.io;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.capability.LevelCapabilityCache;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;

import appeng.api.storage.MEStorage;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 被动驱动：拿着本 tick 的表单，问规划器「这一轮跑哪些单元」，再交给执行器搬。
 *
 * <p>它只做三件事：遍历表单槽位（按轮转起点）、问规划器、把结果交给执行器并落账。睡眠、
 * 退避、游标全在 {@link PassivePlanner}；机器在不在是表单的事，这里不再有健康门禁。</p>
 *
 * <p>异常按机器兜住：第三方 handler 抛异常不能带崩服务端 tick，记一条节流 warn 并按本轮
 * 失败落账，下一轮照常重试。</p>
 */
public final class NodeIoRunner {
    /** 坏机器日志节流间隔（tick）。 */
    private static final long FAILURE_LOG_INTERVAL = 1200L;

    /** 排期。 */
    private final PassivePlanner planner = new PassivePlanner();
    /** 把执行结果写进账本与结论。 */
    private final LedgerAnalyzer analyzer = new LedgerAnalyzer();
    /** 模糊物品规则的变体索引；按工厂隔离，TTL 到期重建。 */
    private final Map<UUID, ItemVariantIndex> variants = new HashMap<>();
    /** 机器 id → 上次异常日志时刻，按机器节流刷屏。 */
    private final Map<UUID, Long> failureLogAt = new HashMap<>();
    /** 已跑过的轮数，用于槽位轮转起点。 */
    private long rounds;

    /**
     * 跑一个工厂本 tick 的表单。
     *
     * @param server       服务端
     * @param factory      工厂
     * @param form         本 tick 的表单
     * @param observations 观测服务；为 {@code null} 时不给能力缓存
     * @param now          当前服务端 tick
     */
    public void runFactory(MinecraftServer server, Factory factory, IoForm form,
            @Nullable ObservationService observations, long now) {
        if (!factory.live().storageOnline()) {
            this.rounds++;
            return;
        }
        MEStorage storage = factory.live().storage();
        List<IoForm.Slot> slots = form.slots();
        if (slots.isEmpty()) {
            this.rounds++;
            return;
        }
        // 机器轮转：每轮从不同的槽位开始，避免固定顺序总是先喂第一台机器。
        int start = (int) Math.floorMod(this.rounds, (long) slots.size());
        for (int index = 0; index < slots.size(); index++) {
            IoForm.Slot slot = slots.get((start + index) % slots.size());
            tickMachine(server, factory, storage, slot, now, observations);
        }
        this.rounds++;
    }

    /**
     * 跑一台机器的一轮。
     *
     * @return 本轮搬动的数量
     */
    private long tickMachine(MinecraftServer server, Factory factory, MEStorage storage,
            IoForm.Slot slot, long now, @Nullable ObservationService observations) {
        Machine machine = slot.machine();
        PassivePlanner.Plan plan = this.planner.plan(slot, now);
        if (plan.isEmpty()) {
            return 0L;
        }
        BlockKey target = machine.block();
        ItemVariantIndex factoryVariants = this.variants.computeIfAbsent(factory.id(),
                id -> new ItemVariantIndex());
        int[] sharedBudget = {ItemCandidates.MAX_VARIANTS_PER_MACHINE_TICK};
        MachineIoRunner.BlockRun result;
        try {
            ServerLevel level = server.getLevel(target.dimension());
            if (level == null || !level.isLoaded(target.pos())) {
                // 未加载不代表机器没了，下一轮再看
                return 0L;
            }
            LevelCapabilityCache caps = observations == null
                    ? null : observations.capabilities(level);
            result = MachineIoRunner.runBlock(storage, level, target.pos(), plan.inputs(),
                    plan.outputs(), caps, factoryVariants, now, sharedBudget);
        } catch (RuntimeException e) {
            // 只在这里兜：第三方 handler 在 MachineIoRunner 内部被调用，异常穿出去会带崩
            // 整个服务端 tick。更糟的是 IoTransfer 已经先从仓库扣了料才交给机器，异常意味着
            // 「已扣未给」，这台机器本轮只能按未搬动处理：记节流 warn、落一条 ERROR 账，
            // 下一轮照常重试，其余机器不受影响。只捕 RuntimeException，不捕 Error。
            this.logFailure(machine, now, e);
            this.recordReason(plan, Reason.ERROR, now);
            return 0L;
        }
        this.recordResult(plan, result, now);
        if (result.movedAnything()) {
            factory.live().stats().machine(machine.id())
                    .addMoved(result.fedByRule(), result.pulledByRule());
        }
        return result.moved();
    }

    /**
     * 把本轮结果按单元写进账本与结论。
     *
     * @param plan   本轮执行清单
     * @param result 本轮结果
     * @param now    当前服务端 tick
     */
    private void recordResult(PassivePlanner.Plan plan, MachineIoRunner.BlockRun result, long now) {
        for (IoUnit unit : plan.inputs()) {
            RuleKey rule = unit.key().rule();
            Reason reason = result.inputReasons().get(rule);
            if (reason != null) {
                this.analyzer.record(unit, reason, now,
                        result.fedByRule().getOrDefault(rule, 0L), result.faces().get(rule));
            }
        }
        for (IoUnit unit : plan.outputs()) {
            RuleKey rule = unit.key().rule();
            Reason reason = result.outputReasons().get(rule);
            if (reason != null) {
                this.analyzer.record(unit, reason, now,
                        result.pulledByRule().getOrDefault(rule, 0L), result.faces().get(rule));
            }
        }
    }

    /**
     * 给清单里所有单元记同一个原因（没有数量）。
     *
     * <p>用于整台机器本轮没跑成：异常。界面上「异常」来自这里。</p>
     *
     * @param plan   本轮执行清单
     * @param reason 原因
     * @param now    当前服务端 tick
     */
    private void recordReason(PassivePlanner.Plan plan, Reason reason, long now) {
        for (IoUnit unit : plan.inputs()) {
            this.analyzer.record(unit, reason, now, 0L, null);
        }
        for (IoUnit unit : plan.outputs()) {
            this.analyzer.record(unit, reason, now, 0L, null);
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
     * 唤醒一台机器的全部单元：下一轮忽略退避跑一次。
     *
     * @param machineId 机器 id
     */
    public void wake(UUID machineId) {
        this.planner.wake(machineId);
    }

    /**
     * 丢掉一台机器的排期与日志节流状态（机器被删时）。
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
}
