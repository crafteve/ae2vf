package io.github.crafteve.idlefactory.factory.io;

/**
 * 一次搬运的结果。
 *
 * @param moved  实际搬动的数量（物品个、流体 mB、能量 FE）
 * @param reason 为什么是这个数
 */
public record IoResult(long moved, Reason reason) {
    /** 没搬动时的兜底原因。 */
    public static final IoResult NOTHING = new IoResult(0L, Reason.NO_MATERIAL);

    /** 这一轮是否搬了东西。 */
    public boolean movedAnything() {
        return this.moved > 0L;
    }
}
