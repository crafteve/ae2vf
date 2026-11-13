package io.github.crafteve.idlefactory.factory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.io.DispatchOrder;
import io.github.crafteve.idlefactory.factory.io.DispatchQueue;
import io.github.crafteve.idlefactory.factory.io.IoForm;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.node.Multiplier;
import io.github.crafteve.idlefactory.factory.io.DispatchMode;
import io.github.crafteve.idlefactory.factory.io.BlockingMode;
import io.github.crafteve.idlefactory.factory.node.Node;
import io.github.crafteve.idlefactory.factory.node.NodeManager;
import io.github.crafteve.idlefactory.factory.node.NodeRuntime;
import io.github.crafteve.idlefactory.factory.storage.AeKeySupport;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;
import io.github.crafteve.idlefactory.factory.probe.MachineHealth;
import io.github.crafteve.idlefactory.factory.probe.NodeHealth;
import io.github.crafteve.idlefactory.factory.storage.StorageMode;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * 把工厂的节点库整理成节点管理终端直接可用的视图。
 *
 * <p>视图按节点 → 成员 → 方块三层建模：{@code NodeView} 带节点状态、类型方块、provider 与成员；
 * {@code MemberView} 带机器健康与唯一的方块位置。节点状态由 {@link NodeStatusResolver}
 * 一处聚合：探没过期看全体成员，在干什么按最需要管的那台，结果与成员登记顺序无关。</p>
 */
public final class FactoryNodeSnapshotBuilder {
    private FactoryNodeSnapshotBuilder() {
        // 纯静态视图组装
    }

    /**
     * 一个节点在节点管理终端里的样子。
     *
     * @param nodeId       节点 id
     * @param name         玩家起的名字；{@code null} 表示用方块名
     * @param typeBlockId  类型方块 id（节点图标 / 类型判定）
     * @param status       节点状态：服务端唯一聚合结果，客户端只读
     * @param ruleCount        节点 IO 规则总数
     * @param patternCount     已放样板数
     * @param autoEnergyOnly   规则里只有「接入时自动补的 FE 输入规则」；这种节点删除不用二次确认
     * @param pendingCount     待写入队列的单数
     * @param pendingOldestAt  队首入队时刻；队列空时为 -1
     * @param provider         provider 组的状态
     * @param members          节点下的机器
     */
    public record NodeView(java.util.UUID nodeId, @Nullable String name,
            ResourceLocation typeBlockId, NodeStatus status, int ruleCount, int patternCount,
            boolean autoEnergyOnly, int pendingCount, long pendingOldestAt, ProviderView provider,
            List<MemberView> members) {

        public NodeView {
            members = List.copyOf(members);
        }

        /** 节点下的机器台数。 */
        public int machineCount() {
            return this.members.size();
        }

        /**
         * 删除前要不要二次确认。
         *
         * <p>只有「接入时自动补的 FE 输入规则」不算玩家配置：这种节点点删除直接删，
         * 规则反正会在下次接机器时再补回来。</p>
         *
         * @return 有样板、或有玩家自己配的规则
         */
        public boolean configured() {
            return this.patternCount > 0 || (this.ruleCount > 0 && !this.autoEnergyOnly);
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
     * @param block     机器占用的方块（一台机器只有一个方块）
     * @param blockId   观测到的方块 id
     * @param health    机器健康
     * @param probed    是否探测过
     * @param stale     健康是否过期
     */
    public record MemberView(java.util.UUID machineId, BlockKey block,
            @Nullable ResourceLocation blockId, MachineHealth health, boolean probed,
            boolean stale) {

        /** 机器方块。 */
        public BlockKey primaryBlock() {
            return this.block;
        }

        /** 方块坐标。 */
        public BlockPos primaryPos() {
            return this.block.pos();
        }

        /** 方块所在维度。 */
        public ResourceLocation dimensionId() {
            return this.block.dimension().location();
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
            @Nullable IoForm form, @Nullable NodeManager nodeManager, long now) {
        List<FactorySnapshotBuilder.MachineView> machineViews =
                FactorySnapshotBuilder.machines(factory, observations, form, now);
        // 分组只由 NodeStatusResolver 一处实现：机器表的节点状态与节点表的成员查找共用同一份分组。
        Map<java.util.UUID, List<FactorySnapshotBuilder.MachineView>> byNode =
                NodeStatusResolver.groupByNode(machineViews);
        List<NodeView> nodes = new ArrayList<>(factory.config().nodes().size());
        boolean meStorage = factory.config().storageMode() == StorageMode.ME;
        boolean storageOnline = factory.live().storageOnline();
        for (Node node : factory.config().nodes().all()) {
            List<FactorySnapshotBuilder.MachineView> views =
                    byNode.getOrDefault(node.id(), List.of());
            // 节点状态只由 NodeStatusResolver 一处聚合：探没过期看全体，在干什么按最需要管的那台。
            NodeStatus status = NodeStatusResolver.resolve(node.ruleCount(), views);
            List<MemberView> members = new ArrayList<>(node.machineCount());
            for (Machine machine : node.machines()) {
                FactorySnapshotBuilder.MachineView view = null;
                for (FactorySnapshotBuilder.MachineView candidate : views) {
                    if (candidate.machineId().equals(machine.id())) {
                        view = candidate;
                        break;
                    }
                }
                members.add(view == null
                        ? new MemberView(machine.id(), machine.block(), null, MachineHealth.OFFLINE,
                                false, false)
                        : new MemberView(machine.id(), machine.block(), view.blockId(),
                                view.health(), view.probed(), view.stale()));
            }
            boolean available = meStorage && storageOnline && status.health() != NodeHealth.OFFLINE;
            NodeRuntime runtime = nodeManager == null ? null : nodeManager.node(node.id());
            // 规则里只有自动补的 FE 输入规则：这种节点删除不需要二次确认。
            // 样板不算玩家配置——删节点时样板会退回玩家背包，不会丢。
            boolean autoEnergyOnly = node.io().outputs().isEmpty()
                    && node.io().inputs().stream().allMatch(spec ->
                            AeKeySupport.isEnergy(spec.key()) && spec.face() == null);
            DispatchQueue queue = runtime == null ? null : runtime.pendingQueue();
            int pendingCount = queue == null ? 0 : queue.size();
            DispatchOrder head = queue == null ? null : queue.first();
            long pendingOldestAt = head == null ? -1L : head.enqueuedAt();
            nodes.add(new NodeView(node.id(), node.name(), node.typeBlockId(), status,
                    node.ruleCount(), node.patternCount(), autoEnergyOnly, pendingCount,
                    pendingOldestAt,
                    new ProviderView(available, node.provider().blocking(),
                            node.provider().multiplier(), node.provider().dispatch()),
                    members));
        }
        return nodes;
    }

}