package io.github.crafteve.idlefactory.factory.machine;

import io.github.crafteve.idlefactory.factory.probe.BlockFacts;

/**
 * 一台机器的状态：当前结论 + 进入该结论的时刻。
 *
 * <p>字段对外只读，写入只有 {@link #apply} 一个入口；每次写入都由「当前完整输入」重算，
 * 不依赖信号先后（同一 tick 里观测和配置都可能变）。</p>
 */
public final class MachineState {
    private MachineStatus status = MachineStatus.UNSEEN;

    /** 当前状态。 */
    public MachineStatus status() {
        return this.status;
    }

    /**
     * 唯一写入点：按信号重算状态。
     *
     * @param signal 写入信号
     * @param facts  这个方块的观测事实；没登记过时传未探测的空事实
     */
    void apply(MachineSignal signal, BlockFacts facts) {
        MachineStatus next = switch (signal) {
            case OBSERVED, CONFIG_CHANGED -> derive(facts);
        };
        this.status = next;
    }

    /**
     * 观测事实 → 机器状态。
     *
     * @param facts 观测事实
     * @return 状态；从没探过为 UNSEEN
     */
    private static MachineStatus derive(BlockFacts facts) {
        return switch (facts.health()) {
            case UNKNOWN -> MachineStatus.UNSEEN;
            case PRESENT -> MachineStatus.RUN;
            case UNLOADED -> MachineStatus.UNLOADED;
            case MISSING, NO_DIMENSION -> MachineStatus.GONE;
        };
    }
}
