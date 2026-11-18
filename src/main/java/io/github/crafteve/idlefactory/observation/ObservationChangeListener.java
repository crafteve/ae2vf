package io.github.crafteve.idlefactory.observation;

/**
 * 观测变化监听器：每 tick 最多收到一批。
 *
 * <p>监听者只能读观测结果，不能回写观测表——回写会与探测循环抢同一批行。需要「马上重探」
 * 的场合走 {@link ObservationRunner#markDirty}，那是下一 tick 的事。</p>
 */
@FunctionalInterface
public interface ObservationChangeListener {
    /**
     * 收到一批变化。
     *
     * @param change 本批变化；保证非空
     */
    void onObservationChanged(ObservationChange change);
}
