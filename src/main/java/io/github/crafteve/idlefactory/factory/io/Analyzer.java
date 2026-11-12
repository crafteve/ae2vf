package io.github.crafteve.idlefactory.factory.io;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.machine.Machine;

import appeng.api.stacks.AEKey;

import net.minecraft.core.Direction;

/**
 * 一个工厂的账房：账本、结论、速率与经验面四件套的唯一维护者。
 *
 * <p>只记不判：Runner 报结果，Planner 与界面读。它不碰世界、不做决策、不写存档、不起线程；
 * 时间永远是参数，淘汰逻辑可以脱离游戏单独验证。</p>
 *
 * <p>三个抽屉：</p>
 * <ul>
 *   <li>{@link Ledger}：变化序列，会滚，每个目标 8 行；</li>
 *   <li>{@link Conclusion}：被动侧每条规则的摘要，不滚；</li>
 *   <li>{@link Stats}：每秒入 / 出速率窗口。</li>
 * </ul>
 *
 * <p>主动侧另有 {@link NodeConclusion}（每节点）与 {@link MachineConclusion}（每机器），
 * 外加 {@link FaceHint} 经验面：被动按条目存，主动按「机器 + 资源」存。</p>
 *
 * <p>生命周期：条目离开表单 → 账本与结论随条目释放；机器删除 → 清经验面、机器结论与统计；
 * 节点删除 → 清节点账本与结论。淘汰与清理都在本类内部，外部只负责「到点了叫它一声」。</p>
 */
public final class Analyzer {
    /** 每个目标保留的账本行数。 */
    public static final int LEDGER_CAPACITY = 8;
    /** 速率结算间隔（tick）：每秒把窗口结算成速率。 */
    private static final int SAMPLE_INTERVAL = 20;
    /** 每 tick 补扫多少台机器的经验面。 */
    private static final int SWEEP_BATCH = 16;
    /** 主动经验面的过期时间（tick）。 */
    public static final long FACE_HINT_TTL_TICKS = 1200L;
    /** 每台机器的经验面条数栅栏：正常玩法摸不到，只防「同一台机器被灌进上千个资源」。 */
    public static final int FACE_HINT_FENCE = 512;

    /** 按机器、按规则的搬运速率。 */
    private final Stats stats = new Stats();
    /** 节点 id → 主动侧账本。 */
    private final Map<UUID, Ledger> nodeLedgers = new HashMap<>();
    /** 节点 id → 主动侧结论。 */
    private final Map<UUID, NodeConclusion> nodeConclusions = new HashMap<>();
    /** 机器 id → (资源 → 经验面)。 */
    private final Map<UUID, Map<AEKey, FaceHint>> machineFaceHints = new HashMap<>();
    /** 机器 id → 机器结论。 */
    private final Map<UUID, MachineConclusion> machineConclusions = new HashMap<>();
    /** 经验面补扫的机器轮转表；每次 retain 后刷新。 */
    private UUID[] machineRing = new UUID[0];
    /** 轮转游标。 */
    private int ringCursor;
    /** 距上次速率结算过了多少 tick。 */
    private int sinceSample;

    /**
     * 记一次被动执行结果：同时写账本与结论。
     *
     * @param entry  执行条目
     * @param reason 原因
     * @param now    当前服务端 tick
     * @param amount 本次搬动的数量
     * @param face   本次用的面
     * @return 这次结果是否搬到了东西（方便调用方累计速率）
     */
    public boolean record(Form.Entry entry, Reason reason, long now, long amount,
            @Nullable Direction face) {
        entry.ledger().append(reason, now, amount, face, null, null, null);
        entry.conclusion().record(reason, now, amount, face);
        return amount > 0L;
    }

