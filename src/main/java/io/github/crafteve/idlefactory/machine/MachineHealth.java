package io.github.crafteve.idlefactory.machine;

import io.github.crafteve.idlefactory.node.Node;
import io.github.crafteve.idlefactory.observation.BlockFacts;
import io.github.crafteve.idlefactory.observation.Health;

/**
 * 一台机器此刻「能不能搬」的结论：方块在不在 + 方块类型是否还是节点声明的类型。
 *
 * <p>三层信息不混：方块健康是 {@link Health}；机器健康是这里（加类型匹配）；节点健康按在线
 * 台数分档，在节点层。类型不一致的机器仍然<b>在线</b>，只是不进表单、不产生搬运原因。</p>
 */
public enum MachineHealth {
    /** 还没探测过。 */
    UNSEEN,
    /** 在线且类型匹配：可以搬。 */
    RUN,
    /** 在线但方块类型与节点声明不一致：不搬。 */
    TYPE_MISMATCH,
    /** 区块未加载：不搬。 */
    UNLOADED,
    /** 方块已消失或维度不存在：不搬。 */
    GONE;

    /**
     * 由观测事实与节点声明推导机器健康。
     *
     * @param node    机器所属节点（提供类型方块）
     * @param machine 机器
     * @param facts   方块观测事实来源
     * @return 机器健康
     */
    public static MachineHealth of(Node node, Machine machine, BlockFacts.Source facts) {
        BlockFacts observation = facts.at(machine.block());
        return switch (observation.health()) {
            case UNKNOWN -> UNSEEN;
            case UNLOADED -> UNLOADED;
            case MISSING, NO_DIMENSION -> GONE;
            case PRESENT -> observation.blockId() != null
                    && observation.blockId().equals(node.typeBlockId())
                            ? RUN
                            : TYPE_MISMATCH;
        };
    }

    /** 方块是否在线（不看类型）。 */
    public boolean isOnline() {
        return this == RUN || this == TYPE_MISMATCH;
    }

    /** 是否可以进表单、参与搬运。 */
    public boolean usable() {
        return this == RUN;
    }

    /** 是否已经探测过（不管结果）。 */
    public boolean probed() {
        return this != UNSEEN;
    }
}
