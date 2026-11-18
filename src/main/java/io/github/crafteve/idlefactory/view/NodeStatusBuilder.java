package io.github.crafteve.idlefactory.view;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.crafteve.idlefactory.observation.NodeHealth;

/**
 * 节点状态的唯一聚合实现：成员机器事实 → {@link NodeStatus}。
 *
 * <p>规则固定，且全部用 any / all 表达，因此<b>结果与成员登记顺序无关</b>：</p>
 * <ol>
 *   <li>{@code probed}：全部成员都探过才算探过；</li>
 *   <li>{@code stale}：任一成员过期就算过期；</li>
 *   <li>{@code health}：全在线＝在线、部分在线＝缺机、全离线＝失联；</li>
 *   <li>{@code totalState}：没规则＝无规则；有人堵塞＝输入堵塞；有人在干活＝运行；
 *       有人缺料＝仓库缺料；有人可输入＝可输入；其余都空闲＝空闲；否则未知。
 *       离线 / 过期成员不参与这一步。</li>
 * </ol>
 *
 * <p>服务端只在这里算一次，结果随快照下发；客户端只读，不再自己拼。</p>
 */
public final class NodeStatusBuilder {
    private NodeStatusBuilder() {
        // 纯静态聚合
    }

    /**
     * 聚合一个节点的状态。
     *
     * <p>规则数是节点级事实，由调用方传入，resolver 不从成员里挑任何一个字段——否则又回到
     * 「拿第一台拼」的老路。</p>
     *
     * @param ruleCount 节点 IO 规则总数（节点级，成员共用）
     * @param members   该节点的全部成员机器视图；空列表按「没有成员」处理
     * @return 节点状态
     */
    public static NodeStatus resolve(int ruleCount, List<MachineSnapshot> members) {
        if (members.isEmpty()) {
            // 没有成员就没有任何可观测事实：probed 必须是 false，总状态按离线兜底。
            return new NodeStatus(false, false, NodeHealth.OFFLINE, TotalState.OFFLINE);
        }

        boolean allProbed = true;
        boolean anyStale = false;
        int online = 0;
        boolean blocked = false;
        boolean running = false;
        boolean waitingMaterial = false;
        boolean inputOk = false;
        boolean idle = false;
        for (MachineSnapshot member : members) {
            if (!member.probed()) {
                allProbed = false;
            }
            if (member.stale()) {
                anyStale = true;
            }
            if (!member.health().isOnline()) {
                continue;
            }
            online++;
            switch (member.totalState()) {
                case INPUT_BLOCKED -> blocked = true;
                case RUNNING -> running = true;
                case WAITING_MATERIAL -> waitingMaterial = true;
                case INPUT_OK -> inputOk = true;
                case IDLE -> idle = true;
                case OFFLINE, UNCONFIGURED, UNKNOWN -> {
                    // 在线成员不应给出这三档；出现时不计入"在干什么"的判定。
                }
            }
        }

        NodeHealth health = online == 0 ? NodeHealth.OFFLINE
                : online == members.size() ? NodeHealth.ONLINE : NodeHealth.PARTIAL;
        TotalState totalState;
        if (online == 0) {
            // 全员离线时"在干什么"没有意义：直接给 OFFLINE，别把 OFFLINE + UNKNOWN 的组合丢给下游。
            totalState = TotalState.OFFLINE;
        } else if (ruleCount == 0) {
            totalState = TotalState.UNCONFIGURED;
        } else if (blocked) {
            totalState = TotalState.INPUT_BLOCKED;
        } else if (running) {
            totalState = TotalState.RUNNING;
        } else if (waitingMaterial) {
            totalState = TotalState.WAITING_MATERIAL;
        } else if (inputOk) {
            totalState = TotalState.INPUT_OK;
        } else if (idle) {
            totalState = TotalState.IDLE;
        } else {
            totalState = TotalState.UNKNOWN;
        }
        return new NodeStatus(allProbed, anyStale, health, totalState);
    }

    /**
     * 按节点把机器视图分组；机器表节点状态与节点表成员查找共用这一份分组。
     *
     * <p>包内可见：只给同包的快照构建器用，不对外暴露可变的中间表。</p>
     *
     * @param machines 整厂机器视图
     * @return 节点 id → 该节点的成员视图；顺序按机器表首次出现
     */
    public static Map<UUID, List<MachineSnapshot>> groupByNode(List<MachineSnapshot> machines) {
        Map<UUID, List<MachineSnapshot>> grouped = new LinkedHashMap<>();
        for (MachineSnapshot machine : machines) {
            grouped.computeIfAbsent(machine.nodeId(), ignored -> new ArrayList<>()).add(machine);
        }
        return grouped;
    }

    /**
     * 按节点分组聚合，给机器表快照附带节点状态用。
     *
     * @param machines 整厂机器视图
     * @return 节点 id → 节点状态；顺序按机器表首次出现
     */
    public static Map<UUID, NodeStatus> byNode(List<MachineSnapshot> machines) {
        Map<UUID, List<MachineSnapshot>> grouped = groupByNode(machines);
        Map<UUID, NodeStatus> statuses = new LinkedHashMap<>(grouped.size());
        for (Map.Entry<UUID, List<MachineSnapshot>> entry : grouped.entrySet()) {
            // 机器表把节点规则数冗余在每台机器上，组内取值相同；这里只读这个节点级字段，
            // 不再让 resolver 去挑成员。
            int ruleCount = entry.getValue().get(0).ruleCount();
            statuses.put(entry.getKey(), resolve(ruleCount, entry.getValue()));
        }
        return statuses;
    }
}