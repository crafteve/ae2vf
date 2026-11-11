package io.github.crafteve.idlefactory.factory.machine;

import java.util.UUID;

import io.github.crafteve.idlefactory.factory.probe.BlockFacts;

/**
 * 一台机器的状态：当前结论 + 进入该结论的时刻。
 *
 * <p>字段对外只读，写入只有 {@link #apply} 一个入口；每次写入都由「当前完整输入」重算，
 * 不依赖信号先后（同一 tick 里观测和配置都可能变）。</p>
 */
public final class MachineState {
    private final UUID machineId;
    private MachineStatus status = MachineStatus.UNSEEN;
    private long sinceTick = -1L;

    /**
     * @param machineId 机器 id
     */
    MachineState(UUID machineId) {
        this.machineId = machineId;
    }

    /** 机器 id。 */
    public UUID machineId() {
        return this.machineId;
    }

    /** 当前状态。 */
    public MachineStatus status() {
        return this.status;
    }

    /** 进入当前状态的时刻；还没定过为 {@code -1}。 */
    public long sinceTick() {
        return this.sinceTick;
    }

    /**
     * 唯一写入点：按信号重算状态。
     *
     * @param signal 写入信号
     * @param facts  这个方块的观测事实；没登记过时传未探测的空事实
     * @param now    当前服务端 tick
     */
    void apply(MachineSignal signal, BlockFacts facts, long now) {
        MachineStatus next = switch (signal) {
            case OBSERVED, CONFIG_CHANGED -> derive(facts);
        };
        if (next != this.status) {
            this.status = next;
            this.sinceTick = now;
        }
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
