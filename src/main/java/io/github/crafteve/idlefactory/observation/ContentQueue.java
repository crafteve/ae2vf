package io.github.crafteve.idlefactory.observation;

/**
 * 内容队列：健康与工态两条游标在观测表上轮转。
 *
 * <p>游标只记录「上次走到第几行」，不持有行的引用：行会被 {@code reconcile} 删掉，持有引用
 * 就会把已删除的行一直探下去。删行时按下标回退（删在游标之前要减，删的正是游标行则不前进），
 * 因此对账之后游标仍指向「下一行」而不是跳过或重复。</p>
 */
final class ContentQueue {
    private int healthCursor;
    private int workCursor;

    /** 健康游标。 */
    int healthCursor() {
        return this.healthCursor;
    }

    /**
     * 写回健康游标。
     *
     * @param cursor 新位置
     */
    void healthCursor(int cursor) {
        this.healthCursor = cursor;
    }

    /** 工态游标。 */
    int workCursor() {
        return this.workCursor;
    }

    /**
     * 写回工态游标。
     *
     * @param cursor 新位置
     */
    void workCursor(int cursor) {
        this.workCursor = cursor;
    }

    /**
     * 对账删行后修正两条游标。
     *
     * @param removedIndices 被删行的旧下标，升序
     */
    void onRemoved(int[] removedIndices) {
        this.healthCursor = adjust(this.healthCursor, removedIndices);
        this.workCursor = adjust(this.workCursor, removedIndices);
    }

    /**
     * 游标按叠加算法回退。
     *
     * @param cursor  旧游标
     * @param removed 被删行的旧下标，升序
     * @return 新游标
     */
    private static int adjust(int cursor, int[] removed) {
        int shift = 0;
        for (int index : removed) {
            if (index < cursor) {
                shift++;
            }
        }
        return Math.max(0, cursor - shift);
    }
}
