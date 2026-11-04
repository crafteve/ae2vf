package io.github.crafteve.idlefactory.factory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.machine.BlockingMode;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.probe.Health;
import io.github.crafteve.idlefactory.factory.storage.StorageMode;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * 把工厂的机器库整理成节点管理终端直接可用的视图。
 *
 * <p>当前数据模型是「1 台机器 = 1 个节点」，所以一个 {@link NodeView} 只包一台机器、一个方块。
 * 视图按节点 → 成员 → 方块三层建模：堆叠与多方块落地时只换 {@link #members} 的来源，协议与
 * 界面不用再改形状。</p>
 *
 * <p>状态判定不在这里重写：直接复用 {@link FactorySnapshotBuilder#machines} 的结果，
 * 保证节点终端与被动 IO 终端对同一台机器显示同一个状态。</p>
 */
public final class FactoryNodeSnapshotBuilder {
    private FactoryNodeSnapshotBuilder() {
        // 纯静态视图组装
    }

    /**
     * 一个节点在节点管理终端里的样子。
     *
     * @param nodeId         节点 id；1:1 阶段等于机器 id
     * @param name           玩家起的名字；{@code null} 表示用方块名
     * @param blockId        主方块 id；还没观测到时为 {@code null}
     * @param machineMissing 方块是否已经消失
     * @param health         观测结论
     * @param totalState     与被动 IO 终端一致的总状态
     * @param ruleCount      IO 规则总数
     * @param patternCount   已放样板数
     * @param provider       provider 组的状态
     * @param members        节点下的机器；当前恒为 1 台
     */
    public record NodeView(UUID nodeId, @Nullable String name,
            @Nullable ResourceLocation blockId, boolean machineMissing, Health health,
            FactorySnapshotBuilder.TotalState totalState, int ruleCount, int patternCount,
            ProviderView provider, List<MemberView> members) {

        public NodeView {
            members = List.copyOf(members);
        }

        /** 节点下的机器台数。 */
        public int machineCount() {
            return this.members.size();
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
     * @param available provider 是否可用；非 ME、仓库离线、失联时为 {@code false}
     * @param blocking  阻挡开关
     */
    public record ProviderView(boolean available, BlockingMode blocking) {
    }

    /**
     * 节点下的一台机器。
     *
     * @param machineId      机器 id
     * @param dimension      机器所在维度
     * @param blocks         机器占用的方块；{@code blocks.get(0)} 是主方块
     * @param blockId        观测到的方块 id
     * @param machineMissing 方块是否已经消失
     * @param health         观测结论
     */
    public record MemberView(UUID machineId, ResourceLocation dimension, List<BlockPos> blocks,
            @Nullable ResourceLocation blockId, boolean machineMissing, Health health) {

        public MemberView {
            blocks = List.copyOf(blocks);
        }

        /** 主方块坐标；没有方块时用最低坐标兜底。 */
        @Nullable
        public BlockPos primaryPos() {
            return this.blocks.isEmpty() ? null : this.blocks.get(0);
        }
    }

    /**
     * 生成节点视图列表，顺序与机器库登记顺序一致。
     *
     * @param factory      工厂
     * @param observations 观测服务；{@code null} 时按未探测处理
     * @param now          当前服务端 tick
     * @return 节点视图
     */
    public static List<NodeView> nodes(Factory factory,
            @Nullable io.github.crafteve.idlefactory.factory.observation.ObservationService observations,
            long now) {
        List<FactorySnapshotBuilder.MachineView> machines =
                FactorySnapshotBuilder.machines(factory, observations, now);
        List<NodeView> nodes = new ArrayList<>(machines.size());
        boolean meStorage = factory.config().storageMode() == StorageMode.ME;
        boolean storageOnline = factory.live().storageOnline();
        for (FactorySnapshotBuilder.MachineView view : machines) {
            Optional<Machine> found = factory.config().machines().byId(view.ref().id());
            if (found.isEmpty()) {
                continue;
            }
            Machine machine = found.get();
            boolean available = meStorage && storageOnline
                    && !view.machineMissing() && view.health() == Health.PRESENT;
            MemberView member = new MemberView(machine.id(), view.ref().dimension().location(),
                    List.of(view.ref().pos()), view.blockId(), view.machineMissing(), view.health());
            nodes.add(new NodeView(machine.id(), machine.name(), view.blockId(),
                    view.machineMissing(), view.health(), view.totalState(), view.ruleCount(),
                    machine.provider().patternCount(),
                    new ProviderView(available, machine.provider().blocking()), List.of(member)));
        }
        return nodes;
    }
}
