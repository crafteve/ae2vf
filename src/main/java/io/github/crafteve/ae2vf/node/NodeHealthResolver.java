package io.github.crafteve.ae2vf.node;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.crafteve.ae2vf.machine.Machine;
import io.github.crafteve.ae2vf.observation.BlockFacts;
import io.github.crafteve.ae2vf.machine.MachineHealth;
import io.github.crafteve.ae2vf.observation.NodeHealth;

import net.minecraft.resources.ResourceLocation;

/**
 * 健康解析器：节点 + 方块观测快照 + 时间 → 机器 / 节点健康、{@code probed}、{@code stale}。
 *
 * <p>纯函数，不依赖运行时、不读配置、不探世界。过期判定只调用
 * {@link BlockFacts#isStale(long, long)}，本类不重复实现公式。</p>
 *
 * <p>语义：机器所有方块 PRESENT 且都探测过才算在线；任一方块不可用即离线。节点按机器聚合：
 * 全部在线 = 在线，部分在线 = 缺机，没有一台在线 = 离线。「没探过」与「探过但过期」是独立
 * 布尔，不塞进健康枚举。</p>
 */
public final class NodeHealthResolver {
    private NodeHealthResolver() {
        // 纯函数工具类
    }

    /**
     * 一台机器的健康结论。
     *
     * @param machine 机器
     * @param health  机器健康
     * @param probed  是否所有方块都探测过
     * @param stale   是否任一方块健康过期
     */
    public record MachineState(Machine machine, MachineHealth health, boolean probed,
            boolean stale) {
    }

    /**
     * 一个节点的健康结论。
     *
     * @param health   节点健康
     * @param probed   是否所有机器都探测过
     * @param stale    是否任一机器健康过期
     * @param machines 每台机器的结论，顺序与节点机器列表一致
     */
    public record Snapshot(NodeHealth health, boolean probed, boolean stale,
            List<MachineState> machines) {

        public Snapshot {
            machines = List.copyOf(machines);
        }

        /**
         * 按机器 id 取成员结论。
         *
         * @param machineId 机器 id
         * @return 成员结论；不属于本节点时为 {@code null}
         */
        public MachineState machine(UUID machineId) {
            for (MachineState state : this.machines) {
                if (state.machine().id().equals(machineId)) {
                    return state;
                }
            }
            return null;
        }
    }

    /**
     * 解析一个节点。
     *
     * @param facts            方块观测事实来源
     * @param now              当前游戏时间
     * @param staleHealthTicks 过期阈值（tick）
     * @return 节点与成员的结论
     */
    public static Snapshot resolve(Node node, BlockFacts.Source facts,
            long now, long staleHealthTicks) {
        List<MachineState> machines = new ArrayList<>(node.machineCount());
        int online = 0;
        boolean allProbed = true;
        boolean anyStale = false;
        for (Machine machine : node.machines()) {
            MachineState state = resolveMachine(machine, node.typeBlockId(), facts, now,
                    staleHealthTicks);
            machines.add(state);
            if (state.health().isOnline()) {
                online++;
            }
            if (!state.probed()) {
                allProbed = false;
            }
            if (state.stale()) {
                anyStale = true;
            }
        }
        NodeHealth health;
        if (online == 0) {
            health = NodeHealth.OFFLINE;
        } else if (online == machines.size()) {
            health = NodeHealth.ONLINE;
        } else {
            health = NodeHealth.PARTIAL;
        }
        return new Snapshot(health, allProbed, anyStale, machines);
    }

    /**
     * 解析一台机器：所有方块 PRESENT、类型方块 id 匹配、且非 stale 才算在线。
     *
     * <p>过期不算「在」：宁可暂时不跑 IO，也不拿过期健康做搬运判断。类型方块 id 与节点类型
     * 不一致时整台机器离线——这正是「部分掉线后原地换方块不认」的实现点。</p>
     *
     * @param machine          机器
     * @param expectedTypeId   节点类型方块 id
     * @param facts            方块观测事实来源
     * @param now              当前观测时钟
     * @param staleHealthTicks 过期阈值
     * @return 机器结论
     */
    public static MachineState resolveMachine(Machine machine, ResourceLocation expectedTypeId,
            BlockFacts.Source facts, long now, long staleHealthTicks) {
        boolean probed = true;
        boolean stale = false;
        boolean online = true;
        BlockFacts observation = facts.at(machine.block());
        if (!observation.probed()) {
            probed = false;
            online = false;
        } else {
            if (observation.isStale(now, staleHealthTicks)) {
                stale = true;
                online = false;
            }
            if (!observation.present()) {
                online = false;
            }
            if (!expectedTypeId.equals(observation.blockId())) {
                // 方块还是「有东西」，但已经不是节点认的那种机器：不认。
                online = false;
            }
        }
        return new MachineState(machine, online ? MachineHealth.ONLINE : MachineHealth.OFFLINE,
                probed, stale);
    }
}
