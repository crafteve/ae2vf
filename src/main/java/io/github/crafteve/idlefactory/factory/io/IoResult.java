package io.github.crafteve.idlefactory.factory.io;

/**
 * 一次搬运的结果。
 *
 * @param moved  实际搬动的物品数
 * @param reason 为什么是这个数
 */
public record IoResult(int moved, Reason reason) {
    /** 没搬动时的兜底原因。 */
    public static final IoResult NOTHING = new IoResult(0, Reason.NO_ITEM);

    /** 结果原因。 */
    public enum Reason {
        /** 搬成了。 */
        OK,
        /** 这个面没有物品能力。 */
        NO_HANDLER,
        /** 仓库或机器里没有这个物品。 */
        NO_ITEM,
        /** 机器 / 仓库没有空间。 */
        NO_SPACE
    }

    /** 这一轮是否搬了东西。 */
    public boolean movedAnything() {
        return this.moved > 0;
    }
}
