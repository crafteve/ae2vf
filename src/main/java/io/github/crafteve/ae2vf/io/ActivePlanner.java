package io.github.crafteve.ae2vf.io;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.ae2vf.IdleFactory;
import io.github.crafteve.ae2vf.node.NodeDispatchOrder;
import io.github.crafteve.ae2vf.machine.Machine;
import io.github.crafteve.ae2vf.key.AeKeyAdapter;

import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;

import net.minecraft.core.Direction;

/**
 * 主动规划器：决定主动侧这一 tick 把哪些单、按几份、投给哪些机器，以及产物什么时候回收。
 *
 * <p>它只排「这一 tick」：退避用绝对时刻重算，不排未来。订单不绑机器，规划器按
 * {@link DispatchMode} 在节点的在线机器上分配：</p>
 * <ul>
 *   <li>顺序：从第一台开始逐台塞满，塞满换下一台；</li>
 *   <li>轮询：多机平均分——先算目标份数，逐台取 min，一轮分不完再来一轮。</li>
 * </ul>
 *
 * <p>两种模式都遵守同一条：先算 {@link CapacityPick#portionsThatFit}，算出来 0 就不写；
 * 半份优先补齐，补不齐就卡着，<b>不把剩余行挪到别的机器</b>——挪了就是两台机器各拿半份。</p>
 *
 * <p>卡单不退避超时不退款：一直重试、一直忙，直到玩家修好机器。只有必须退料而网络收不回时
 * 才标孤儿，孤儿超时按 D21 丢弃。</p>
 */
public final class ActivePlanner {
    /** 活跃期的回收扫描间隔（tick）。 */
    private static final long ACTIVE_INTERVAL = 5L;
    /** 一次派单 / 一次收获算活跃多久（tick）。 */
    private static final long ACTIVE_WINDOW = 200L;
    /** 空闲退避阶梯（tick）。 */
    private static final long[] IDLE_DELAYS = {20L, 40L, 80L, 128L};
    /** 卡单退避阶梯（tick）：5 / 10 / 20 / 40 / 80，封顶 100。 */
    private static final long[] STALL_DELAYS = {5L, 10L, 20L, 40L, 80L};
    /** 卡单退避封顶（tick）。 */
    private static final long STALL_CAP = 100L;
    /** 被挡后多少 tick 内不再读这台机器的库存。 */
    private static final long BLOCKED_TICKS = 20L;
    /** 孤儿订单等网络恢复的最长时刻（tick），超时按「静默丢弃」处理。 */
    public static final long ORPHAN_TIMEOUT_TICKS = 1200L;
    /** 每节点每 tick 只检查队首这么多单。 */
    public static final int DISPATCH_SCAN_LIMIT = 8;
    /** 固定顺序的六面。 */
    private static final Direction[] FACES = Direction.values();

    private final FacePick facePick;
    private final CapacityPick capacityPick;
    private final PortExecutor ports;

    /** 节点 id → 回收节奏。 */
    private final Map<UUID, Pacing> pacings = new HashMap<>();
    /** 订单 id → 连续卡单次数；不落档，重启后从零开始退避。 */
    private final Map<UUID, Integer> stalls = new HashMap<>();

    /**
     * @param facePick     选面
     * @param capacityPick 容量查询
     * @param ports        端口缓存
     */
    public ActivePlanner(FacePick facePick, CapacityPick capacityPick, PortExecutor ports) {
        this.facePick = facePick;
        this.capacityPick = capacityPick;
        this.ports = ports;
    }

    /**
     * 切回活跃回收节奏；派单成功、抽到东西、机器重新上线时调。
     *
     * @param nodeId 节点 id
     * @param now    当前游戏时间
     */
    public void activate(UUID nodeId, long now) {
        Pacing pacing = this.pacings.computeIfAbsent(nodeId, id -> new Pacing());
        pacing.activate(now);
    }

    /**
     * 这一轮轮到这个节点回收了吗。
     *
     * @param nodeId 节点 id
     * @param now    当前游戏时间
     * @return 到期为 {@code true}
     */
    public boolean sweepDue(UUID nodeId, long now) {
        Pacing pacing = this.pacings.get(nodeId);
        return pacing == null || now >= pacing.nextSweepTick;
    }

    /**
     * 没收获时排下一次扫描：活跃期固定间隔，空闲按阶梯退避。
     *
     * @param nodeId 节点 id
     * @param now    当前游戏时间
     */
    public void idle(UUID nodeId, long now) {
        this.pacings.computeIfAbsent(nodeId, id -> new Pacing()).idle(now);
    }

