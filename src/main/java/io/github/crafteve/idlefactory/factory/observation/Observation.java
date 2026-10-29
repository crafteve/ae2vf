package io.github.crafteve.idlefactory.factory.observation;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.probe.BlockProbe;
import io.github.crafteve.idlefactory.factory.probe.Health;
import io.github.crafteve.idlefactory.factory.probe.WorkState;
import io.github.crafteve.idlefactory.factory.resource.ResourceKind;

import net.minecraft.resources.ResourceLocation;

/**
 * 一个方块位置的世界事实快照。
 *
 * <p>纯运行时，<b>不写存档</b>。它是 {@link ObservationService} 的内部状态：对外只暴露读方法，
 * 写入只能经 {@link ObservationService}。</p>
 *
 * <p>刻意做成可变类而不是 record：一次 tick 里会更新几十个观测，每次 alloc 一个新 record 只会
 * 制造垃圾。线程模型和服务端一致——只有服务器线程碰它。</p>
 *
 * <p>两个身份字段（{@link #blockId()} 与 {@link #beTypeId()}）用来回答「还是不是那个东西」和
 * 「有没有升级」：任一个变了，说明这个位置换过方块——{@code ObservationService} 凭它们
 * 判断面表过期（面表本身住在 capability 包，这里的字段只负责举证）。</p>
 */
public final class Observation {
    /** 还没探测过的占位。只读共享，永远不要对它调用包内写方法。 */
    public static final Observation UNKNOWN = new Observation();

    /** 正常状态的复探间隔（tick）。 */
    private static final int PRESENT_INTERVAL = 100;
    /**
     * 安静 PRESENT 的复探间隔上限（tick）：12000 = 10 分钟。
     *
     * <p>长期无变化、无事件的机器没有资格一直占预算：连续安静几次后一路退到这里，
     * 之后只靠事件唤醒（能力失效、邻居更新、chunk 加载）。这就是“稳态探测次数和对象
     * 总数解耦”——排队的只剩有事的，没事的不在队列里。</p>
     */
    private static final int QUIET_PRESENT_MAX_INTERVAL = 12000;
    /** 未加载时的复探间隔（tick）：加载后要尽快发现。 */
    private static final int UNLOADED_INTERVAL = 40;
    /** 失联复探的起始间隔与上限。 */
    private static final int MISSING_MIN_INTERVAL = 100;
    private static final int MISSING_MAX_INTERVAL = 1200;
    /** 工作状态复探间隔。 */
    private static final int WORK_INTERVAL = 10;

    private Health health = Health.UNKNOWN;
    private WorkState workState = WorkState.UNKNOWN;
    private long workObservedAtTick = -1L;
    private long nextWorkProbeTick;
    @Nullable
    private ResourceLocation blockId;
    @Nullable
    private ResourceLocation beTypeId;
    private final EnumSet<ResourceKind> presence = EnumSet.noneOf(ResourceKind.class);
    private boolean legacyContainer;
    private long observedAtTick = -1L;
    private long missingSince = -1L;
    private long nextProbeTick;
    private int goneProbes;
    private int probeInterval = PRESENT_INTERVAL;
    /**
     * 连续安静次数：健康一直是 PRESENT 且方块身份没换，一次探完 +1。
     *
     * <p>脏事件触发的复探如果结论不变也照样 +1——它只是提前确认了一次“没事”，
     * 不改变“没事”的事实。任何健康迁移或换方块都清零。</p>
     */
    private int quietProbes;

    Observation() {
        // 只能由同包的 ObservationService 创建
    }

    /** 当前结论。 */
    public Health health() {
        return this.health;
    }

    /** 观测到的工作状态；没有可靠探针时为 {@link WorkState#UNKNOWN}。 */
    public WorkState workState() {
        return this.workState;
    }

    /** 最后一次工作状态探测的 tick；没探过为 {@code -1}。 */
    public long workObservedAtTick() {
        return this.workObservedAtTick;
    }

    /**
     * 距离上次工作状态探测的 tick。
     *
     * @param now 当前 tick
     * @return 没探过为 {@code -1}
     */
    public long workAgeTicks(long now) {
        return this.workObservedAtTick < 0L ? -1L : Math.max(0L, now - this.workObservedAtTick);
    }

    /** 下一次该探测工作状态的 tick。 */
    long nextWorkProbeTick() {
        return this.nextWorkProbeTick;
    }

    /** 当前是否该探测工作状态。 */
    boolean workDue(long tick) {
        return this.health == Health.PRESENT && this.nextWorkProbeTick <= tick;
    }

