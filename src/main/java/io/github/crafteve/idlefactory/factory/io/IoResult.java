package io.github.crafteve.idlefactory.factory.io;

/**
 * 一次搬运的结果。
 *
 * @param moved  实际搬动的数量（物品个、流体 mB、能量 FE）
 * @param reason 为什么是这个数
 */
public record IoResult(long moved, Reason reason) {
    /** 没搬动时的兜底原因。 */
    public static final IoResult NOTHING = new IoResult(0L, Reason.NO_ITEM);

    /** 结果原因。 */
    public enum Reason {
        /** 搬成了。 */
        OK,
        /** 这个面没有对应能力。 */
        NO_HANDLER,
        /** 仓库或机器里没有这个资源。 */
        NO_ITEM,
        /** 机器 / 仓库没有空间。 */
        NO_SPACE,
        /**
         * 有空间，但对面不收 / 不给。
         *
         * <p>和 {@link #NO_SPACE} 的区别很重要：「满了」是正常状态，「拒收」说明这个面不对
         * （机器的面配置不允许这个方向，或者面表过期了）。后者要触发路由重扫。</p>
         */
        REJECTED
    }

    /** 这一轮是否搬了东西。 */
    public boolean movedAnything() {
        return this.moved > 0L;
    }
}