    /**
     * 记一次主动执行结果：写节点账本，并累加已送达量。
     *
     * @param nodeId    节点 id
     * @param reason    原因
     * @param now       当前服务端 tick
     * @param amount    本次搬动的数量
     * @param orderId   订单 id；回收等非订单动作为 {@code null}
     * @param key       资源；回收时为空
     * @param machineId 目标机器；{@code null} 表示没定机器
     * @param face      用的面
     */
    public void recordActive(UUID nodeId, Reason reason, long now, long amount,
            @Nullable UUID orderId, @Nullable AEKey key, @Nullable UUID machineId,
            @Nullable Direction face) {
        this.nodeLedger(nodeId).append(reason, now, amount, face, orderId, key, machineId);
        NodeConclusion conclusion = this.nodeConclusion(nodeId);
        if (reason != Reason.RUN) {
            // 只记「最近一次失败原因」：成功不该盖掉玩家要看的诊断。
            conclusion.lastReason = reason;
        }
        conclusion.deliveredTotal += amount;
        if (amount > 0L) {
            // 有进展：清掉退避原料，下一轮立刻可试。
            conclusion.lastSuccessAt = now;
        }
    }

    /**
     * 更新节点的队列状态（单数与队首时刻）。
     *
     * @param nodeId     节点 id
     * @param count      队列单数
     * @param oldestAt   队首入队时刻；队列空时传 {@code -1}
     */
    public void nodeQueue(UUID nodeId, int count, long oldestAt) {
        NodeConclusion conclusion = this.nodeConclusion(nodeId);
        conclusion.pendingCount = count;
        conclusion.pendingOldestAt = count == 0 ? -1L : oldestAt;
    }

    /** 按机器、按规则的搬运速率。 */
    public Stats stats() {
        return this.stats;
    }

    /**
     * 每工厂每 tick 调一次：自己数间隔结算速率，并轮转补扫经验面的过期条目。
     *
     * @param now 当前服务端 tick
     */
    public void tick(long now) {
        this.sinceSample++;
        if (this.sinceSample >= SAMPLE_INTERVAL) {
            this.sinceSample = 0;
            this.stats.sample();
        }
        this.sweepFaceHints(now);
    }

    /**
     * 只保留这些机器的统计、经验面与机器结论（机器删除后不再攒条目）。
     *
     * @param keep 仍然存在的机器 id
     */
    public void retainMachines(Set<UUID> keep) {
        this.stats.retain(keep);
        this.machineFaceHints.keySet().removeIf(id -> !keep.contains(id));
        this.machineConclusions.keySet().removeIf(id -> !keep.contains(id));
        this.machineRing = keep.toArray(new UUID[0]);
        if (this.ringCursor >= this.machineRing.length) {
            this.ringCursor = 0;
        }
    }

    /**
     * 只保留这些节点的账本与结论（节点删除后不再攒条目）。
     *
     * @param keep 仍然存在的节点 id
     */
    public void retainNodes(Set<UUID> keep) {
        this.nodeLedgers.keySet().removeIf(id -> !keep.contains(id));
        this.nodeConclusions.keySet().removeIf(id -> !keep.contains(id));
    }

    /**
     * 主动侧经验面：上一次真搬通的面；读的时候顺手刷活跃时刻并惰性清过期。
     *
     * @param machine 机器
     * @param key     资源（按去组件比较，模糊变体共用一条）
     * @param now     当前服务端 tick
     * @return 经验面；没有或已过期时为 {@code null}
     */
    @Nullable
    public Direction machineFaceHint(Machine machine, AEKey key, long now) {
        Map<AEKey, FaceHint> hints = this.machineFaceHints.get(machine.id());
        if (hints == null) {
            return null;
        }
        AEKey identity = key.dropSecondary();
        FaceHint hint = hints.get(identity);
        if (hint == null) {
            return null;
        }
        if (now - hint.lastUsedTick() > FACE_HINT_TTL_TICKS) {
            hints.remove(identity);
            return null;
        }
        hints.put(identity, hint.used(now));
        return hint.face();
    }