    /** 写入一次工作状态探测结果（包内可见）。 */
    void applyWorkState(WorkState state, long tick) {
        this.workState = state;
        this.workObservedAtTick = tick;
        this.nextWorkProbeTick = tick + WORK_INTERVAL;
    }

    /** 观测到的方块 id（注册名）；未探测或未加载时为 {@code null}。 */
    @Nullable
    public ResourceLocation blockId() {
        return this.blockId;
    }

    /** 观测到的方块实体类型 id；没有方块实体时为 {@code null}。 */
    @Nullable
    public ResourceLocation beTypeId() {
        return this.beTypeId;
    }

    /** 无向查询报告的能力（只读）。 */
    public Set<ResourceKind> presence() {
        return Collections.unmodifiableSet(this.presence);
    }

    /** 是不是原版 {@code Container}（给没有注册能力的模组方块兜底）。 */
    public boolean legacyContainer() {
        return this.legacyContainer;
    }

    /** 最后一次探测的 tick；从没探过为 {@code -1}。 */
    public long observedAtTick() {
        return this.observedAtTick;
    }

    /** 第一次观测到「不在了」的 tick；没失联为 {@code -1}。 */
    public long missingSince() {
        return this.missingSince;
    }

    /** 下一次该探测的 tick。 */
    public long nextProbeTick() {
        return this.nextProbeTick;
    }

    /** 当前复探间隔（tick），诊断用。 */
    public int probeInterval() {
        return this.probeInterval;
    }

    /**
     * 这个位置算不算「在回收站里」。
     *
     * @param detached 玩家是否已经断开它（配置层的意图）
     * @return 断开或确实不在了时为 {@code true}
     */
    public boolean binned(boolean detached) {
        return detached || this.health.gone();
    }

    /**
     * 距离上次观测过去了多少 tick。
     *
     * @param now 当前 tick
     * @return 未探测过时为 {@code -1}
     */
    public long ageTicks(long now) {
        return this.observedAtTick < 0L ? -1L : Math.max(0L, now - this.observedAtTick);
    }

    /**
     * 写一次探测结果，并安排下一次。
     *
     * <p>包内可见：只有 {@link ObservationService} 能改。</p>
     *
     * @param result 探测结果
     * @param tick   当前 tick
     */
    void applyProbe(BlockProbe.Presence result, long tick) {
        Health before = this.health;
        boolean identityChanged = !Objects.equals(this.blockId, result.blockId())
                || !Objects.equals(this.beTypeId, result.beTypeId());

        this.health = result.health();
        this.blockId = result.blockId();
        this.beTypeId = result.beTypeId();
        this.presence.clear();
        this.presence.addAll(result.kinds());
        this.legacyContainer = result.legacyContainer();
        this.observedAtTick = tick;

        if (result.health().gone()) {
            if (this.missingSince < 0L) {
                this.missingSince = tick;
            }
            this.goneProbes++;
            this.quietProbes = 0;
            int shift = Math.min(Math.max(this.goneProbes - 1, 0), 5);
            this.probeInterval = Math.min(MISSING_MIN_INTERVAL << shift, MISSING_MAX_INTERVAL);
        } else if (result.health() == Health.PRESENT) {
            this.missingSince = -1L;
            this.goneProbes = 0;
            if (before == Health.PRESENT && !identityChanged) {
                // 一直好好的：安静计数 +1，间隔指数拉长，直到 10 分钟一探。
                // 从 100 起翻 7 次正好到顶（100→…→6400→封顶 12000）。
                this.quietProbes++;
                int shift = Math.min(this.quietProbes, 7);
                this.probeInterval = Math.min(PRESENT_INTERVAL << shift,
                        QUIET_PRESENT_MAX_INTERVAL);
            } else {
                // 刚恢复或刚换过方块：回到正常节奏，重新开始数安静。
                this.quietProbes = 0;
                this.probeInterval = PRESENT_INTERVAL;
            }
            if (before != Health.PRESENT) {
                this.workState = WorkState.UNKNOWN;
                this.workObservedAtTick = -1L;
                this.nextWorkProbeTick = tick;
            } else if (this.nextWorkProbeTick <= 0L) {
                this.nextWorkProbeTick = tick;
            }
        } else {
            this.goneProbes = 0;
            this.probeInterval = UNLOADED_INTERVAL;
            this.workState = WorkState.UNKNOWN;
            this.workObservedAtTick = -1L;
            this.nextWorkProbeTick = 0L;
        }

        // 注意：这里不再清面表——面表住在 capability 包，过期判断是 Service 的事：
        // 它拿这里的 health/blockId/beTypeId 和探之前的值比，变了就调面表缓存作废。
        // 把“判”和“清”拆开，病历只管记，Service 管动手。
        this.nextProbeTick = tick + this.probeInterval;
    }
}
