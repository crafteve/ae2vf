package io.github.crafteve.ae2vf.machine;

import io.github.crafteve.ae2vf.node.Node;
import io.github.crafteve.ae2vf.observation.BlockFacts;

/**
 * 一台机器的健康：在线或离线。
 *
 * <p>只有两个值：方块在、类型与节点声明一致才算在线；未探测、未加载、方块没了、类型不符
 * 都算离线。方块级的精细状态（UNLOADED / MISSING / NO_DIMENSION）留在 {@link
 * io.github.crafteve.ae2vf.observation.Health}，只用于诊断与 tooltip。</p>
 *
 * <p>这是<b>派生值</b>：每次读取由「节点声明的类型 + 方块观测事实」现算，不存第二份，
 * 也不写档。</p>
 */
public enum MachineHealth {
    /** 离线：未探测、未加载、方块没了或类型不符。 */
    OFFLINE,
    /** 在线：方块在、且类型与节点声明一致。 */
    ONLINE;

    /**
     * 由节点声明与观测事实推导机器健康。
     *
     * @param node    机器所属节点（提供类型方块）
     * @param machine 机器
     * @param facts   方块观测事实来源
     * @return 机器健康
     */
    public static MachineHealth of(Node node, Machine machine, BlockFacts.Source facts) {
        return of(machine, node.typeBlockId(), facts);
    }

    /**
     * 由期望类型与观测事实推导机器健康。
     *
     * @param machine        机器
     * @param expectedTypeId 节点声明的类型方块 id
     * @param facts          方块观测事实来源
     * @return 机器健康
     */
    public static MachineHealth of(Machine machine, net.minecraft.resources.ResourceLocation expectedTypeId,
            BlockFacts.Source facts) {
        BlockFacts observation = facts.at(machine.block());
        if (!observation.probed() || !observation.present()
                || observation.blockId() == null
                || !observation.blockId().equals(expectedTypeId)) {
            return OFFLINE;
        }
        return ONLINE;
    }

    /** 是否在线。 */
    public boolean isOnline() {
        return this == ONLINE;
    }

    /** 是否可以进表单、参与搬运。 */
    public boolean usable() {
        return this == ONLINE;
    }

    /** 给节点终端、机器表与 tooltip 共用的一句话。 */
    public String describe() {
        return switch (this) {
            case OFFLINE -> "离线";
            case ONLINE -> "在线";
        };
    }
}
