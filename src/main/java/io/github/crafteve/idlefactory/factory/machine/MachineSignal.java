package io.github.crafteve.idlefactory.factory.machine;

/**
 * 机器状态的写入信号。
 *
 * <p>状态只有一个写入点（{@link MachineState#apply}）：观测给出的事实与配置的变化都通过
 * 这两个信号汇入，由它穷尽处理，外部不许直接改字段。</p>
 */
public enum MachineSignal {
    /** 观测层给出了这个方块的新事实。 */
    OBSERVED,
    /** 配置变了（机器新增 / 删除 / 更换方块）。 */
    CONFIG_CHANGED
}