    /**
     * 丢掉一个节点的节奏（节点销毁时）。
     *
     * @param nodeId 节点 id
     */
    public void forget(UUID nodeId) {
        this.pacings.remove(nodeId);
    }

    /** 清空全部节奏与退避（服务器停止时）。 */
    public void clear() {
        this.pacings.clear();
        this.stalls.clear();
    }

    /**
     * 规划一个节点这一 tick 的投递。
     *
     * @param state    这一 tick 的输入
     * @param analyzer 账房：读经验面与阻挡缓存
     * @param now      当前服务端 tick
     * @return 要执行的填充任务与要退回的量
     */
    public Plan plan(DispatchState state, IoAnalyzer analyzer, long now) {
        Plan plan = new Plan();
        // 一台机器每 tick 最多读一次库存：多个订单共用这一份结果。
        Map<UUID, Map<AEKey, Long>> inventories = new HashMap<>();
        for (NodeDispatchOrder order : state.queue().head(DISPATCH_SCAN_LIMIT)) {
            if (now < order.retryAt()) {
                continue;
            }
            if (order.orphan()) {
                this.planOrphan(plan, state, order, now);
                continue;
            }
            if (!order.openBatches().isEmpty()) {
                this.planBatches(plan, state, order, analyzer, inventories, now);
                continue;
            }
            this.planPortions(plan, state, order, analyzer, inventories, now);
        }
        return plan;
    }

    /**
     * 补齐半份：逐台机器算还差多少，产出补齐任务；机器被删就把没写进去的量退回网络。
     */
    private void planBatches(Plan plan, DispatchState state, NodeDispatchOrder order, IoAnalyzer analyzer,
            Map<UUID, Map<AEKey, Long>> inventories, long now) {
        int fillsBefore = plan.fills.size();
        int refundsBefore = plan.refunds.size();
        for (Map.Entry<UUID, NodeDispatchOrder.OpenBatch> entry : order.openBatches().entrySet()) {
            UUID machineId = entry.getKey();
            NodeDispatchOrder.OpenBatch batch = entry.getValue();
            Machine machine = findOnline(state, machineId);
            if (machine == null) {
                if (!state.memberMachines().contains(machineId)) {
                    // 机器被删：半份里没写进去的量退回网络。先退，退完再决定这一份算不算结束。
                    plan.refunds.add(new RefundRequest(order.id(), machineId, order, batch));
                    continue;
                }
                // 成员机器只是没加载：等它回来，订单与半份都不动。
                continue;
            }
            if (this.blocked(state, machine, analyzer, inventories, now)) {
                continue;
            }
            Map<AEKey, Direction> faces = this.resolveFaces(machine, order, state, analyzer, now);
            if (faces == null) {
                continue;
            }
            List<FillLine> lines = new ArrayList<>(batch.size());
            for (int index = 0; index < batch.size() && index < order.perPortion().size(); index++) {
                long missing = batch.missing(index);
                if (missing > 0L) {
                    AEKey key = order.perPortion().get(index).key();
                    lines.add(new FillLine(key, faces.get(key), missing));
                }
            }
            if (lines.isEmpty()) {
                plan.fills.add(new FillRequest(order.id(), machine.id(), 1L, true, List.of()));
                continue;
            }
            plan.fills.add(new FillRequest(order.id(), machine.id(), 1L, true, lines));
        }
        if (plan.fills.size() == fillsBefore && plan.refunds.size() == refundsBefore) {
            this.stall(state, order, now);
        }
    }