    /**
     * 记一次主动搬通的面。
     *
     * @param machine 机器
     * @param key     资源
     * @param face    本次用的面
     * @param now     当前服务端 tick
     */
    public void recordMachineFace(Machine machine, AEKey key, Direction face, long now) {
        Map<AEKey, FaceHint> hints = this.machineFaceHints.computeIfAbsent(machine.id(),
                id -> new HashMap<>());
        AEKey identity = key.dropSecondary();
        hints.put(identity, new FaceHint(face, now));
        if (hints.size() > FACE_HINT_FENCE) {
            this.evictOldest(machine, hints);
        }
    }

    /**
     * 清掉某个资源的主动经验面（只有它自己的那条失效）。
     *
     * @param machine 机器
     * @param key     资源
     */
    public void clearMachineFace(Machine machine, AEKey key) {
        Map<AEKey, FaceHint> hints = this.machineFaceHints.get(machine.id());
        if (hints != null) {
            hints.remove(key.dropSecondary());
        }
    }

    /**
     * 清掉一台机器的全部经验面；观测代数变化时用（方块换了，面全不算数）。
     *
     * @param machine 机器
     */
    public void clearMachineFaces(Machine machine) {
        this.machineFaceHints.remove(machine.id());
    }

    /**
     * 这台机器是否处在阻挡退避里。
     *
     * @param machine 机器
     * @param now     当前服务端 tick
     * @return 还在退避里为 {@code true}
     */
    public boolean machineBlocked(Machine machine, long now) {
        MachineConclusion conclusion = this.machineConclusions.get(machine.id());
        return conclusion != null && now < conclusion.blockedUntil();
    }

    /**
     * 记「这台机器被挡」，退避一段时间内不再读它的库存。
     *
     * @param machine 机器
     * @param until   退避到期 tick
     */
    public void markMachineBlocked(Machine machine, long until) {
        this.machineConclusions.computeIfAbsent(machine.id(), id -> new MachineConclusion(until))
                .block(until);
    }

    /**
     * 清掉一台机器的阻挡缓存（机器删除 / 观测代数变化）。
     *
     * @param machine 机器
     */
    public void clearMachineBlocked(Machine machine) {
        this.machineConclusions.remove(machine.id());
    }

    /** 一个节点的主动侧结论。 */
    public NodeConclusion nodeConclusion(UUID nodeId) {
        return this.nodeConclusions.computeIfAbsent(nodeId, id -> new NodeConclusion());
    }

    /** 一个节点的主动侧账本。 */
    public Ledger nodeLedger(UUID nodeId) {
        return this.nodeLedgers.computeIfAbsent(nodeId, id -> new Ledger());
    }

    private void evictOldest(Machine machine, Map<AEKey, FaceHint> hints) {
        AEKey oldestKey = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<AEKey, FaceHint> entry : hints.entrySet()) {
            if (entry.getValue().lastUsedTick() < oldest) {
                oldest = entry.getValue().lastUsedTick();
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            hints.remove(oldestKey);
            IdleFactory.LOGGER.debug("Idle Factory: 机器 {} 的经验面超过 {} 条，淘汰最久未用的一条",
                    machine.shortId(), FACE_HINT_FENCE);
        }
    }

    private void sweepFaceHints(long now) {
        int size = this.machineRing.length;
        if (size == 0) {
            return;
        }
        int budget = Math.min(SWEEP_BATCH, size);
        for (int step = 0; step < budget; step++) {
            UUID machineId = this.machineRing[this.ringCursor % size];
            this.ringCursor = (this.ringCursor + 1) % size;
            Map<AEKey, FaceHint> hints = this.machineFaceHints.get(machineId);
            if (hints == null) {
                continue;
            }
            hints.values().removeIf(hint -> now - hint.lastUsedTick() > FACE_HINT_TTL_TICKS);
            if (hints.isEmpty()) {
                this.machineFaceHints.remove(machineId);
            }
        }
    }

    /**
     * 主动经验面的一条：面 + 最近使用时刻。
     *
     * @param face         面
     * @param lastUsedTick 最近一次搬通 / 命中的 tick
     */
    public record FaceHint(Direction face, long lastUsedTick) {
        FaceHint used(long now) {
            return new FaceHint(this.face, now);
        }
    }

