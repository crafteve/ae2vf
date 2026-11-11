package io.github.crafteve.idlefactory.factory.machine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.crafteve.idlefactory.factory.probe.BlockFacts;

/**
 * 全部机器的状态集合。
 *
 * <p>每 tick 由编排层喂一次「当前被配置引用的机器 + 观测事实」：新机器按 CONFIG_CHANGED 建条目，
 * 已有机器按 OBSERVED 重算；本轮没出现的条目说明机器已被删除，在这里释放。</p>
 *
 * <p>状态不写档，只在内存里活；索引 byId 是 O(1)，没有每 tick 新建集合。</p>
 */
public final class MachineStateLibrary {
    private final Map<UUID, MachineState> states = new HashMap<>();
    /** 本轮出现过的机器 id，用于回收已删除机器的条目。 */
    private final Set<UUID> seen = new HashSet<>();

    /** 开始一轮刷新：清空「本轮见过」标记。 */
    public void beginRefresh() {
        this.seen.clear();
    }

    /**
     * 喂一台被配置引用的机器。
     *
     * @param machine 机器
     * @param source  观测事实来源
     */
    public void observed(Machine machine, BlockFacts.Source source) {
        this.seen.add(machine.id());
        MachineState state = this.states.get(machine.id());
        MachineSignal signal = state == null ? MachineSignal.CONFIG_CHANGED : MachineSignal.OBSERVED;
        if (state == null) {
            state = new MachineState();
            this.states.put(machine.id(), state);
        }
        state.apply(signal, source.at(machine.block()));
    }

    /** 结束一轮刷新：释放本轮没出现的机器状态。 */
    public void endRefresh() {
        this.states.keySet().removeIf(id -> !this.seen.contains(id));
    }

    /**
     * 取一台机器的状态。
     *
     * @param machineId 机器 id
     * @return 状态；没有条目时返回 {@link MachineStatus#UNSEEN}
     */
    public MachineStatus status(UUID machineId) {
        MachineState state = this.states.get(machineId);
        return state == null ? MachineStatus.UNSEEN : state.status();
    }

    /** 清空全部状态（服务器停止时）。 */
    public void clear() {
        this.states.clear();
        this.seen.clear();
    }
}
