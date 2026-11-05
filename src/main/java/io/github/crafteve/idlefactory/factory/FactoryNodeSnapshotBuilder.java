package io.github.crafteve.idlefactory.factory;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.node.Multiplier;
import io.github.crafteve.idlefactory.factory.node.DispatchMode;
import io.github.crafteve.idlefactory.factory.node.BlockingMode;
import io.github.crafteve.idlefactory.factory.node.Node;
import io.github.crafteve.idlefactory.factory.observation.Observation;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;
import io.github.crafteve.idlefactory.factory.probe.Health;
import io.github.crafteve.idlefactory.factory.probe.MachineHealth;
import io.github.crafteve.idlefactory.factory.probe.NodeHealth;
import io.github.crafteve.idlefactory.factory.storage.StorageMode;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * 把工厂的节点库整理成节点管理终端直接可用的视图。
 *
 * <p>视图按节点 → 成员 → 方块三层建模：{@code NodeView} 带节点健康、类型方块、{@code probed}
 * 与 {@code stale}；{@code MemberView} 带机器健康与「每块都写维度 + 坐标」的方块表。
 * 健康由观测快照机械聚合：P1b 只区分在线 / 离线，P2 换成 {@code NodeHealthResolver}。</p>
 */
public final class FactoryNodeSnapshotBuilder {
    private FactoryNodeSnapshotBuilder() {
        // 纯静态视图组装
    }

    /**
     * 一个节点在节点管理终端里的样子。
     *
     * @param nodeId            节点 id
     * @param name              玩家起的名字；{@code null} 表示用方块名
     * @param typeBlockId       类型方块 id（节点图标 / 类型判定）
     * @param health            节点健康
     * @param probed            是否所有机器都探测过
     * @param stale             是否有机器健康过期
     * @param totalState        与被动 IO 终端一致的总状态
     * @param ruleCount         节点 IO 规则总数
     * @param patternCount      已放样板数
     * @param provider          provider 组的状态
     * @param members           节点下的机器
     */
    public record NodeView(java.util.UUID nodeId, @Nullable String name,
            ResourceLocation typeBlockId, NodeHealth health, boolean probed, boolean stale,
            FactorySnapshotBuilder.TotalState totalState, int ruleCount, int patternCount,
            ProviderView provider, List<MemberView> members) {

        public NodeView {
            members = List.copyOf(members);
        }

        /** 节点下的机器台数。 */
        public int machineCount() {
            return this.members.size();
        }

        /** 在线机器台数。 */
        public int onlineMachineCount() {
            int count = 0;
            for (MemberView member : this.members) {
                if (member.health().isOnline()) {
                    count++;
                }
            }
            return count;
        }

        /** 节点占用的方块总数。 */
        public int blockCount() {
            int total = 0;
            for (MemberView member : this.members) {
                total += member.blocks().size();
            }
            return total;
        }

        /** 是否已有 IO 规则或样板；删除确认弹窗用。 */
        public boolean configured() {
            return this.ruleCount > 0 || this.patternCount > 0;
        }
    }

    /**
     * provider 组的状态。
     *
     * @param available   provider 是否可用；非 ME、仓库离线、全部机器离线时为 {@code false}
     * @param blocking    阻挡开关
     * @param multiplier  倍率
     * @param dispatch    派单模式
     */
    public record ProviderView(boolean available, BlockingMode blocking, Multiplier multiplier,
            DispatchMode dispatch) {
    }

