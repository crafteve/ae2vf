package io.github.crafteve.idlefactory.factory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder.MachineView;
import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder.TotalState;
import io.github.crafteve.idlefactory.factory.probe.NodeHealth;

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
public final class NodeStatusResolver {
    private NodeStatusResolver() {
        // 纯静态聚合
    }

    /**
     * 聚合一个节点的状态。
     *
     * @param members 该节点的全部成员机器视图；空列表按「无成员」处理
     * @return 节点状态
     */
    public static NodeStatus resolve(List<MachineView> members) {
        if (members.isEmpty()) {
            // 正常流程里节点至少有一台机器；空列表按「离线且没配置」兜底，不参与成员顺序判断。
            return new NodeStatus(true, false, NodeHealth.OFFLINE, TotalState.OFFLINE);
        }

        boolean allProbed = true;
        boolean anyStale = false;
        int online = 0;
        boolean blocked = false;
        boolean running = false;
        boolean waitingMaterial = false;
        boolean inputOk = false;
        boolean idle = false;
        for (MachineView member : members) {
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
        int ruleCount = members.get(0).ruleCount();
        TotalState totalState;
        if (ruleCount == 0) {
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
     * 按节点分组聚合，给机器表快照附带节点状态用。
     *
     * @param machines 整厂机器视图
     * @return 节点 id → 节点状态；顺序按机器表首次出现
     */
    public static Map<UUID, NodeStatus> byNode(List<MachineView> machines) {
        Map<UUID, List<MachineView>> grouped = new LinkedHashMap<>();
        for (MachineView machine : machines) {
            grouped.computeIfAbsent(machine.nodeId(), ignored -> new ArrayList<>()).add(machine);
        }
        Map<UUID, NodeStatus> statuses = new LinkedHashMap<>(grouped.size());
        for (Map.Entry<UUID, List<MachineView>> entry : grouped.entrySet()) {
            statuses.put(entry.getKey(), resolve(entry.getValue()));
        }
        return statuses;
    }
}