    /**
     * 分配新份数。
     */
    private void planPortions(Plan plan, DispatchState state, NodeDispatchOrder order, IoAnalyzer analyzer,
            Map<UUID, Map<AEKey, Long>> inventories, long now) {
        long remaining = order.remainingPortions();
        if (remaining <= 0L) {
            state.queue().remove(order.id());
            return;
        }
        List<Machine> machines = state.onlineMachines();
        if (machines.isEmpty()) {
            // 机器没加载：等，订单不丢，也不算孤儿。
            this.stall(state, order, now);
            return;
        }
        Map<AEKey, Long> perPortion = perPortion(order);
        List<Candidate> candidates = new ArrayList<>(machines.size());
        for (Machine machine : machines) {
            if (this.blocked(state, machine, analyzer, inventories, now)) {
                continue;
            }
            Map<AEKey, Direction> faces = this.resolveFaces(machine, order, state, analyzer, now);
            if (faces == null) {
                continue;
            }
            long capacity = this.capacityPick.portionsThatFit(machine, perPortion, faces);
            if (capacity <= 0L) {
                continue;
            }
            candidates.add(new Candidate(machine, faces, capacity));
        }
        if (candidates.isEmpty()) {
            this.stall(state, order, now);
            return;
        }
        long before = remaining;
        long placed;
        if (state.dispatch() == DispatchMode.SEQUENTIAL) {
            placed = this.allocateSequential(plan, order, candidates, remaining);
        } else {
            placed = this.allocateRoundRobin(plan, order, candidates, remaining, state);
        }
        if (placed > 0L) {
            this.stalls.remove(order.id());
            // 重新读一遍：轮询模式可能刚写过 nextMachineId，不能拿旧快照盖回去。
            NodeDispatchOrder latest = findOrder(state, order.id());
            if (latest != null) {
                state.queue().update(latest.withRetryAt(0L));
            }
        } else if (before > 0L) {
            this.stall(state, order, now);
        }
    }

    /**
     * 顺序模式：逐台塞满。
     *
     * @return 这一轮排出去的份数
     */
    private long allocateSequential(Plan plan, NodeDispatchOrder order, List<Candidate> candidates,
            long remaining) {
        long left = remaining;
        for (Candidate candidate : candidates) {
            if (left <= 0L) {
                break;
            }
            long count = Math.min(left, candidate.capacity);
            if (count <= 0L) {
                continue;
            }
            plan.fills.add(this.fill(order, candidate, count));
            candidate.capacity -= count;
            left -= count;
        }
        return remaining - left;
    }

    /**
     * 轮询模式：多机平均分。
     *
     * <p>先算「目标 = ceil(剩余份数 / 还有空位的机器数)」，逐台取 min(目标, 可装)；一轮分完
     * 还有剩余就对还有空位的机器再来一轮，直到分完或都满。起点按订单记的 {@code nextMachineId}
     * 轮转，避免固定顺序总是同一台先拿。</p>
     *
     * @return 这一轮排出去的份数
     */
    private long allocateRoundRobin(Plan plan, NodeDispatchOrder order, List<Candidate> candidates,
            long remaining, DispatchState state) {
        List<Candidate> ordered = rotate(candidates, order.nextMachineId());
        long left = remaining;
        UUID lastUsed = null;
        while (left > 0L) {
            long space = 0L;
            int available = 0;
            for (Candidate candidate : ordered) {
                if (candidate.capacity > 0L) {
                    space += candidate.capacity;
                    available++;
                }
            }
            if (space <= 0L || available == 0) {
                break;
            }
            long target = Math.max(1L, (left + available - 1L) / available);
            boolean progressed = false;
            for (Candidate candidate : ordered) {
                if (left <= 0L) {
                    break;
                }
                long count = Math.min(target, Math.min(candidate.capacity, left));
                if (count <= 0L) {
                    continue;
                }
                plan.fills.add(this.fill(order, candidate, count));
                candidate.capacity -= count;
                left -= count;
                lastUsed = candidate.machine.id();
                progressed = true;
            }
            if (!progressed) {
                break;
            }
        }
        if (lastUsed != null) {
            // 起点移到本轮最后一台的后一台：下一轮不再总是同一台先拿，也不在同一台原地打转。
            state.queue().update(order.withNextMachine(nextAfter(ordered, lastUsed)));
        }
        return remaining - left;
    }

    /**
     * 取列表中某台机器的后一台；找不到时回到第一台。
     */
    @Nullable
    private static UUID nextAfter(List<Candidate> candidates, UUID machineId) {
        for (int index = 0; index < candidates.size(); index++) {
            if (candidates.get(index).machine.id().equals(machineId)) {
                return candidates.get((index + 1) % candidates.size()).machine.id();
            }
        }
        return candidates.isEmpty() ? null : candidates.get(0).machine.id();
    }