    /** 主动侧机器结论：阻挡缓存与退避时刻。 */
    public static final class MachineConclusion {
        /** 阻挡退避到期 tick；{@code 0} 表示没有退避。 */
        private long blockedUntil;

        MachineConclusion(long blockedUntil) {
            this.blockedUntil = blockedUntil;
        }

        /** 阻挡退避到期 tick。 */
        public long blockedUntil() {
            return this.blockedUntil;
        }

        void block(long until) {
            this.blockedUntil = Math.max(this.blockedUntil, until);
        }
    }

    /** 主动侧节点结论：队列深度、最近失败原因与累计送达量。 */
    public static final class NodeConclusion {
        private int pendingCount;
        private long pendingOldestAt = -1L;
        @Nullable
        private Reason lastReason;
        private long deliveredTotal;
        private long lastSuccessAt = -1L;

        /** 队列里还有几单。 */
        public int pendingCount() {
            return this.pendingCount;
        }

        /** 队首入队的时刻；没有单时为 {@code -1}。 */
        public long pendingOldestAt() {
            return this.pendingOldestAt;
        }

        /** 最近一次失败原因；没失败过为 {@code null}。 */
        @Nullable
        public Reason lastReason() {
            return this.lastReason;
        }

        /** 累计送达量（按实际写入累加）。 */
        public long deliveredTotal() {
            return this.deliveredTotal;
        }

        /** 最近一次有进展的时刻；没有为 {@code -1}。 */
        public long lastSuccessAt() {
            return this.lastSuccessAt;
        }
    }

    /**
     * 一个目标的账本：环形有界的「变化序列」。
     *
     * <p>它记的是变化不是时间线：连续同原因、同面、同订单 / 资源的结果合并成一行（次数与累计
     * 数量累加），任一要素一变就开新行。被动行的订单 / 资源 / 机器三项为空——Entry 身份已经
     * 唯一确定它们，不重复存；主动行才带。</p>
     */
    public static final class Ledger {
        /**
         * 一行账。
         *
         * @param reason    结果原因
         * @param firstTick 这一串第一次发生的时刻
         * @param lastTick  这一串最近一次发生的时刻
         * @param count     这一串发生了几次
         * @param amount    这一串累计搬动的数量
         * @param face      用的面；{@code null} 表示不限面或没定面
         * @param orderId   主动侧订单；被动侧为 {@code null}
         * @param key       主动侧资源；被动侧为 {@code null}
         * @param machineId 主动侧机器；被动侧为 {@code null}
         */
        public record Row(Reason reason, long firstTick, long lastTick, int count, long amount,
                @Nullable Direction face, @Nullable UUID orderId, @Nullable AEKey key,
                @Nullable UUID machineId) {
        }

        private final Row[] rows = new Row[LEDGER_CAPACITY];
        private int head;
        private int size;

        /**
         * 追加一次结果；与最新一行同原因同面同目标时合并。
         *
         * @param reason    原因
         * @param tick      发生时刻
         * @param amount    本次搬动的数量
         * @param face      用的面
         * @param orderId   主动侧订单；被动侧传 {@code null}
         * @param key       主动侧资源；被动侧传 {@code null}
         * @param machineId 主动侧机器；被动侧传 {@code null}
         */
        public void append(Reason reason, long tick, long amount, @Nullable Direction face,
                @Nullable UUID orderId, @Nullable AEKey key, @Nullable UUID machineId) {
            Row newest = this.current();
            if (newest != null && newest.reason() == reason && Objects.equals(newest.face(), face)
                    && Objects.equals(newest.orderId(), orderId)
                    && Objects.equals(newest.key(), key)
                    && Objects.equals(newest.machineId(), machineId)) {
                this.rows[this.newestIndex()] = new Row(reason, newest.firstTick(), tick,
                        newest.count() + 1, newest.amount() + amount, face, orderId, key,
                        machineId);
                return;
            }
            this.rows[this.head] = new Row(reason, tick, tick, 1, amount, face, orderId, key,
                    machineId);
            this.head = (this.head + 1) % LEDGER_CAPACITY;
            if (this.size < LEDGER_CAPACITY) {
                this.size++;
            }
        }

