package io.github.crafteve.idlefactory.command;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.config.IdleFactoryConfig;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryLifecycle;
import io.github.crafteve.idlefactory.io.IoRunner;
import io.github.crafteve.idlefactory.location.BlockKey;
import io.github.crafteve.idlefactory.machine.Machine;
import io.github.crafteve.idlefactory.node.Node;
import io.github.crafteve.idlefactory.node.NodeLibrary;
import io.github.crafteve.idlefactory.node.NodeManager;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * 节点拓扑的唯一写入口：节点增删改的落盘、虚拟节点拆建与执行器瞬态清理都在这里。
 *
 * <p>写库前先按 config 软上限校验新增量；写入成功后才清执行器瞬态并推一次链接快照，
 * 失败时副作用一点不做。样板只在<b>整个节点被删除</b>时退回：堆叠节点少一台机器保留样板。</p>
 *
 * <p>本类不缓存任何状态，也不认识网络：回执由 {@link CommandApply} 映射成原因。</p>
 */
public final class CommandTopology {
    private final FactoryLifecycle runtime;
    private final NodeManager nodeManager;
    private final IoRunner nodeIoRunner;
    private final MinecraftServer server;

    /**
     * @param runtime      服务端运行时
     * @param nodeManager  虚拟节点管理器
     * @param nodeIoRunner 执行器
     * @param server       服务端
     */
    public CommandTopology(FactoryLifecycle runtime, NodeManager nodeManager, IoRunner nodeIoRunner,
            MinecraftServer server) {
        this.runtime = runtime;
        this.nodeManager = nodeManager;
        this.nodeIoRunner = nodeIoRunner;
        this.server = server;
    }

    /**
     * 一次拓扑变更的结果。
     *
     * @param applied     是否真的写成功
     * @param patterns    退回的样板数；只有整个节点被删除时才可能非零
     * @param nodeDeleted 是否连节点一起删掉了
     * @param result      节点库的原始结果
     */
    public record TopologyOutcome(boolean applied, int patterns, boolean nodeDeleted,
            NodeLibrary.Result result) {
    }

    /**
     * 应用一次节点拓扑变更。
     *
     * <p>三种形态：{@code base == null} 新建；{@code candidate == null} 删除整个节点；两者都非空
     * 改成员。失败时不碰虚拟节点与执行器。</p>
     *
     * @param factory   工厂
     * @param base      变更前的节点；新建时为 {@code null}
     * @param candidate 变更后的节点；删除节点时为 {@code null}
     * @param removed   本次删掉的机器 id；删除节点时可为空
     * @param receiver  样板优先接收者；{@code null} 表示找工厂拥有者
     * @return 结果
     */
    public TopologyOutcome apply(Factory factory, @Nullable Node base, @Nullable Node candidate,
            Collection<UUID> removed, @Nullable ServerPlayer receiver) {
        if (base == null) {
            if (candidate == null) {
                return new TopologyOutcome(false, 0, false, NodeLibrary.Result.NOT_FOUND);
            }
            if (overLimit(factory, candidate)) {
                return new TopologyOutcome(false, 0, false, NodeLibrary.Result.FULL);
            }
            NodeLibrary.Result result = factory.addNode(candidate);
            if (result == NodeLibrary.Result.ADDED) {
                this.runtime.markDirtyLinks(factory);
            }
            return new TopologyOutcome(result == NodeLibrary.Result.ADDED, 0, false, result);
        }
        if (candidate == null) {
            NodeLibrary.Result result = factory.removeNode(base.id());
            if (result != NodeLibrary.Result.REMOVED) {
                return new TopologyOutcome(false, 0, false, result);
            }
            // 先拆节点：机器已经不归工厂管了，不能再让 CPU 往里派单
            this.nodeManager.removeNode(base.id());
            for (Machine machine : base.machines()) {
                factory.analyzer().forgetMachine(machine.id());
                this.nodeIoRunner.forget(machine.id());
            }
            int returned = returnPatterns(factory, base, receiver);
            this.runtime.markDirtyLinks(factory);
            return new TopologyOutcome(true, returned, true, NodeLibrary.Result.REMOVED);
        }
        if (overLimit(factory, candidate)) {
            return new TopologyOutcome(false, 0, false, NodeLibrary.Result.FULL);
        }
        NodeLibrary.Result result = factory.replaceNode(candidate);
        if (result != NodeLibrary.Result.REPLACED) {
            return new TopologyOutcome(false, 0, false, result);
        }
        // 成员变了：结构版本随之变化，对账会把节点整体重建，样板缓存也在那时重算。
        for (UUID machineId : removed) {
            factory.analyzer().forgetMachine(machineId);
            this.nodeIoRunner.forget(machineId);
        }
        this.runtime.markDirtyLinks(factory);
        return new TopologyOutcome(true, 0, false, result);
    }

