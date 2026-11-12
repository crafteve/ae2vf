package io.github.crafteve.idlefactory.factory.io;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Direction;

/**
 * 一个工厂的执行账房：账本、结论、速率统计三件套的唯一维护者。
 *
 * <p>它只记不判：执行器报结果，规划器与界面读。账本是明细（会滚掉），结论是摘要（不滚），
 * 统计只回答「每秒搬了多少」。退避与择时由规划器算，本类只给原料。</p>
 *
 * <p>账本与结论的实例挂在 {@link Form.Entry} 上，条目离开表单即随之释放；本类负责写入，
 * 并按工厂维护速率窗口。不读世界、不写存档、不起线程。</p>
 */
public final class Analyzer {
    /** 每个目标保留的账本行数。 */
    public static final int LEDGER_CAPACITY = 8;
    /** 速率结算间隔（tick）：每秒把窗口结算成速率。 */
    private static final int SAMPLE_INTERVAL = 20;

    /** 按机器、按规则的搬运速率。 */
    private final Stats stats = new Stats();
    /** 距上次速率结算过了多少 tick。 */
    private int sinceSample;

    /**
     * 记一次执行结果：同时写账本与结论。
     *
     * @param entry  执行条目
     * @param reason 原因
     * @param now    当前服务端 tick
     * @param amount 本次搬动的数量
     * @param face   本次用的面
     */
    public void record(Form.Entry entry, Reason reason, long now, long amount,
            @Nullable Direction face) {
        entry.ledger().append(reason, now, amount, face);
        entry.conclusion().record(reason, now, amount, face);
    }

    /** 按机器、按规则的搬运速率。 */
    public Stats stats() {
        return this.stats;
    }

    /**
     * 每工厂每 tick 调一次：自己数间隔结算速率，不依赖外部节奏。
     *
     * @param now 当前服务端 tick（保留给以后的 TTL 与结论清理）
     */
    public void tick(long now) {
        this.sinceSample++;
        if (this.sinceSample >= SAMPLE_INTERVAL) {
            this.sinceSample = 0;
            this.stats.sample();
        }
    }

    /**
     * 只保留这些机器的速率统计（机器删除后不再攒条目）。
     *
     * @param keep 仍然存在的机器 id
     */
    public void retainMachines(Set<UUID> keep) {
        this.stats.retain(keep);
    }

    /**
     * 一个目标的账本：环形有界的「变化序列」。
     *
     * <p>它记的是变化不是时间线：连续同原因、同面的结果合并成一行（次数与累计数量累加），
     * 原因或面一变就开新行。当前结论就是最新那一行，不另外存一份。</p>
     *
     * <p>容量固定 {@link Analyzer#LEDGER_CAPACITY} 行，满了覆盖最旧的；条目离开表单时整本账
     * 随条目一起释放。</p>
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
         */
        public record Row(Reason reason, long firstTick, long lastTick, int count, long amount,
                @Nullable Direction face) {
        }

        private final Row[] rows = new Row[LEDGER_CAPACITY];
        /** 下一行的写入位置。 */
        private int head;
        private int size;

        /**
         * 追加一次结果；与最新一行同原因同面时合并。
         *
         * @param reason 原因
         * @param tick   发生时刻
         * @param amount 本次搬动的数量
         * @param face   用的面
         */
        public void append(Reason reason, long tick, long amount, @Nullable Direction face) {
            Row newest = this.current();
            if (newest != null && newest.reason() == reason && Objects.equals(newest.face(), face)) {
                this.rows[this.newestIndex()] = new Row(reason, newest.firstTick(), tick,
                        newest.count() + 1, newest.amount() + amount, face);
                return;
            }
            this.rows[this.head] = new Row(reason, tick, tick, 1, amount, face);
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
            Row[] snapshot = new Row[this.size];
            for (int index = 0; index < this.size; index++) {
                snapshot[index] = this.rows[this.indexFromNewest(index)];
            }
            return List.of(snapshot);
        }

        private int newestIndex() {
            return Math.floorMod(this.head - 1, LEDGER_CAPACITY);
        }

        private int indexFromNewest(int offset) {
            return Math.floorMod(this.newestIndex() - offset, LEDGER_CAPACITY);
        }
    }

    /**
     * 一个目标的历史结论：账本压成的一组数。
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
