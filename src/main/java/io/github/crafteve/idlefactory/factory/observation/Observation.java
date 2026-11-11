package io.github.crafteve.idlefactory.factory.observation;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.capability.CapabilityKind;
import io.github.crafteve.idlefactory.factory.probe.BlockFacts;
import io.github.crafteve.idlefactory.factory.probe.BlockProbe;
import io.github.crafteve.idlefactory.factory.probe.Health;
import io.github.crafteve.idlefactory.factory.probe.WorkState;

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
public final class Observation implements BlockFacts {
    /** 还没探测过的占位。只读共享，永远不要对它调用包内写方法。 */
    public static final Observation UNKNOWN = new Observation();

    /**
     * 正常状态的复探间隔（tick）。
     *
     * <p>必须是固定值，不能随安静次数拉长：健康过期判据是
     * {@code now - lastProbeTick > staleHealthTicks}（默认 1200 tick），复探间隔一旦
     * 超过它，没出任何事的机器也会被判成过期，连带 IO 门禁把节点停掉。100 tick 复探
     * 在几百台规模下也只有每 tick 个位数，预算够用。</p>
     */
    private static final int PRESENT_INTERVAL = 100;
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
    /**
     * 最后一次真的看到过的方块 id。
     *
     * <p>和 {@link #blockId} 分开是有意的：{@code blockId} 在未加载/维度不存在时必须是
     * {@code null}，因为面表过期判断靠「方块 id 变没变」，保留旧值会让重载后继续用旧面表。
     * 这个字段只在探到真方块时更新，专门给界面分组用——区块卸载时组不该散架。</p>
     */
    @Nullable
    private ResourceLocation lastBlockId;
    @Nullable
    private ResourceLocation beTypeId;
    private final EnumSet<CapabilityKind> presence = EnumSet.noneOf(CapabilityKind.class);
    private boolean legacyContainer;
    private long observedAtTick = -1L;
    private long missingSince = -1L;
    private long nextProbeTick;
    private int goneProbes;
    private int probeInterval = PRESENT_INTERVAL;

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

    /**
     * 最后一次探到的方块 id，未加载时保留旧值。
     *
     * <p>只给界面分组与显示兜底用，<b>不参与任何执行判断</b>；判断「机器还在不在」一律看
     * {@link #health()} 和 {@link #blockId()}。</p>
     *
     * @return 方块 id；从没探到过时为 {@code null}
     */
    @Nullable
    public ResourceLocation lastBlockId() {
        return this.lastBlockId;
    }

    /** 观测到的方块实体类型 id；没有方块实体时为 {@code null}。 */
    @Nullable
    public ResourceLocation beTypeId() {
        return this.beTypeId;
    }

    /** 无向查询报告的能力（只读）。 */
    public Set<CapabilityKind> presence() {
        return Collections.unmodifiableSet(this.presence);
    }

    /** 是不是原版 {@code Container}（给没有注册能力的模组方块兜底）。 */
    public boolean legacyContainer() {
        return this.legacyContainer;
    }

    /**
     * 健康是否已经过期。
     *
     * <p>从未探测过时恒为 {@code false}：过期与「没探过」是两件事，各由一个布尔表达。</p>
     *
     * @param now              当前观测时钟
     * @param staleHealthTicks 过期阈值（tick）
     * @return 探过且超过阈值时为 {@code true}
     */
    @Override
    public boolean isStale(long now, long staleHealthTicks) {
        if (!this.probed() || this.observedAtTick < 0L) {
            return false;
        }
        return now - this.observedAtTick > staleHealthTicks;
    }

    /** 最后一次探测的 tick；从没探过为 {@code -1}。 */
    public long observedAtTick() {
        return this.observedAtTick;
    }

    /** 下一次该探测的 tick。 */
    public long nextProbeTick() {
        return this.nextProbeTick;
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

        this.health = result.health();
        this.blockId = result.blockId();
        if (result.blockId() != null) {
            // 只有真的看到方块才刷新「上次是什么」；未加载/维度不存在时保留，
            // 否则玩家走远一步，界面上的组就会全部散成「未知」。
            this.lastBlockId = result.blockId();
        }
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
            int shift = Math.min(Math.max(this.goneProbes - 1, 0), 5);
            this.probeInterval = Math.min(MISSING_MIN_INTERVAL << shift, MISSING_MAX_INTERVAL);
        } else if (result.health() == Health.PRESENT) {
            this.missingSince = -1L;
            this.goneProbes = 0;
            // 固定 100 tick 复探：间隔必须短于健康过期阈值，否则安静机器会自己“过期”。
            this.probeInterval = PRESENT_INTERVAL;
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