    /**
     * 按当前起点重排候选机器。
     */
    private static List<Candidate> rotate(List<Candidate> candidates, @Nullable UUID startId) {
        if (startId == null) {
            return candidates;
        }
        int start = 0;
        for (int index = 0; index < candidates.size(); index++) {
            if (candidates.get(index).machine.id().equals(startId)) {
                start = index;
                break;
            }
        }
        List<Candidate> rotated = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            rotated.add(candidates.get((start + index) % candidates.size()));
        }
        return rotated;
    }

    /**
     * 把一条订单的 n 份摊成「一台机器的一批行」。
     */
    private FillRequest fill(NodeDispatchOrder order, Candidate candidate, long count) {
        List<FillLine> lines = new ArrayList<>(order.perPortion().size());
        for (NodeDispatchOrder.Line line : order.perPortion()) {
            lines.add(new FillLine(line.key(), candidate.faces.get(line.key()),
                    line.amount() * count));
        }
        return new FillRequest(order.id(), candidate.machine.id(), count, false, lines);
    }

    /**
     * 回写一次填充的执行结果。
     *
     * <p>正常路径是「全写进去」，但模拟能收、提交少收是允许的：按每行实际写入量算出完整份数，
     * 余下不足一份的部分记成这台机器的半份，下一 tick 优先补齐。</p>
     *
     * @param state    这一 tick 的输入
     * @param fill     刚执行的填充
     * @param results  逐行结果
     * @param analyzer 账房：成功时记经验面
     * @param now      当前服务端 tick
     */
    public void applyFill(DispatchState state, FillRequest fill, Map<AEKey, IoResult> results,
            IoAnalyzer analyzer, long now) {
        NodeDispatchOrder order = findOrder(state, fill.orderId);
        if (order == null) {
            return;
        }
        Map<AEKey, Long> perPortion = perPortion(order);
        Machine machine = machineById(state, fill.machineId);
        long[] moved = new long[order.perPortion().size()];
        boolean anyError = false;
        for (int index = 0; index < order.perPortion().size(); index++) {
            AEKey key = order.perPortion().get(index).key();
            IoResult result = results.get(key);
            if (result == null) {
                continue;
            }
            moved[index] = result.moved();
            if (result.reason() == IoReason.ERROR) {
                anyError = true;
            }
            Direction face = fillFace(fill, key);
            if (machine != null && result.moved() > 0L && face != null) {
                analyzer.recordMachineFace(machine, key, face, now);
            }
            if (result.reason() == IoReason.REJECTED && machine != null) {
                analyzer.clearMachineFace(machine, key);
            }
        }
        if (fill.topUp) {
            this.applyTopUp(state, order, fill, moved, anyError, analyzer, now);
        } else {
            this.applyPortions(state, order, fill, moved, anyError);
        }
    }

    /**
     * 新份数的回写：算完整份数，余量记成半份。
     */
    private void applyPortions(DispatchState state, NodeDispatchOrder order, FillRequest fill, long[] moved,
            boolean anyError) {
        long complete = fill.portions;
        for (int index = 0; index < order.perPortion().size(); index++) {
            complete = Math.min(complete, moved[index] / order.perPortion().get(index).amount());
        }
        long done = order.donePortions() + complete;
        NodeDispatchOrder next = order.withDone(done);
        // 提交少于请求：把不足一份的那部分记成这台机器的半份，下一 tick 只补这一台。
        long[] missing = new long[order.perPortion().size()];
        boolean partial = false;
        for (int index = 0; index < order.perPortion().size(); index++) {
            long leftover = moved[index] - complete * order.perPortion().get(index).amount();
            long gap = order.perPortion().get(index).amount() - leftover;
            if (gap > 0L && complete < fill.portions) {
                missing[index] = gap;
                partial = true;
            }
        }
        if (partial && !anyError) {
            next = next.withOpenBatch(fill.machineId, missing);
        }
        state.queue().update(next);
        if (next.finished()) {
            state.queue().remove(next.id());
        }
    }

    /**
     * 半份补齐的回写：全补齐才算一份，否则更新还差多少。
     */
    private void applyTopUp(DispatchState state, NodeDispatchOrder order, FillRequest fill, long[] moved,
            boolean anyError, IoAnalyzer analyzer, long now) {
        NodeDispatchOrder.OpenBatch batch = order.openBatches().get(fill.machineId);
        if (batch == null) {
            return;
        }
        long[] missing = new long[batch.size()];
        boolean complete = true;
        for (int index = 0; index < batch.size(); index++) {
            missing[index] = Math.max(0L, batch.missing(index) - moved[index]);
            if (missing[index] > 0L) {
                complete = false;
            }
        }
        NodeDispatchOrder next = complete
                ? order.completeBatch(fill.machineId)
                : order.withOpenBatch(fill.machineId, missing);
        state.queue().update(next);
    }

    /**
     * 处理一次退回结果：退干净才算这一份结束，否则留着下次再退，超时才丢弃。
     *
     * @param state     这一 tick 的输入
     * @param refund    退回请求
     * @param refunded  逐行实际退回量（key → 数量）
     * @param analyzer  账房
     * @param now       当前服务端 tick
     */
    public void applyRefund(DispatchState state, RefundRequest refund, Map<AEKey, Long> refunded,
            IoAnalyzer analyzer, long now) {
        NodeDispatchOrder order = findOrder(state, refund.orderId);
        if (order == null) {
            return;
        }
        NodeDispatchOrder.OpenBatch batch = order.openBatches().get(refund.machineId);
        if (batch == null) {
            return;
        }
        long[] missing = new long[batch.size()];
        long left = 0L;
        for (int index = 0; index < batch.size() && index < order.perPortion().size(); index++) {
            AEKey key = order.perPortion().get(index).key();
            long back = refunded.getOrDefault(key, 0L);
            missing[index] = Math.max(0L, batch.missing(index) - back);
            left += missing[index];
        }
        if (left <= 0L) {
            // 没写进去的量全部回到网络：这一份按结束算，已写进去的部分随方块消失，不重复退。
            state.queue().update(order.completeBatch(refund.machineId));
            return;
        }
        NodeDispatchOrder next = order.withOpenBatch(refund.machineId, missing);
        if (!order.orphan()) {
            next = next.asOrphan(now);
        }
        if (order.orphan() && now - order.orphanedAt() > ORPHAN_TIMEOUT_TICKS) {
            // 网络长时间收不回：剩余量按 D21 丢弃，只记日志，不生成掉落物。
            IdleFactory.LOGGER.warn("Idle Factory: 半份退回网络超时，{} 件已按丢弃处理",
                    left);
            next = order.completeBatch(refund.machineId);
        }
        state.queue().update(next);
    }

    /**
     * 孤儿订单：网络恢复后把剩余量退回并出队；超时就丢弃。
     */
    private void planOrphan(Plan plan, DispatchState state, NodeDispatchOrder order, long now) {
        plan.orphans.add(new RefundRequest(order.id(), null, order, null));
    }

    /**
     * 把面解析成「资源 → 面」：配置面（节点输入规则）→ 经验面 → 逐面探。
     *
     * @return 每行都有面时返回映射；有一行拿不到面时返回 {@code null}
     */
    @Nullable
    private Map<AEKey, Direction> resolveFaces(Machine machine, NodeDispatchOrder order,
            DispatchState state, IoAnalyzer analyzer, long now) {
        Map<AEKey, Direction> faces = new LinkedHashMap<>();
        for (NodeDispatchOrder.Line line : order.perPortion()) {
            Direction hint = analyzer.machineFaceHint(machine, line.key(), now);
            FacePick.Result pick = this.facePick.pick(machine, line.key(), IoSide.INPUT,
                    state.inputFace(line.key()), hint);
            if (!pick.picked()) {
                return null;
            }
            faces.put(line.key(), pick.face());
        }
        return faces;
    }

    /**
     * 这台机器这一刻是否被阻挡；被挡就记退避，期间不再读库存。
     */
    private boolean blocked(DispatchState state, Machine machine, IoAnalyzer analyzer,
            Map<UUID, Map<AEKey, Long>> inventories, long now) {
        if (!state.blocking().isOn()) {
            return false;
        }
        if (analyzer.machineBlocked(machine, now)) {
            return true;
        }
        Map<AEKey, Long> inventory = inventories.get(machine.id());
        if (inventory == null) {
            inventory = this.readInventory(machine);
            inventories.put(machine.id(), inventory);
        }
        if (state.blockingHit(inventory)) {
            analyzer.markMachineBlocked(machine, now + BLOCKED_TICKS);
            return true;
        }
        return false;
    }

    /**
     * 读一台机器六面的库存并集。
     */
    private Map<AEKey, Long> readInventory(Machine machine) {
        Map<AEKey, Long> merged = new LinkedHashMap<>();
        for (Direction face : FACES) {
            MEStorage storage = this.ports.storage(machine, face);
            if (storage == null) {
                continue;
            }
            Map<AEKey, Long> part;
            try {
                part = AeKeyAdapter.snapshot(storage);
            } catch (RuntimeException e) {
                // 某一面读不出来：只失效这一面，阻挡判定按「没读到」处理。
                this.ports.invalidate(machine, face);
                continue;
            }
            for (Map.Entry<AEKey, Long> entry : part.entrySet()) {
                merged.merge(entry.getKey(), entry.getValue(), Long::sum);
            }
        }
        return merged;
    }

    /**
     * 记一次卡单并按阶梯退避。
     */
    private void stall(DispatchState state, NodeDispatchOrder order, long now) {
        int count = this.stalls.merge(order.id(), 1, Integer::sum);
        int step = Math.min(count - 1, STALL_DELAYS.length - 1);
        long delay = Math.min(STALL_DELAYS[step], STALL_CAP);
        state.queue().update(order.withRetryAt(now + delay));
    }

    @Nullable
    private static NodeDispatchOrder findOrder(DispatchState state, UUID orderId) {
        for (NodeDispatchOrder order : state.queue().all()) {
            if (order.id().equals(orderId)) {
                return order;
            }
        }
        return null;
    }

    @Nullable
    private static Machine machineById(DispatchState state, UUID machineId) {
        for (Machine machine : state.onlineMachines()) {
            if (machine.id().equals(machineId)) {
                return machine;
            }
        }
        return null;
    }

    @Nullable
    private static Machine findOnline(DispatchState state, UUID machineId) {
        return machineById(state, machineId);
    }

    /**
     * 订单的「每份用量」映射，给容量查询用。
     */
    private static Map<AEKey, Long> perPortion(NodeDispatchOrder order) {
        Map<AEKey, Long> map = new LinkedHashMap<>();
        for (NodeDispatchOrder.Line line : order.perPortion()) {
            map.put(line.key(), line.amount());
        }
        return map;
    }

    /**
     * 取填充里某一行的面。
     */
    private static Direction fillFace(FillRequest fill, AEKey key) {
        for (FillLine line : fill.lines) {
            if (line.key.equals(key)) {
                return line.face;
            }
        }
        return null;
    }

    /**
     * 一台候选机器：面与还能装的份数。
     */
    private static final class Candidate {
        private final Machine machine;
        private final Map<AEKey, Direction> faces;
        private long capacity;

        Candidate(Machine machine, Map<AEKey, Direction> faces, long capacity) {
            this.machine = machine;
            this.faces = faces;
            this.capacity = capacity;
        }
    }

    /**
     * 一个节点的回收节奏：活跃窗口 + 空闲阶梯。
     */
    private static final class Pacing {
        private long nextSweepTick;
        private long activeUntil;
        private int idleStep;

        void activate(long now) {
            this.activeUntil = now + ACTIVE_WINDOW;
            this.idleStep = 0;
            this.nextSweepTick = now + ACTIVE_INTERVAL;
        }

        void idle(long now) {
            if (now < this.activeUntil) {
                this.nextSweepTick = now + ACTIVE_INTERVAL;
                return;
            }
            int step = Math.min(this.idleStep, IDLE_DELAYS.length - 1);
            this.nextSweepTick = now + IDLE_DELAYS[step];
            this.idleStep = Math.min(this.idleStep + 1, IDLE_DELAYS.length - 1);
        }
    }

    /**
     * 一条填充任务：把某一单的 n 份写到一台机器上。
     *
     * @param orderId   订单 id
     * @param machineId 目标机器
     * @param portions  这一批的份数；补齐半份时为 1
     * @param topUp     是否在补半份
     * @param lines     逐行（资源、面、要写的量）
     */
    public record FillRequest(UUID orderId, UUID machineId, long portions, boolean topUp,
            List<FillLine> lines) {
        public FillRequest {
            lines = List.copyOf(lines);
        }
    }

    /**
     * 填充里的一行。
     *
     * @param key    资源
     * @param face   面
     * @param amount 要写的量
     */
    public record FillLine(AEKey key, Direction face, long amount) {
    }

    /**
     * 一次退回请求：半份机器被删，或者整单要退回网络。
     *
     * @param orderId   订单 id
     * @param machineId 机器 id；整单退回时为 {@code null}
     * @param order     订单快照
     * @param batch     半份；整单退回时为 {@code null}
     */
    public record RefundRequest(UUID orderId, @Nullable UUID machineId, NodeDispatchOrder order,
            @Nullable NodeDispatchOrder.OpenBatch batch) {
    }

    /**
     * 一 tick 的规划结果。
     */
    public static final class Plan {
        private final List<FillRequest> fills = new ArrayList<>();
        private final List<RefundRequest> refunds = new ArrayList<>();
        private final List<RefundRequest> orphans = new ArrayList<>();

        /** 填充任务。 */
        public List<FillRequest> fills() {
            return this.fills;
        }

        /** 半份退回请求。 */
        public List<RefundRequest> refunds() {
            return this.refunds;
        }

        /** 整单退回请求。 */
        public List<RefundRequest> orphans() {
            return this.orphans;
        }
    }
}