        /**
         * 最新一行（当前结论）。
         *
         * @return 最新一行；还没记过时为 {@code null}
         */
        @Nullable
        public Row current() {
            return this.size == 0 ? null : this.rows[this.newestIndex()];
        }

        /**
         * 全部行，从新到旧。
         *
         * <p>目前没有任何界面读者：视图只读 {@link #current()}。这里提前留出明细口，等 UI 细化。
         * </p>
         *
         * @return 只读列表
         */
        public List<Row> rows() {
            List<Row> snapshot = new ArrayList<>(this.size);
            for (int index = 0; index < this.size; index++) {
                snapshot.add(this.rows[this.indexFromNewest(index)]);
            }
            return List.copyOf(snapshot);
        }

        private int newestIndex() {
            return Math.floorMod(this.head - 1, LEDGER_CAPACITY);
        }

        private int indexFromNewest(int offset) {
            return Math.floorMod(this.newestIndex() - offset, LEDGER_CAPACITY);
        }
    }

    /**
     * 一个执行条目的历史结论：账本压成的一组数。
     *
     * <p>这是值对象，只给规划器与界面读；账本是明细（会滚掉），这里是摘要（不滚）。
     * 命名后缀语义固定：{@code At} 是绝对时刻，{@code Ticks} 是连续时长，
     * {@code Streak} 是连续次数，{@code Counts} 是累计次数，{@code Hint} 是经验值。</p>
     */
    public static final class Conclusion {
        private long firstAttemptAt = -1L;
        private long lastAttemptAt = -1L;
        private long lastSuccessAt = -1L;
        /** 最近一次搬动的时刻；{@code -1} 表示还没搬动过。 */
        private long idleSince = -1L;
        /** 连续被拒次数；不是拒收时清零。 */
        private int rejectStreak;
        /** 各原因累计次数。 */
        private final Map<Reason, Integer> reasonCounts = new EnumMap<>(Reason.class);
        /** 经验面：搬通过的面，写入失败时就地清除。 */
        @Nullable
        private Direction faceHint;

        /**
         * 记一次尝试。
         *
         * @param reason 原因
         * @param now    当前服务端 tick
         * @param amount 本次搬动的数量
         * @param face   本次用的面
         */
        public void record(Reason reason, long now, long amount, @Nullable Direction face) {
            if (this.firstAttemptAt < 0L) {
                this.firstAttemptAt = now;
            }
            this.lastAttemptAt = now;
            this.reasonCounts.merge(reason, 1, Integer::sum);
            if (amount > 0L) {
                this.lastSuccessAt = now;
                this.idleSince = now;
                this.rejectStreak = 0;
                if (face != null) {
                    this.faceHint = face;
                }
            } else {
                if (reason == Reason.REJECTED) {
                    this.rejectStreak++;
                    this.clearFaceHint();
                } else {
                    this.rejectStreak = 0;
                }
            }
        }

        /** 第一次尝试的时刻；没试过为 {@code -1}。 */
        public long firstAttemptAt() {
            return this.firstAttemptAt;
        }

        /** 最近一次尝试的时刻；没试过为 {@code -1}。 */
        public long lastAttemptAt() {
            return this.lastAttemptAt;
        }

        /** 最近一次成功搬动的时刻；没成功过为 {@code -1}。 */
        public long lastSuccessAt() {
            return this.lastSuccessAt;
        }

        /**
         * 连续空转的时长。
         *
         * <p>没有成功记录时从第一次尝试算起；一次都没试过时为 {@code 0}。</p>
         *
         * @param now 当前服务端 tick
         * @return 连续没搬动的 tick 数
         */
        public long idleTicks(long now) {
            if (this.idleSince >= 0L) {
                return now - this.idleSince;
            }
            return this.firstAttemptAt < 0L ? 0L : now - this.firstAttemptAt;
        }

