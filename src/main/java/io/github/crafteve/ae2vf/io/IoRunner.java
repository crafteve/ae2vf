package io.github.crafteve.ae2vf.io;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.ae2vf.VoidFactory;
import io.github.crafteve.ae2vf.node.NodeDispatchOrder;
import io.github.crafteve.ae2vf.factory.Factory;
import io.github.crafteve.ae2vf.machine.Machine;

import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;

import net.minecraft.core.Direction;

/**
 * 一个工厂的执行编排：按 回收 → 主动 → 被动 依次唤醒规划器与执行器，最后收口账房。
 *
 * <p>它是 {@code io} 对工厂的唯一入口：自己不做决策、不选面，只负责顺序、异常边界与把结果
 * 交回账房。异常按节点 / 机器兜住，一个节点炸掉不影响其它节点，更不带崩服务端 tick。</p>
 *
 * <p>吸收了三件事：被动驱动、主动投递驱动与产物回收的调用。回收动作本身在
 * {@link IoExecutor#sweep}，回收节奏在 {@link ActivePlanner}。</p>
 */
public final class IoRunner {
    /** 坏机器日志节流间隔（tick）。 */
    private static final long FAILURE_LOG_INTERVAL = 1200L;

    private final PortExecutor ports;
    private final FacePick facePick;
    private final CapacityPick capacityPick;
    /** 唯一的机器侧写入口。 */
    private final IoExecutor executor;
    /** 被动排期。 */
    private final PassivePlanner passivePlanner;
    /** 每工厂一份的主动规划器；按节点记回收节奏。 */
    private final Map<UUID, ActivePlanner> actives = new HashMap<>();
    /** 模糊物品规则的变体索引；按工厂隔离，TTL 到期重建。 */
    private final Map<UUID, VariantPick> variants = new HashMap<>();
    /** 机器 id → 上次异常日志时刻，按机器节流刷屏。 */
    private final Map<UUID, Long> failureLogAt = new HashMap<>();
    /** 工厂 id → 上一 tick 见过的节点 id，用来清掉已消失节点的节奏。 */
    private final Map<UUID, Set<UUID>> knownNodes = new HashMap<>();
    /** 节点 id → 上一 tick 的队列单数，用来判断「有新单入队」。 */
    private final Map<UUID, Integer> queueSizes = new HashMap<>();
    /** 已跑过的轮数，用于槽位轮转起点。 */
    private long rounds;

    /**
     * @param ports 工厂共享的端口缓存
     */
    public IoRunner(PortExecutor ports) {
        this.ports = ports;
        this.facePick = new FacePick(ports);
        this.capacityPick = new CapacityPick(this.facePick, ports);
        this.executor = new IoExecutor(ports);
        this.passivePlanner = new PassivePlanner(this.facePick);
    }

    /**
     * 跑一个工厂本 tick 的执行段。
     *
     * @param factory      工厂
     * @param form         本 tick 的表单
     * @param dispatches   各节点的主动侧输入
     * @param liveMachines 当前仍存在的机器 id（清理账房用）
     * @param now          当前服务端 tick
     */
    public void run(Factory factory, IoForm form, List<DispatchState> dispatches,
            Set<UUID> liveMachines, long now) {
        IoAnalyzer analyzer = factory.analyzer();
        try {
            if (!factory.storageOnline()) {
                // 仓库离线就是没料：不跑搬运，但账房仍然要收口。
                return;
            }
            MEStorage network = factory.storage();
            this.trackNodes(factory.id(), dispatches, now);
            this.recover(factory, dispatches, network, analyzer, now);
            this.active(factory, dispatches, network, analyzer, now);
            this.passive(factory, form, network, analyzer, now);
        } finally {
            analyzer.retainMachines(liveMachines);
            this.ports.retainMachines(liveMachines);
            this.passivePlanner.retainMachines(liveMachines);
            Set<UUID> nodeIds = dispatches.stream().map(DispatchState::nodeId)
                    .collect(java.util.stream.Collectors.toSet());
            analyzer.retainNodes(nodeIds);
        }
    }

    /**
     * 跟踪节点集合与队列增长：新单入队时把回收拉回活跃节奏，节点消失时清掉节奏与退避。
     *
     * <p>这两件事都是执行层自己的状态，不需要节点回头调执行层：节点只交出
     * {@link DispatchState}，增删由这里按「上一 tick 的快照」做差集。</p>
     */
    private void trackNodes(UUID factoryId, List<DispatchState> dispatches, long now) {
        ActivePlanner planner = this.activePlanner(factoryId);
        Set<UUID> current = new java.util.HashSet<>();
        for (DispatchState state : dispatches) {
            current.add(state.nodeId());
            int size = state.queue().size();
            Integer previous = this.queueSizes.get(state.nodeId());
            if (previous == null || size > previous) {
                // 新单入队：产物需要尽快回网络，回收不要停在最慢的退避档上。
                planner.activate(state.nodeId(), now);
            }
            this.queueSizes.put(state.nodeId(), size);
        }
        Set<UUID> previousNodes = this.knownNodes.put(factoryId, current);
        if (previousNodes == null) {
            return;
        }
        for (UUID nodeId : previousNodes) {
            if (!current.contains(nodeId)) {
                planner.forget(nodeId);
                this.queueSizes.remove(nodeId);
            }
        }
    }

