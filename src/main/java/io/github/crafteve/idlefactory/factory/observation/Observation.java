package io.github.crafteve.idlefactory.factory.observation;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

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
 * 「有没有升级」：任一个变了，说明这个位置换过方块，缓存的面表立刻作废。</p>
 */
public final class Observation {
    /** 还没探测过的占位。只读共享，永远不要对它调用包内写方法。 */
    public static final Observation UNKNOWN = new Observation();

    /** 正常状态的复探间隔（tick）。 */
    private static final int PRESENT_INTERVAL = 100;
    /** 未加载时的复探间隔（tick）：加载后要尽快发现。 */
    private static final int UNLOADED_INTERVAL = 40;
    /** 失联复探的起始间隔与上限。 */
    private static final int MISSING_MIN_INTERVAL = 100;
    private static final int MISSING_MAX_INTERVAL = 1200;

    private Health health = Health.UNKNOWN;
    @Nullable
    private ResourceLocation blockId;
    @Nullable
    private ResourceLocation beTypeId;
    private final EnumSet<ResourceKind> presence = EnumSet.noneOf(ResourceKind.class);
    private boolean legacyContainer;
    @Nullable
    private FaceTable faces;
    /** 面表代数：每次作废 +1。路由表靠它判断自己是否过期。 */
    private long facesGeneration;
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

    /** 缓存的面表；还没建过时为 {@code null}。 */
    @Nullable
    public FaceTable faces() {
        return this.faces;
    }

    /** 面表代数。每次作废 +1，路由表用它判过期。 */
    public long facesGeneration() {
        return this.facesGeneration;
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
            int shift = Math.min(Math.max(this.goneProbes - 1, 0), 5);
            this.probeInterval = Math.min(MISSING_MIN_INTERVAL << shift, MISSING_MAX_INTERVAL);
        } else if (result.health() == Health.PRESENT) {
            this.missingSince = -1L;
            this.goneProbes = 0;
            this.probeInterval = PRESENT_INTERVAL;
        } else {
            this.goneProbes = 0;
            this.probeInterval = UNLOADED_INTERVAL;
        }

        if (before != result.health() || identityChanged) {
            // 方块换过／可用性变了：面表一定过期
            this.invalidateFaces();
        }
        this.nextProbeTick = tick + this.probeInterval;
    }

    /**
     * 写入缓存的面表。
     *
     * @param table 面表
     */
    void applyFaces(FaceTable table) {
        this.faces = table;
    }

    /**
     * 让缓存的面表作废。
     *
     * <p>能力失效事件（模组改了面配置）不会改变方块 id，所以 {@link #applyProbe} 的
     * 「身份变了」判断抓不到它——靠这个方法显式清。</p>
     */
    void invalidateFaces() {
        this.faces = null;
        this.facesGeneration++;
    }
}