        /** 连续被拒次数。 */
        public int rejectStreak() {
            return this.rejectStreak;
        }

        /**
         * 某个原因累计发生次数。
         *
         * @param reason 原因
         * @return 次数
         */
        public int countOf(Reason reason) {
            return this.reasonCounts.getOrDefault(reason, 0);
        }

        /** 经验面；没有为 {@code null}。 */
        @Nullable
        public Direction faceHint() {
            return this.faceHint;
        }

        /** 清掉经验面（写入失败时调用）。 */
        public void clearFaceHint() {
            this.faceHint = null;
        }
    }

    /**
     * 一个工厂的搬运速率统计：每台机器一份「按规则的一秒窗口与速率」。
     *
     * <p>纯运行时，不写存档。原因、上次成功、连续空转都不在这里——那些是账本与结论的职责，
     * 本类只回答「每秒搬了多少」这一个界面问题。</p>
     */
    public static final class Stats {
        /** 每台机器的统计。 */
        private final Map<UUID, MachineStats> machines = new HashMap<>();

        /**
         * 取一台机器的统计，没有就建。
         *
         * @param machineId 机器 id
         * @return 统计
         */
        public MachineStats machine(UUID machineId) {
            return this.machines.computeIfAbsent(machineId, id -> new MachineStats());
        }

        /**
         * 取一台机器的统计；没有时返回 {@code null}，不创建空项。
         *
         * @param machineId 机器 id
         * @return 统计或 {@code null}
         */
        public MachineStats machineOrNull(UUID machineId) {
            return this.machines.get(machineId);
        }

        /**
         * 只保留这些机器的统计（机器删除后不再攒条目）。
         *
         * @param keep 仍然存在的机器 id
         */
        public void retain(Set<UUID> keep) {
            this.machines.keySet().removeIf(id -> !keep.contains(id));
        }

        /** 采样一次速率。由 {@link Analyzer#tick(long)} 每秒调一次。 */
        void sample() {
            for (MachineStats stats : this.machines.values()) {
                stats.sample();
            }
        }

        /** 一台机器的搬运速率。 */
        public static final class MachineStats {
            private final Map<RuleKey, Long> inRuleWindow = new HashMap<>();
            private final Map<RuleKey, Long> outRuleWindow = new HashMap<>();
            private final Map<RuleKey, Long> inRuleRate = new HashMap<>();
            private final Map<RuleKey, Long> outRuleRate = new HashMap<>();

            /**
             * 记一次成功搬运（进窗口，采样时结算成速率）。
             *
             * @param fedByRule    按规则拆分的补料量
             * @param pulledByRule 按规则拆分的抽取量
             */
            public void addMoved(Map<RuleKey, Long> fedByRule, Map<RuleKey, Long> pulledByRule) {
                for (Map.Entry<RuleKey, Long> entry : fedByRule.entrySet()) {
                    this.inRuleWindow.merge(entry.getKey(), entry.getValue(), Long::sum);
                }
                for (Map.Entry<RuleKey, Long> entry : pulledByRule.entrySet()) {
                    this.outRuleWindow.merge(entry.getKey(), entry.getValue(), Long::sum);
                }
            }

            /**
             * 一条输入规则上一秒的速率。
             *
             * @param key 规则
             * @return 数量/秒
             */
            public long rateIn(RuleKey key) {
                Long value = this.inRuleRate.get(key);
                return value == null ? 0L : value;
            }

            /**
             * 一条输出规则上一秒的速率。
             *
             * @param key 规则
             * @return 数量/秒
             */
            public long rateOut(RuleKey key) {
                Long value = this.outRuleRate.get(key);
                return value == null ? 0L : value;
            }

            private void sample() {
                this.inRuleRate.clear();
                this.inRuleRate.putAll(this.inRuleWindow);
                this.inRuleWindow.clear();
                this.outRuleRate.clear();
                this.outRuleRate.putAll(this.outRuleWindow);
                this.outRuleWindow.clear();
            }
        }
    }
}
