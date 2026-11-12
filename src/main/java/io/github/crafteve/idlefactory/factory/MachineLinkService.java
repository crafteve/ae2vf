package io.github.crafteve.idlefactory.factory;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.io.PassiveIoRunner;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.node.Node;
import io.github.crafteve.idlefactory.factory.node.NodeLibrary;
import io.github.crafteve.idlefactory.factory.node.NodeManager;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * 节点接线：节点增删改的唯一副作用出口，以及删除时的样板退回与执行器清理。
 *
 * <p>真正写节点库的是 {@link #applyTopologyChange}：新建走 {@code add}、删除走 {@code remove}、
 * 改成员走 {@code replace}，成功后统一拆 / 刷新虚拟节点并清执行器瞬态。绑定杖提交与终端删节点
 * 都只走这一条路，删机器的副作用只有一套；规则与样板是否允许删由
 * {@link WandValidator} 在写库前判，本类不重复判。</p>
 *
 * <p>样板只在<b>整个节点被删除</b>时退回；堆叠节点少一台机器保留样板（B1）。本类不落盘：
 * 批量句柄由调用方传入，整批结束后由调用方 {@code close()} 一次。</p>
 */
final class MachineLinkService {
    private final FactoryManager manager;
    private final NodeManager nodeManager;
    private final PassiveIoRunner nodeIoRunner;
    private final NodeLibraryService nodeLibrary;
    private final MinecraftServer server;

    /**
     * @param manager      工厂档案柜
     * @param nodeManager  虚拟节点管理器
     * @param nodeIoRunner 执行器
     * @param nodeLibrary  节点库写入口（落盘由它统一触发）
     * @param server       服务端
     */
    MachineLinkService(FactoryManager manager, NodeManager nodeManager,
            PassiveIoRunner nodeIoRunner, NodeLibraryService nodeLibrary,
            MinecraftServer server) {
        this.manager = manager;
        this.nodeManager = nodeManager;
        this.nodeIoRunner = nodeIoRunner;
        this.nodeLibrary = nodeLibrary;
        this.server = server;
    }

    /**
     * 一次拓扑变更的结果。
     *
     * @param deleted     是否真的写成功；{@code false} 表示节点库拒绝了这次写入，副作用一点没做
     * @param patterns    退回的样板数；只有整个节点被删除时才可能非零
     * @param nodeDeleted 是否连节点一起删掉了
     * @param result      节点库的原始结果，调用方据此映射文案与失败枚举
     */
    record TopologyOutcome(boolean deleted, int patterns, boolean nodeDeleted,
            NodeLibrary.Result result) {
    }

    /**
     * 应用一次节点拓扑变更，是节点增删改的唯一副作用出口。
     *
     * <p>三种形态：{@code base == null} 是新建（只用 {@code candidate}）；{@code candidate == null}
     * 是删除整个节点；两者都非空是改成员。写入失败时不碰虚拟节点与执行器，返回
     * {@code deleted=false}，调用方按 {@code result} 决定文案。</p>
     *
     * @param factory           工厂
     * @param base              变更前的节点；新建时为 {@code null}
     * @param candidate         变更后的节点；删除节点时为 {@code null}
     * @param removedMachineIds 本次删掉的机器 id；删除节点时可为空，方法自己从 base 收集
     * @param receiver          样板优先接收者；{@code null} 表示找工厂拥有者
     * @param batch             本次操作的节点库批量句柄
     * @return 结果
     */
    TopologyOutcome applyTopologyChange(Factory factory, @Nullable Node base, @Nullable Node candidate,
            Collection<UUID> removedMachineIds, @Nullable ServerPlayer receiver,
            NodeLibraryService.Batch batch) {
        if (base == null) {
            if (candidate == null) {
                return new TopologyOutcome(false, 0, false, NodeLibrary.Result.NOT_FOUND);
            }
            NodeLibrary.Result result = batch.add(candidate);
            return new TopologyOutcome(result == NodeLibrary.Result.ADDED, 0, false, result);
        }
        if (candidate == null) {
            boolean removed = batch.remove(base.id());
            if (!removed) {
                return new TopologyOutcome(false, 0, false, NodeLibrary.Result.NOT_FOUND);
            }
            // 先拆节点：机器已经不归工厂管了，不能再让 CPU 往里派单
            this.nodeManager.removeNode(base.id());
            // 睡眠、面路由都是按机器 id 存的内存状态；不清掉，同 id 重连会继承旧睡眠
            for (Machine machine : base.machines()) {
                this.nodeIoRunner.forget(machine.id());
            }
            int returned = returnPatterns(factory, base, receiver);
            return new TopologyOutcome(true, returned, true, NodeLibrary.Result.REMOVED);
        }
        NodeLibrary.Result result = batch.replace(candidate);
        if (result != NodeLibrary.Result.REPLACED) {
            return new TopologyOutcome(false, 0, false, result);
        }
        // 成员变了：结构版本随之变化，对账会把节点整体重建，样板缓存也在那时重算。
        for (UUID machineId : removedMachineIds) {
            this.nodeIoRunner.forget(machineId);
        }
        return new TopologyOutcome(true, 0, false, result);
    }


    /**
     * 按节点 id 删除整个节点；界面节点卡片的删除入口。
     *
     * @param factory 工厂
     * @param nodeId  节点 id
     * @return 动作结果
     */
    public NodeActions.Result deleteNodeById(Factory factory, UUID nodeId) {
        Optional<Node> found = factory.config().nodes().byId(nodeId);
        if (found.isEmpty()) {
            return NodeActions.Result.err("找不到节点");
        }
        Node node = found.get();
        NodeLibraryService.Batch batch = this.nodeLibrary.begin(factory);
        TopologyOutcome outcome = applyTopologyChange(factory, node, null, List.of(), null, batch);
        if (!outcome.deleted()) {
            return NodeActions.Result.err("节点已不在节点库");
        }
        batch.close();
        String patternText = outcome.patterns() > 0
                ? "（退回 " + outcome.patterns() + " 块样板）" : "";
        return NodeActions.Result.ok("已删除节点 [" + nodeId.toString().substring(0, 8) + "]"
                + patternText);
    }

    /**
     * 把一个节点的样板退回玩家。
     *
     * <p>样板是玩家的东西，删节点不能吞掉：优先塞接收者（没有接收者就找工厂拥有者）的背包，
     * 塞不下或不在线就掉在机器坐标；机器所在区块没加载时退回接收者脚下，再不行落到主世界
     * 出生点，保证不丢。</p>
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

    /**
     * 掉一块样板：机器坐标优先，其次接收者脚下，最后主世界出生点。
     *
     * @param machine 机器
     * @param stack   样板（可能是背包塞不下的剩余）
     * @param target  在线玩家，可为 {@code null}
     */
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

    /** 工厂拥有者；不在线时为 {@code null}。 */
    @Nullable
    private ServerPlayer onlineOwner(Factory factory) {
        return this.server.getPlayerList().getPlayer(factory.config().owner());
    }
}
