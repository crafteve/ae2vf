package io.github.crafteve.idlefactory.factory.machine;

/**
 * 机器状态：只回答「它在不在」，不回答「它这轮跑没跑」。
 *
 * <p>这是世界的镜像：由观测事实 + 「被配置引用」两件事推出来，不写档。睡眠、拒收、计数
 * 属于执行状态，住在执行层，不在这个枚举里（见计划 A1 / A2）。</p>
 */
public enum MachineStatus {
    /** 方块在、已加载、被配置引用。 */
    RUN,
    /** 方块没了（被拆掉或换成了别的东西）。 */
    GONE,
    /** 区块没加载。 */
    UNLOADED,
    /** 还没被观测探到；会自愈成上面某个值。 */
    UNSEEN
}
