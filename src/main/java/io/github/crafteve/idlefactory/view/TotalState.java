package io.github.crafteve.idlefactory.view;

/**
 * 界面上的总状态：由仓库在线状态 + 节点结论 + IO 推导，不由任何一处单独维护。
 */
public enum TotalState {
    /** 正在搬东西。 */
    RUNNING,
    /** 配好了但此刻不忙。 */
    IDLE,
    /** 缺料。 */
    WAITING_MATERIAL,
    /** 输入顺畅。 */
    INPUT_OK,
    /** 输入堵塞（机器满 / 拒收）。 */
    INPUT_BLOCKED,
    /** 仓库离线。 */
    OFFLINE,
    /** 还没配规则。 */
    UNCONFIGURED,
    /** 判断不出来。 */
    UNKNOWN
}
