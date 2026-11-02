package io.github.crafteve.idlefactory.factory.io;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryManager;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.machine.MachineRef;
import io.github.crafteve.idlefactory.factory.capability.LevelCapabilityCache;
import io.github.crafteve.idlefactory.factory.observation.Observation;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;
import appeng.api.storage.MEStorage;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 被动执行器：遍历每个工厂机器库里的每一台机器，按机器自己的 IO 规则跑一轮搬运。
 *
 * <p>P1 删除产线后这里不再有「线 → 组 → 机器」的层级，只剩「工厂 → 机器库 → 机器」。
 * 睡眠也只有机器一级：连续 {@link RunnerState#SLEEP_AFTER_TICKS} tick 没搬动就睡，
 * 睡着的机器每 {@link RunnerState#WAKE_INTERVAL} tick 醒一次。{@code start} / 配置变更会手动唤醒。</p>
 *
 * <p><b>门禁读观测，不读世界</b>：一台机器只有「没被观测标记失联」且「观测结论是 PRESENT」
 * 才会进 {@link GroupRunner}。失联的、未加载的一律直接睡，零世界查询。</p>
 *
 * <p>路由表按机器隔离：一台机器的面路由不会影响另一台。</p>
 */
public final class MachineRunner {
    /** 探针打印间隔（tick）：首轮必打，之后每这么多轮打一次。 */
    private static final long PROBE_INTERVAL = 100L;

    private final RunnerState state = new RunnerState();
    /** 模糊物品规则的变体索引；按工厂隔离，TTL 到期重建。 */
    private final Map<UUID, ItemVariantIndex> variants = new HashMap<>();
    /** 已跑过的轮数，用于探针节流。 */
    private long rounds;

    /**
     * 跑一轮所有工厂的机器库。
     *
     * @param server       服务端
     * @param manager      工厂档案
     * @param observations 观测服务；为 {@code null} 时退化成「不做健康门禁」
     */
    public void tick(MinecraftServer server, FactoryManager manager,
            @Nullable ObservationService observations) {
        long now = server.overworld().getGameTime();
        int machines = 0;
        int awake = 0;
        long moved = 0L;
        for (Factory factory : manager.all()) {
            if (!factory.live().storageOnline()) {
                continue;
            }
            MEStorage storage = factory.live().storage();
            for (Machine machine : factory.config().machines().all()) {
                machines++;
                if (this.state.peek(machine.id()) != null
                        && isAwake(machine.id(), now)) {
                    awake++;
                }
                moved += tickMachine(server, factory, storage, machine, now, observations);
            }
        }
        // 探针：确认遍历的是机器库而不是产线。首轮与每 100 轮打一次，避免刷屏。
        if (this.rounds == 0L || this.rounds % PROBE_INTERVAL == 0L) {
        }
        this.rounds++;
    }

    /**
     * 这台机器这一轮是否会参与遍历（醒着、且不在睡眠期）。
     *
     * @param machineId 机器 id
     * @param now       当前游戏时间
     * @return 醒着时为 {@code true}
     */
    private boolean isAwake(UUID machineId, long now) {
        RunnerState.MachineState machineState = this.state.peek(machineId);
        return machineState == null || !machineState.sleeping || now >= machineState.wakeAt;
    }

    /**
     * 跑一台机器的一轮。
     *
     * @return 本轮搬动的数量
     */
    private long tickMachine(MinecraftServer server, Factory factory, MEStorage storage,
            Machine machine, long now, @Nullable ObservationService observations) {
        RunnerState.MachineState machineState = this.state.machine(machine.id());
        if (machineState.sleeping) {
            if (now < machineState.wakeAt) {
                return 0L;
            }
            machineState.sleeping = false;
            machineState.idleSince = -1L;
            machineState.sleepSince = -1L;
        }

        MachineRef target = machine.ref();
        if (factory.live().isMachineMissing(target.id())) {
            // 方块没了，机器仍在库；等方块回来时再唤醒
            sleepIfIdle(machineState, now);
            return 0L;
        }
        if (observations != null && !runnable(observations, target)) {
            sleepIfIdle(machineState, now);
            return 0L;
        }

        ServerLevel level = server.getLevel(target.dimension());
        if (level == null || !level.isLoaded(target.pos())) {
            // 未加载不代表机器没了，保持醒着，下一轮再看
            return 0L;
        }

        LevelCapabilityCache caps = observations == null ? null : observations.capabilities(level);
        long generation = observations == null ? 0L : observations.facesGeneration(target.blockKey());
        ItemVariantIndex factoryVariants = this.variants.computeIfAbsent(factory.id(),
                id -> new ItemVariantIndex());
        GroupRunner.GroupRun result;
        try {
            result = GroupRunner.run(storage, level, target.pos(), machine.io(), caps,
                    machineState.routes, generation, factoryVariants, now);
        } catch (RuntimeException e) {
            // 只在这里兜：第三方 handler（IItemHandler.insertItem / MEStorage.insert）都在
            // GroupRunner 内部被调用，异常穿出去会带崩整个服务端 tick。更糟的是 IoTransfer
            // 已经先从仓库扣了料（IoTransfer:49）才交给机器（:55），异常意味着「已扣未给」，
            // 这台机器本轮只能按「未搬动」处理：记节流 warn、走 sleepIfIdle、直接返回，
            // 下一轮照常重试，其余机器不受影响。
            // 只捕 RuntimeException，不捕 Error：OOM / StackOverflow / LinkageError 不是单台
            // 机器的运行期故障，吞掉只会掩盖更严重的问题；捕获后也不带半状态往下跑。
            logFailure(machine, machineState, now, e);
            sleepIfIdle(machineState, now);
            return 0L;
        }
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
        DiagStats.MachineStats stats = factory.live().stats().machine(target.id());
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
     * 观测结论是否允许搬。
     *
     * <p>只读缓存，<b>不探测</b>：UNKNOWN 说明观测服务还没轮到这台机器，先跳过（机器保持醒着，
     * 几十 tick 内就会被探到）。这样 IO 永远不会绕过探测预算去打一个尖峰。</p>
     */
    private static boolean runnable(ObservationService observations, MachineRef target) {
        Observation observation = observations.observe(target.blockKey());
        return observation.health().alive();
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
                machine.ref().shortId(), cause);
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
     * 这台机器睡到什么时候。
     *
     * @param machineId 机器 id
     * @return 唤醒时间；没在睡时为 {@code 0}
     */
    public long sleepUntil(UUID machineId) {
        RunnerState.MachineState state = this.state.peek(machineId);
        if (state == null || !state.sleeping) {
            return 0L;
        }
        return state.wakeAt;
    }

    /**
     * 这台机器是什么时候开始睡的。
     *
     * @param machineId 机器 id
     * @return 睡眠起点；没在睡时为 {@code -1}
     */
    public long sleepSince(UUID machineId) {
        RunnerState.MachineState state = this.state.peek(machineId);
        return state == null ? -1L : state.sleepSince;
    }

    /**
     * 丢掉一台机器的面路由。玩家改规则（加/删输入输出）时调——面可能跟着变了。
     *
     * @param machineId 机器 id
     */
    public void forgetRoutes(UUID machineId) {
        RunnerState.MachineState state = this.state.peek(machineId);
        if (state != null) {
            state.routes.clearAll();
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
        return state == null ? 0 : state.routes.size();
    }

    /**
     * 全部机器记住的面路由总条数（诊断用）。
     *
     * @return 路由条数
     */
    public int totalRouteCount() {
        int total = 0;
        for (UUID machineId : this.state.ids()) {
            total += routeCount(machineId);
        }
        return total;
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
