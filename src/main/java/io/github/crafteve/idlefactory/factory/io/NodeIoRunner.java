package io.github.crafteve.idlefactory.factory.io;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.capability.LevelCapabilityCache;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;
import appeng.api.storage.MEStorage;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 被动执行器：跑本 tick 的表单。
 *
 * <p>它只认识表单上的槽位（一台机器 + 它的规则），不再遍历节点，也不判断机器在不在——那是
 * 表单的事。睡眠仍是机器一级：连续 {@link RunnerState#SLEEP_AFTER_TICKS} tick 没搬动就睡，
 * 睡着的机器每 {@link RunnerState#WAKE_INTERVAL} tick 醒一次；配置变更会手动唤醒。</p>
 *
 * <p>路由表按机器隔离：一台机器的面路由不会影响另一台。面表代数与能力缓存来自观测层。</p>
 */
public final class NodeIoRunner {
    /** 探针打印间隔（tick）：首轮必打，之后每这么多轮打一次。 */
    private static final long PROBE_INTERVAL = 100L;

    private final RunnerState state = new RunnerState();
    /** 把执行结果写进账本与结论。 */
    private final LedgerAnalyzer analyzer = new LedgerAnalyzer();
    /** 模糊物品规则的变体索引；按工厂隔离，TTL 到期重建。 */
    private final Map<UUID, ItemVariantIndex> variants = new HashMap<>();
    /** 已跑过的轮数，用于探针节流。 */
    private long rounds;

    /**
     * 跑一个工厂本 tick 的表单。
     *
     * @param server       服务端
     * @param factory      工厂
     * @param form         本 tick 的表单
     * @param observations 观测服务；为 {@code null} 时不给能力缓存与面表代数
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
        RunnerState.MachineState machineState = this.state.machine(machine.id());
        if (machineState.sleeping) {
            if (now < machineState.wakeAt) {
                this.recordReason(slot, Reason.SLEEPING, now);
                return 0L;
            }
            machineState.sleeping = false;
            machineState.idleSince = -1L;
            machineState.sleepSince = -1L;
        }

        BlockKey target = machine.block();
        ItemVariantIndex factoryVariants = this.variants.computeIfAbsent(factory.id(),
                id -> new ItemVariantIndex());
        int[] sharedBudget = {ItemCandidates.MAX_VARIANTS_PER_MACHINE_TICK};
        MachineIoRunner.BlockRun result;
        try {
            ServerLevel level = server.getLevel(target.dimension());
            if (level == null || !level.isLoaded(target.pos())) {
                // 未加载不代表机器没了，保持醒着，下一轮再看
                return 0L;
            }
            LevelCapabilityCache caps = observations == null
                    ? null : observations.capabilities(level);
            long generation = observations == null ? 0L : observations.facesGeneration(target);
            result = MachineIoRunner.runBlock(storage, level, target.pos(), slot, caps,
                    machineState.routes(target), generation, factoryVariants, now, sharedBudget);
        } catch (RuntimeException e) {
            // 只在这里兜：第三方 handler（IItemHandler.insertItem / MEStorage.insert）都在
            // MachineIoRunner 内部被调用，异常穿出去会带崩整个服务端 tick。更糟的是 IoTransfer
            // 已经先从仓库扣了料（IoTransfer:49）才交给机器（:55），异常意味着「已扣未给」，
            // 这台机器本轮只能按「未搬动」处理：记节流 warn、走 sleepIfIdle、直接返回，
            // 下一轮照常重试，其余机器不受影响。
            // 只捕 RuntimeException，不捕 Error：OOM / StackOverflow / LinkageError 不是单台
            // 机器的运行期故障，吞掉只会掩盖更严重的问题；捕获后也不带半状态往下跑。
            logFailure(machine, machineState, now, e);
            this.recordReason(slot, Reason.ERROR, now);
            sleepIfIdle(machineState, now);
            return 0L;
        }
        // 熔断器喂食：只有面级拒收（REJECTED）才记——面不对和机器没了是两回事，
        // 这里进去的只刷面表，绝不动健康；其他任何结果（搬通了、没料、满了）
        // 都算“路没问题”，清掉连续计数。详见 noteRejected。
        if (observations != null) {
            BlockKey key = target;
            if (result.inputReason() == Reason.REJECTED
                    || result.outputReason() == Reason.REJECTED) {
                observations.noteRejected(key);
            } else {
                observations.noteSuccess(key);
            }
        }
        this.recordResult(slot, result, now);
        DiagStats.MachineStats stats = factory.live().stats().machine(machine.id());
        // 逐规则原因独立于组级搬运结果：部分规则没搬动时也要让界面看到具体哪条卡住。
        stats.setRuleReasons(result.inputReasons(), result.outputReasons());
        if (result.movedAnything()) {
            stats.addMoved(result.fedByKey(), result.pulledByKey(),
                    result.fedByRule(), result.pulledByRule(), now);
            machineState.idleSince = -1L;
            machineState.sleeping = false;
        } else {
            stats.markBlocked(result.inputReason(), result.outputReason(),
                    result.inputFull(), now);
            sleepIfIdle(machineState, now);
        }
        return result.moved();
    }

    /**
     * 把本轮结果按单元写进账本与结论。
     *
     * @param slot   本台机器的槽位
     * @param result 本轮结果
     * @param now    当前服务端 tick
     */
    private void recordResult(IoForm.Slot slot, MachineIoRunner.BlockRun result, long now) {
        for (IoUnit unit : slot.inputs()) {
            RuleKey rule = unit.key().rule();
            Reason reason = result.inputReasons().get(rule);
            if (reason != null) {
                this.analyzer.record(unit, reason, now,
                        result.fedByRule().getOrDefault(rule, 0L), unit.spec().face());
            }
        }
        for (IoUnit unit : slot.outputs()) {
            RuleKey rule = unit.key().rule();
            Reason reason = result.outputReasons().get(rule);
            if (reason != null) {
                this.analyzer.record(unit, reason, now,
                        result.pulledByRule().getOrDefault(rule, 0L), unit.spec().face());
            }
        }
    }

    /**
     * 给槽位里所有单元记同一个原因（没有数量）。
     *
     * <p>用于整台机器本轮没跑到：睡觉、异常。界面上「休息中」与「异常」都来自这里。</p>
     *
     * @param slot   本台机器的槽位
     * @param reason 原因
     * @param now    当前服务端 tick
     */
    private void recordReason(IoForm.Slot slot, Reason reason, long now) {
        for (IoUnit unit : slot.inputs()) {
            this.analyzer.record(unit, reason, now, 0L, unit.spec().face());
        }
        for (IoUnit unit : slot.outputs()) {
            this.analyzer.record(unit, reason, now, 0L, unit.spec().face());
        }
    }

    private static void sleepIfIdle(RunnerState.MachineState machineState, long now) {
        if (machineState.idleSince < 0L) {
            machineState.idleSince = now;
            return;
        }
        if (now - machineState.idleSince >= RunnerState.SLEEP_AFTER_TICKS) {
            if (!machineState.sleeping && machineState.sleepSince < 0L) {
                machineState.sleepSince = now;
            }
            machineState.sleeping = true;
            machineState.wakeAt = now + RunnerState.WAKE_INTERVAL;
        }
    }

    /**
     * 一台机器本轮搬运异常时记一条 warn，按机器节流。
     *
     * <p>坏机器每个 IO 轮次都会抛，逐轮打会把 latest.log 刷满：首条必打（{@code lastFailureLogAt}
     * 初值 {@code -1} 表示还没打过），之后同一台机器每 {@link RunnerState#FAILURE_LOG_INTERVAL}
     * tick 最多一条。节流状态挂在这台机器的瞬态状态里，{@code forget} 删机器时一起清掉，
     * 重新加回来的机器能立刻再看见。</p>
     *
     * @param machine      出事的机器，日志里只取 short id
     * @param machineState 这台机器的瞬态状态，用来记上次打日志的时间
     * @param now          当前 game time
     * @param cause        捕获到的异常，作为 throwable 参数打印完整栈
     */
    private static void logFailure(Machine machine, RunnerState.MachineState machineState, long now,
            RuntimeException cause) {
        if (machineState.lastFailureLogAt >= 0L
                && now - machineState.lastFailureLogAt < RunnerState.FAILURE_LOG_INTERVAL) {
            return;
        }
        machineState.lastFailureLogAt = now;
        IdleFactory.LOGGER.warn("Idle Factory: 机器 {} 本轮 IO 异常，已跳过本轮",
                machine.shortId(), cause);
    }

    /**
     * 唤醒一台机器。
     *
     * @param machineId 机器 id
     */
    public void wake(UUID machineId) {
        this.state.wake(machineId);
    }

    /**
     * 丢掉一台机器的面路由。玩家改规则（加/删输入输出）时调——面可能跟着变了。
     *
     * @param machineId 机器 id
     */
    public void forgetRoutes(UUID machineId) {
        RunnerState.MachineState state = this.state.peek(machineId);
        if (state != null) {
            state.clearRoutes();
        }
    }

    /**
     * 一台机器记住的面路由条数（诊断用）。
     *
     * @param machineId 机器 id
     * @return 路由条数
     */
    public int routeCount(UUID machineId) {
        RunnerState.MachineState state = this.state.peek(machineId);
        return state == null ? 0 : state.routeCount();
    }

    /**
     * 丢掉一台机器的睡眠状态。
     *
     * @param machineId 机器 id
     */
    public void forget(UUID machineId) {
        this.state.forget(machineId);
    }

    /** 清空全部状态（服务器停止时）。 */
    public void clear() {
        this.state.clear();
        this.variants.clear();
        this.rounds = 0L;
    }
}
