package io.github.crafteve.idlefactory.observation;

import java.util.LinkedHashSet;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.key.BlockKey;

/**
 * 结构队列：等下一轮立刻复探的位置（事件标脏、新登记的位置）。
 *
 * <p>用 {@link LinkedHashSet} 保持事件顺序并去重。每 tick 按事件预算消费，消费不完顺延到
 * 下一 tick——预算在这里的语义是 backlog 消化速度，不是摊薄，tick 不会被事件 burst 打爆。</p>
 */
final class StructureQueue {
    private final LinkedHashSet<BlockKey> pending = new LinkedHashSet<>();

    /**
     * 入队；已在队列里不重复加。
     *
     * @param key 位置键
     */
    void add(BlockKey key) {
        this.pending.add(key);
    }

    /**
     * 是否在队列里。
     *
     * @param key 位置键
     * @return 在队列里为 {@code true}
     */
    boolean contains(BlockKey key) {
        return this.pending.contains(key);
    }

    /**
     * 取一个待处理位置。
     *
     * @return 位置；队列空时为 {@code null}
     */
    @Nullable
    BlockKey poll() {
        var iterator = this.pending.iterator();
        if (!iterator.hasNext()) {
            return null;
        }
        BlockKey key = iterator.next();
        iterator.remove();
        return key;
    }

    /** 队列长度。 */
    int size() {
        return this.pending.size();
    }

    /** 清空；停服时调。 */
    void clear() {
        this.pending.clear();
    }
}