    /**
     * 节点下的一台机器。
     *
     * @param machineId 机器 id
     * @param blocks    机器占用的方块，每块都带维度；{@code blocks.get(0)} 是类型方块
     * @param blockId   观测到的类型方块 id
     * @param health    机器健康
     * @param probed    是否所有方块都探测过
     * @param stale     是否有方块健康过期
     */
    public record MemberView(java.util.UUID machineId, List<BlockKey> blocks,
            @Nullable ResourceLocation blockId, MachineHealth health, boolean probed,
            boolean stale) {

        public MemberView {
            blocks = List.copyOf(blocks);
        }

        /** 类型 / 展示方块；没有方块时为 {@code null}。 */
        @Nullable
        public BlockKey primaryBlock() {
            return this.blocks.isEmpty() ? null : this.blocks.get(0);
        }

        /** 类型方块坐标；没有方块时为 {@code null}。 */
        @Nullable
        public BlockPos primaryPos() {
            BlockKey primary = primaryBlock();
            return primary == null ? null : primary.pos();
        }

        /** 类型方块所在维度；没有方块时为 {@code null}。 */
        @Nullable
        public ResourceLocation dimensionId() {
            BlockKey primary = primaryBlock();
            return primary == null ? null : primary.dimension().location();
        }
    }

    /**
     * 生成节点视图列表，顺序与节点库登记顺序一致。
     *
     * @param factory      工厂
     * @param observations 观测服务；{@code null} 时按未探测处理
     * @param now          当前服务端 tick
     * @return 节点视图
     */
    public static List<NodeView> nodes(Factory factory, @Nullable ObservationService observations,
            long now) {
        List<FactorySnapshotBuilder.MachineView> machineViews =
                FactorySnapshotBuilder.machines(factory, observations, now);
        List<NodeView> nodes = new ArrayList<>(factory.config().nodes().size());
        boolean meStorage = factory.config().storageMode() == StorageMode.ME;
        boolean storageOnline = factory.live().storageOnline();
        for (Node node : factory.config().nodes().all()) {
            List<MemberView> members = new ArrayList<>(node.machineCount());
            boolean allProbed = true;
            boolean anyStale = false;
            boolean anyOnline = false;
            FactorySnapshotBuilder.TotalState state = FactorySnapshotBuilder.TotalState.OFFLINE;
            for (Machine machine : node.machines()) {
                MachineHealth health = MachineHealth.OFFLINE;
                boolean probed = false;
                boolean memberStale = false;
                ResourceLocation blockId = null;
                for (FactorySnapshotBuilder.MachineView view : machineViews) {
                    if (view.machineId().equals(machine.id())) {
                        health = view.health();
                        probed = view.probed();
                        memberStale = view.stale();
                        blockId = view.blockId();
                        state = view.totalState();
                        break;
                    }
                }
                if (!probed) {
                    allProbed = false;
                }
                if (memberStale) {
                    anyStale = true;
                }
                if (health.isOnline()) {
                    anyOnline = true;
                }
                members.add(new MemberView(machine.id(), machine.blocks(), blockId, health, probed,
                        memberStale));
            }
            NodeHealth health = nodeHealth(members);
            if (!anyOnline) {
                state = FactorySnapshotBuilder.TotalState.OFFLINE;
            }
            boolean available = meStorage && storageOnline && anyOnline;
            nodes.add(new NodeView(node.id(), node.name(), node.typeBlockId(), health, allProbed,
                    anyStale, state, node.ruleCount(), node.patternCount(),
                    new ProviderView(available, node.provider().blocking(),
                            node.provider().multiplier(), node.provider().dispatch()),
                    members));
        }
        return nodes;
    }

    /**
     * 按成员机器聚合节点健康：全部在线 = 在线，部分在线 = 缺机，没有一台在线 = 离线。
     *
     * @param members 成员视图
     * @return 节点健康
     */
    private static NodeHealth nodeHealth(List<MemberView> members) {
        int online = 0;
        for (MemberView member : members) {
            if (member.health().isOnline()) {
                online++;
            }
        }
        if (online == 0) {
            return NodeHealth.OFFLINE;
        }
        return online == members.size() ? NodeHealth.ONLINE : NodeHealth.PARTIAL;
    }

    /**
     * 观测方块级健康是否「探测过」。
     *
     * <p>保留给 P2 的 resolver 使用；P1b 直接按成员健康聚合。</p>
     *
     * @param observation 观测
     * @return 是否探测过
     */
    static boolean probed(Observation observation) {
        return observation.health() != Health.UNKNOWN;
    }
}