    /**
     * 回收段：节点到期就扫它的在线机器，产物按白名单回网络。
     */
    private void recover(Factory factory, List<DispatchState> dispatches, MEStorage network,
            IoAnalyzer analyzer, long now) {
        ActivePlanner planner = this.activePlanner(factory.id());
        for (DispatchState state : dispatches) {
            if (!planner.sweepDue(state.nodeId(), now)) {
                continue;
            }
            boolean harvested = false;
            for (Machine machine : state.onlineMachines()) {
                Map<AEKey, IoResult> results;
                try {
                    results = this.executor.sweep(machine, network, state.products());
                } catch (RuntimeException e) {
                    this.logFailure(machine, now, e);
                    analyzer.recordActive(state.nodeId(), IoReason.ERROR, now, 0L, null, null,
                            machine.id(), null);
                    continue;
                }
                for (Map.Entry<AEKey, IoResult> entry : results.entrySet()) {
                    IoResult result = entry.getValue();
                    analyzer.recordActive(state.nodeId(), result.reason(), now, result.moved(),
                            null, entry.getKey(), machine.id(), null);
                    harvested |= result.movedAnything();
                }
            }
            if (harvested) {
                planner.activate(state.nodeId(), now);
            } else {
                planner.idle(state.nodeId(), now);
            }
        }
    }

    /**
     * 主动段：规划一节点这一 tick 的投递，执行填充与退回。
     */
    private void active(Factory factory, List<DispatchState> dispatches, MEStorage network,
            IoAnalyzer analyzer, long now) {
        ActivePlanner planner = this.activePlanner(factory.id());
        for (DispatchState state : dispatches) {
            ActivePlanner.Plan plan;
            try {
                plan = planner.plan(state, analyzer, now);
            } catch (RuntimeException e) {
                VoidFactory.LOGGER.warn("AE2 Void Factory: 节点 {} 投递规划异常：{}",
                        state.nodeId().toString().substring(0, 8), e.toString());
                continue;
            }
            for (ActivePlanner.FillRequest fill : plan.fills()) {
                this.executeFill(state, fill, network, analyzer, planner, now);
            }
            for (ActivePlanner.RefundRequest refund : plan.refunds()) {
                this.executeBatchRefund(state, refund, network, analyzer, planner, now);
            }
            for (ActivePlanner.RefundRequest orphan : plan.orphans()) {
                this.executeOrphan(state, orphan, network, analyzer, now);
            }
            NodeDispatchOrder first = state.queue().first();
            analyzer.nodeQueue(state.nodeId(), state.queue().size(),
                    first == null ? -1L : first.enqueuedAt());
        }
    }

    /**
     * 执行一条填充任务：按面分组后逐组写入，再把结果回写订单。
     */
    private void executeFill(DispatchState state, ActivePlanner.FillRequest fill, MEStorage network,
            IoAnalyzer analyzer, ActivePlanner planner, long now) {
        Machine machine = null;
        for (Machine candidate : state.onlineMachines()) {
            if (candidate.id().equals(fill.machineId())) {
                machine = candidate;
                break;
            }
        }
        if (machine == null) {
            // 规划到执行之间机器掉线了：这轮不写，下一 tick 重新规划。
            return;
        }
        Map<Direction, Map<AEKey, Long>> byFace = new LinkedHashMap<>();
        for (ActivePlanner.FillLine line : fill.lines()) {
            byFace.computeIfAbsent(line.face(), face -> new LinkedHashMap<>())
                    .merge(line.key(), line.amount(), Long::sum);
        }
        Map<AEKey, IoResult> results = new LinkedHashMap<>();
        boolean moved = false;
        for (Map.Entry<Direction, Map<AEKey, Long>> group : byFace.entrySet()) {
            MEStorage storage = this.ports.storage(machine, group.getKey());
            if (storage == null) {
                for (AEKey key : group.getValue().keySet()) {
                    results.put(key, new IoResult(0L, IoReason.NO_FACE));
                }
                continue;
            }
            Map<AEKey, IoResult> part;
            try {
                part = this.executor.fill(storage, group.getValue());
            } catch (RuntimeException e) {
                this.logFailure(machine, now, e);
                for (AEKey key : group.getValue().keySet()) {
                    results.put(key, new IoResult(0L, IoReason.ERROR));
                }
                continue;
            }
            results.putAll(part);
            for (Map.Entry<AEKey, IoResult> entry : part.entrySet()) {
                IoResult result = entry.getValue();
                analyzer.recordActive(state.nodeId(), result.reason(), now, result.moved(),
                        fill.orderId(), entry.getKey(), machine.id(), group.getKey());
                moved |= result.movedAnything();
                if (result.reason() == IoReason.REJECTED || result.reason() == IoReason.ERROR) {
                    this.ports.invalidate(machine, group.getKey());
                }
            }
        }
        planner.applyFill(state, fill, results, analyzer, now);
        if (moved) {
            planner.activate(state.nodeId(), now);
        }
    }

