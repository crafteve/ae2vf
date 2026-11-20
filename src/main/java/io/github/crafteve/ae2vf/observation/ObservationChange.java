package io.github.crafteve.ae2vf.observation;

import java.util.List;

import io.github.crafteve.ae2vf.key.BlockKey;

/**
 * 一批观测变化：一次 tick 里所有健康结论发生变化的行。
 *
 * <p>变化只走这一条通道，观测层不再暴露「变化列表」供上层轮询。批量广播保证一次 tick 只
 * 通知一次，失联聚合与 IO 唤醒都按批处理。</p>
 *
 * @param changes 本批变化
 * @param tick    观测时钟
 */
public record ObservationChange(List<BlockChange> changes, long tick) {
    public ObservationChange {
        changes = List.copyOf(changes);
    }

    /** 是否没有任何变化。 */
    public boolean isEmpty() {
        return this.changes.isEmpty();
    }

    /**
     * 一个方块的健康变化。
     *
     * @param key  位置
     * @param from 旧结论
     * @param to   新结论
     * @param tick 发生时刻（观测时钟）
     */
    public record BlockChange(BlockKey key, Health from, Health to, long tick) {
    }
}
