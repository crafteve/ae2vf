package io.github.crafteve.idlefactory.factory.node;

/**
 * 主动规划器：决定主动侧的节奏——队列什么时候投递、产物什么时候回收。
 *
 * <p>它只排「这一 tick」：退避用「现在 − 上次」重算，不排未来。回收原先由
 * {@code ProviderReturnSweep} 自己维护节奏（活跃 5 tick / 空闲阶梯退避 / 活跃窗口），
 * 现在节奏参数住在这里，扫描实现只问「到期没有」。队列不退避，每 tick 都试。</p>
 */
public final class ActivePlanner {
    /** 活跃期的回收扫描间隔（tick）。 */
    private static final long ACTIVE_INTERVAL = 5L;
    /** 一次派单 / 一次收获算活跃多久（tick）。 */
    private static final long ACTIVE_WINDOW = 200L;
    /** 空闲退避阶梯（tick）。 */
    private static final long[] IDLE_DELAYS = {20L, 40L, 80L, 128L};

    /** 下一次允许扫描的 tick。 */
    private long nextSweepTick;
    /** 活跃期截止 tick。 */
    private long activeUntil;
    /** 空闲退避走到第几级。 */
    private int idleStep;

    /** 打回活跃节奏：派单成功、抽到东西、机器重新上线时调。 */
    public void activate(long now) {
        this.activeUntil = now + ACTIVE_WINDOW;
        this.idleStep = 0;
        this.nextSweepTick = now + ACTIVE_INTERVAL;
    }

    /**
     * 这一轮轮到回收了吗。
     *
     * @param now 当前游戏时间
     * @return 到期为 {@code true}
     */
    public boolean sweepDue(long now) {
        return now >= this.nextSweepTick;
    }

    /**
     * 没收获时排下一次扫描：活跃期固定间隔，空闲按阶梯退避。
     *
     * @param now 当前游戏时间
     */
    public void idle(long now) {
        if (now < this.activeUntil) {
            this.nextSweepTick = now + ACTIVE_INTERVAL;
            return;
        }
        int step = Math.min(this.idleStep, IDLE_DELAYS.length - 1);
        this.nextSweepTick = now + IDLE_DELAYS[step];
        this.idleStep = Math.min(this.idleStep + 1, IDLE_DELAYS.length - 1);
    }
}