    /**
     * 执行一次半份退回：逐行退网络，再按退干净的多少更新订单。
     */
    private void executeBatchRefund(DispatchState state, ActivePlanner.RefundRequest refund,
            MEStorage network, IoAnalyzer analyzer, ActivePlanner planner, long now) {
        NodeDispatchOrder order = refund.order();
        NodeDispatchOrder.OpenBatch batch = refund.batch();
        if (batch == null) {
            return;
        }
        Map<AEKey, Long> refunded = new LinkedHashMap<>();
        for (int index = 0; index < batch.size() && index < order.perPortion().size(); index++) {
            long amount = batch.missing(index);
            if (amount <= 0L) {
                continue;
            }
            AEKey key = order.perPortion().get(index).key();
            IoResult result = this.executor.refund(network, key, amount);
            refunded.merge(key, result.moved(), Long::sum);
            analyzer.recordActive(state.nodeId(), result.reason(), now, result.moved(),
                    refund.orderId(), key, refund.machineId(), null);
        }
        planner.applyRefund(state, refund, refunded, analyzer, now);
    }

    /**
     * 执行整单退回：把剩余量尽力还回网络后出队，退不回按 D21 记日志丢弃。
     */
    private void executeOrphan(DispatchState state, ActivePlanner.RefundRequest orphan, MEStorage network,
            IoAnalyzer analyzer, long now) {
        NodeDispatchOrder order = orphan.order();
        long[] remaining = order.remainingAmounts();
        long lost = 0L;
        for (int index = 0; index < remaining.length; index++) {
            long amount = remaining[index];
            if (amount <= 0L) {
                continue;
            }
            AEKey key = order.perPortion().get(index).key();
            IoResult result = this.executor.refund(network, key, amount);
            analyzer.recordActive(state.nodeId(), result.reason(), now, result.moved(),
                    order.id(), key, null, null);
            lost += amount - result.moved();
        }
        state.queue().remove(order.id());
        if (lost > 0L) {
            VoidFactory.LOGGER.warn("AE2 Void Factory: 孤儿订单退回失败，{} 件已按丢弃处理", lost);
        }
    }

    /**
     * 被动段：逐机器问规划器，再执行 Job 并落账。
     */
    private void passive(Factory factory, IoForm form, MEStorage network, IoAnalyzer analyzer,
            long now) {
        List<IoForm.Slot> slots = form.slots();
        if (slots.isEmpty()) {
            this.rounds++;
            return;
        }
        // 机器轮转：每轮从不同的槽位开始，避免固定顺序总是先喂第一台机器。
        int start = (int) Math.floorMod(this.rounds, (long) slots.size());
        for (int index = 0; index < slots.size(); index++) {
            IoForm.Slot slot = slots.get((start + index) % slots.size());
            this.tickMachine(factory, network, analyzer, slot, now);
        }
        this.rounds++;
    }