    /**
     * 按节点 id 删除整个节点。
     *
     * @param factory  工厂
     * @param nodeId   节点 id
     * @param receiver 样板优先接收者；{@code null} 表示用工厂拥有者
     * @return 回执
     */
    public CommandResult deleteNode(Factory factory, UUID nodeId, @Nullable ServerPlayer receiver) {
        Optional<Node> found = factory.config().nodes().byId(nodeId);
        if (found.isEmpty()) {
            return CommandResult.node(CommandReason.NODE_MISSING, nodeId);
        }
        TopologyOutcome outcome = this.apply(factory, found.get(), null, List.of(), receiver);
        return outcome.applied()
                ? CommandResult.node(CommandReason.OK, nodeId)
                : CommandResult.node(CommandReason.INTERNAL, nodeId);
    }

    /**
     * config 软上限校验：只看新增量是否超过有效上限。
     *
     * @param factory 工厂
     * @param node    待写入节点
     * @return 超限为 {@code true}
     */
    private static boolean overLimit(Factory factory, Node node) {
        var nodes = factory.config().nodes();
        boolean replacing = nodes.byId(node.id()).isPresent();
        if (!replacing && nodes.size() + 1 > IdleFactoryConfig.maxNodes()) {
            return true;
        }
        if (node.machineCount() > IdleFactoryConfig.maxMachinesPerNode()) {
            return true;
        }
        Node old = nodes.byId(node.id()).orElse(null);
        int machineDelta = node.machineCount() - (old == null ? 0 : old.machineCount());
        return nodes.totalMachineCount() + machineDelta > IdleFactoryConfig.maxTotalMachines();
    }

    /**
     * 把一个节点的样板退回玩家；塞不下就掉在机器坐标 / 玩家脚下 / 主世界出生点。
     *
     * @param factory  工厂
     * @param node     要被删掉的节点
     * @param receiver 优先接收者；{@code null} 表示用工厂拥有者
     * @return 退回去的样板数
     */
    private int returnPatterns(Factory factory, Node node, @Nullable ServerPlayer receiver) {
        List<ItemStack> patterns = node.provider().takePatterns();
        if (patterns.isEmpty()) {
            return 0;
        }
        ServerPlayer target = receiver != null ? receiver : onlineOwner(factory);
        Machine machine = node.machines().get(0);
        for (ItemStack stack : patterns) {
            if (target != null) {
                target.getInventory().add(stack);
            }
            if (!stack.isEmpty()) {
                dropPattern(machine, stack, target);
            }
        }
        return patterns.size();
    }

    private void dropPattern(Machine machine, ItemStack stack, @Nullable ServerPlayer target) {
        BlockKey typeBlock = machine.block();
        ServerLevel level = this.server.getLevel(typeBlock.dimension());
        if (level != null && level.isLoaded(typeBlock.pos())) {
            Block.popResource(level, typeBlock.pos(), stack);
            return;
        }
        if (target != null) {
            Block.popResource(target.serverLevel(), target.blockPosition(), stack);
            return;
        }
        ServerLevel overworld = this.server.overworld();
        Block.popResource(overworld, overworld.getSharedSpawnPos(), stack);
    }

    @Nullable
    private ServerPlayer onlineOwner(Factory factory) {
        return this.server.getPlayerList().getPlayer(factory.config().owner());
    }
}
