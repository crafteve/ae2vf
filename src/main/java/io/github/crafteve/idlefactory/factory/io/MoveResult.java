package io.github.crafteve.idlefactory.factory.io;

/**
 * 一次搬运的结果。
 *
 * <p>{@code moved} 必须单独返回，不能并进 {@link Reason}：原因说明「为什么是这个结果」，
 * 数量说明「实际搬动了多少」，两者在部分成功时并不等价。</p>
 *
 * @param moved  实际搬动的数量（物品个、流体 mB、能量 FE）
 * @param reason 为什么是这个数
 */
public record MoveResult(long moved, Reason reason) {
    /** 没搬动时的兜底原因。 */
    public static final MoveResult NOTHING = new MoveResult(0L, Reason.NO_MATERIAL);

    /** 这一轮是否搬了东西。 */
    public boolean movedAnything() {
        return this.moved > 0L;
    }
}