    /**
     * 跑一台机器的一轮被动搬运。
     */
    private void tickMachine(Factory factory, MEStorage network, IoAnalyzer analyzer, IoForm.Slot slot,
            long now) {
        Machine machine = slot.machine();
        if (!this.ports.loaded(machine)) {
            // 未加载不代表机器没了，下一轮再看
            return;
        }
        PassivePlanner.Plan plan;
        try {
            VariantPick variantPick = this.variants.computeIfAbsent(factory.id(),
                    id -> new VariantPick());
            plan = this.passivePlanner.plan(slot, now, variantPick, network);
        } catch (RuntimeException e) {
            // 规划阶段异常（第三方库存读不出来）：整台机器本轮按未搬动处理，不影响其它机器。
            this.logFailure(machine, now, e);
            return;
        }
        if (plan.isEmpty()) {
            return;
        }
        // 同一条规则可能展开成多个变体：按条目汇总成一行账，否则界面上一条规则会冒出好几行。
        Map<IoForm.Entry, EntryRun> runs = new LinkedHashMap<>();
        for (PassivePlanner.Failure failure : plan.failures()) {
            runs.computeIfAbsent(failure.entry(), entry -> new EntryRun())
                    .merge(failure.reason(), 0L, null);
        }
        for (IoJob job : plan.jobs()) {
            IoResult result;
            try {
                result = this.executor.move(network, job);
            } catch (RuntimeException e) {
                this.logFailure(machine, now, e);
                result = new IoResult(0L, IoReason.ERROR);
            }
            this.invalidateOnFailure(job, result);
            runs.computeIfAbsent(job.entry(), entry -> new EntryRun())
                    .merge(result.reason(), result.moved(), job.face());
        }
        Map<IoKey, Long> fedByRule = new LinkedHashMap<>();
        Map<IoKey, Long> pulledByRule = new LinkedHashMap<>();
        for (Map.Entry<IoForm.Entry, EntryRun> entry : runs.entrySet()) {
            IoForm.Entry formEntry = entry.getKey();
            EntryRun run = entry.getValue();
            analyzer.record(formEntry, run.reason == null ? IoReason.NO_MATERIAL : run.reason,
                    now, run.moved, run.face);
            if (run.moved > 0L) {
                Map<IoKey, Long> target = formEntry.side() == IoSide.INPUT
                        ? fedByRule : pulledByRule;
                target.merge(formEntry.key().rule(), run.moved, Long::sum);
            }
        }
        if (!fedByRule.isEmpty() || !pulledByRule.isEmpty()) {
            analyzer.stats().machine(machine.id()).addMoved(fedByRule, pulledByRule);
        }
    }

    /**
     * 按失败结果失效端口缓存。
     *
     * <p>{@code REJECTED} / {@code ERROR} 只失效那个面；{@code NO_FACE} 失效整台机器的六个面
     * （连面都没选出来，缓存里的面不再可信）；{@code FULL} 不失效——面是对的，只是满了。</p>
     */
    private void invalidateOnFailure(IoJob job, IoResult result) {
        switch (result.reason()) {
            case REJECTED, ERROR -> this.ports.invalidate(job.machine(), job.face());
            case NO_FACE -> this.ports.invalidateMachine(job.machine());
            case RUN, SLEEPING, BUDGET_OUT, NO_MATERIAL, FULL, FACE_CONFIG_INVALID -> {
                // 这些结果说明面本身没问题（配错面是配置问题，不是缓存脏了），不动缓存。
            }
        }
    }

    /**
     * 唤醒一台机器的全部条目：下一轮忽略退避跑一次。
     *
     * @param machineId 机器 id
     */
    public void wake(UUID machineId) {
        this.passivePlanner.wake(machineId);
    }

    /**
     * 丢掉一台机器的排期、日志节流与端口缓存（机器被删时）。
     *
     * @param machineId 机器 id
     */
    public void forget(UUID machineId) {
        this.passivePlanner.forget(machineId);
        this.failureLogAt.remove(machineId);
        this.ports.drop(machineId);
    }

    /**
     * 激活一个节点的产物回收节奏（派单成功、样板变化时）。
     *
     * @param factoryId 工厂 id
     * @param nodeId    节点 id
     * @param now       当前服务端 tick
     */
    public void activateSweep(UUID factoryId, UUID nodeId, long now) {
        this.activePlanner(factoryId).activate(nodeId, now);
    }

    /** 清空全部状态（服务器停止时）。 */
    public void clear() {
        this.passivePlanner.clear();
        this.actives.clear();
        this.variants.clear();
        this.failureLogAt.clear();
        this.knownNodes.clear();
        this.queueSizes.clear();
        this.rounds = 0L;
    }

    /**
     * 取（必要时建）一个工厂的主动规划器。
     */
    private ActivePlanner activePlanner(UUID factoryId) {
        return this.actives.computeIfAbsent(factoryId,
                id -> new ActivePlanner(this.facePick, this.capacityPick, this.ports));
    }

    /**
     * 一台机器本轮搬运异常时记一条 warn，按机器节流。
     */
    private void logFailure(Machine machine, long now, RuntimeException cause) {
        Long last = this.failureLogAt.get(machine.id());
        if (last != null && now - last < FAILURE_LOG_INTERVAL) {
            return;
        }
        this.failureLogAt.put(machine.id(), now);
        VoidFactory.LOGGER.warn("AE2 Void Factory: 机器 {} 本轮 IO 异常，已跳过本轮",
                machine.shortId(), cause);
    }

    /**
     * 一条规则这一轮的汇总：搬了多少、最坏原因、最后一个面。
     */
    private static final class EntryRun {
        private long moved;
        @Nullable
        private IoReason reason;
        @Nullable
        private Direction face;

        void merge(@Nullable IoReason nextReason, long amount, @Nullable Direction usedFace) {
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
