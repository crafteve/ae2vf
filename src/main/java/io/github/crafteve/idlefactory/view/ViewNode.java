package io.github.crafteve.idlefactory.view;

import java.util.List;
import java.util.UUID;

/**
 * 视图表里的一个节点行：节点自身字段 + 聚合后的规则 + 成员机器。
 *
 * <p>节点是视图表的行单位，也是被动 IO 终端的分块单位（草稿 23：节点一行、规则聚合）。
 * 规则与机器都不可变，构造时做防御性拷贝。</p>
 */
public record ViewNode(ViewNodeCore core, List<ViewIoRule> rules, List<ViewMachine> machines) {

    public ViewNode {
        rules = List.copyOf(rules);
        machines = List.copyOf(machines);
    }

    /**
     * 节点在干什么。
     *
     * <p>取值保留 {@code INPUT_OK}（ADR-040 / ADR-041 的"可输入"语义），共 8 个；顺序即
     * 聚合时"最需要管的那台"的判定顺序，客户端的状态色按它查表。</p>
     */
    public enum Activity {
        /** 有成员正在搬运。 */
        RUNNING,
        /** 机器有容量、仓库有料，可以输入。 */
        INPUT_OK,
        /** 明确空闲。 */
        IDLE,
        /** 输入规则缺料，等仓库。 */
        WAITING_MATERIAL,
        /** 机器满了或拒收，输入进不去。 */
        INPUT_BLOCKED,
        /** 全员离线或健康过期。 */
        OFFLINE,
        /** 节点没有规则。 */
        UNCONFIGURED,
        /** 没有足够事实判断。 */
        UNKNOWN
    }

    /** 节点 id。 */
    public UUID nodeId() {
        return this.core.nodeId();
    }

    /** 成员机器台数。 */
    public int machineCount() {
        return this.machines.size();
    }

    /** 输入 + 输出规则总数。 */
    public int ruleCount() {
        return this.rules.size();
    }

    /**
     * 删除前要不要二次确认。
     *
     * <p>只有"接入时自动补的 FE 输入规则"不算玩家配置：这种节点点删除直接删，规则反正会在下次
     * 接机器时再补回来。样板不算玩家配置——删节点时样板会退回玩家背包，不会丢。</p>
     *
     * @return 有样板、或有玩家自己配的规则
     */
    public boolean configured() {
        return this.core.patternCount() > 0
                || (this.ruleCount() > 0 && !this.core.autoEnergyOnly());
    }
}
