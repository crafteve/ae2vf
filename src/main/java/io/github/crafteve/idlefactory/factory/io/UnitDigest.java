package io.github.crafteve.idlefactory.factory.io;

import java.util.EnumMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Direction;

/**
 * 一个执行单元的历史结论：分析器把账本压成的一组数。
 *
 * <p>这是值对象，只给规划器与界面读；账本是明细（会滚掉），这里是摘要（不滚）。
 * 命名后缀语义固定：{@code At} 是绝对时刻，{@code Ticks} 是连续时长，
 * {@code Streak} 是连续次数，{@code Counts} 是累计次数，{@code Hint} 是经验值。</p>
 */
public final class UnitDigest {
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
