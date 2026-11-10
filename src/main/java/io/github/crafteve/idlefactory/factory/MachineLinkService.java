package io.github.crafteve.idlefactory.factory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.io.NodeIoRunner;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.node.Node;
import io.github.crafteve.idlefactory.factory.node.NodeManager;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * 节点接线：准星接入、断开、删除，以及删除时的样板退回与执行器清理。
 *
 * <p>它是节点库写操作的唯一出口：三处删除调用点（命令确认、界面删除、连锁断开）只负责取参与
 * 出文案，真正的副作用在 {@link #removeMachine} 里一次做完——退样板、移出节点库、拆虚拟节点、
 * 清执行器瞬态。删掉节点最后一台机器等于删节点。</p>
 *
 * <p>本类不持有运行时单例：档案柜、发布回调、节点与执行器都从构造注入。</p>
 */
final class MachineLinkService {
    private final FactoryManager manager;
    private final NodeManager nodeManager;
    private final NodeIoRunner nodeIoRunner;
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
            NodeIoRunner nodeIoRunner, NodeLibraryService nodeLibrary,
            MinecraftServer server) {
        this.manager = manager;
        this.nodeManager = nodeManager;
        this.nodeIoRunner = nodeIoRunner;
        this.nodeLibrary = nodeLibrary;
        this.server = server;
    }

    /**
     * 一次删除尝试的结果。
     *
     * @param deleted     是否真的删掉了；{@code false} 只表示「因策略要求未删」
     * @param rules       删除前的节点规则条数
     * @param patterns    退回的样板数
     * @param nodeDeleted 是否连节点一起删掉了（删的是最后一台机器）
     */
    record DeleteOutcome(boolean deleted, int rules, int patterns, boolean nodeDeleted) {
    }

    /**
     * 删除一台机器的唯一出口。
     *
     * <p>删掉节点最后一台机器时整节点删除；否则只把该机器从节点里摘掉。本方法不落盘：
     * 批量句柄由调用方传入，整批删除结束后由调用方 {@code close()} 一次。</p>
     *
     * @param factory  工厂
     * @param node     机器所属节点
     * @param machine  机器
     * @param receiver 样板优先接收者；{@code null} 表示找工厂拥有者
     * @param batch    本次操作的节点库批量句柄
     * @return 结果；{@code deleted=false} 表示机器还有规则、被跳过
     */
    DeleteOutcome removeMachine(Factory factory, Node node, Machine machine,
            @Nullable ServerPlayer receiver, NodeLibraryService.Batch batch) {
        int rules = node.ruleCount();
        // 规则不能批量丢：有规则的机器一律跳过，由玩家先清规则或直接删整个节点。
        if (rules > 0) {
            return new DeleteOutcome(false, rules, 0, false);
        }
        int returned = returnPatterns(factory, node, receiver);
        boolean nodeDeleted = node.machineCount() <= 1;
        if (nodeDeleted) {
            batch.remove(node.id());
        } else {
            batch.replace(node.withRemovedMachine(machine.id()));
        }
        // 先拆节点：机器已经不归工厂管了，不能再让 CPU 往里派单
        if (nodeDeleted) {
            this.nodeManager.removeNode(node.id());
        } else {
            this.nodeManager.refreshPatterns(node.id());
        }
        // 睡眠、面路由都是按机器 id 存的内存状态；不清掉，同 id 重连会继承旧睡眠
        this.nodeIoRunner.forget(machine.id());
        return new DeleteOutcome(true, rules, returned, nodeDeleted);
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
        int returned = returnPatterns(factory, node, null);
        NodeLibraryService.Batch batch = this.nodeLibrary.begin(factory);
        if (!batch.remove(nodeId)) {
            return NodeActions.Result.err("节点已不在节点库");
        }
        batch.close();
        this.nodeManager.removeNode(nodeId);
        for (Machine machine : node.machines()) {
            this.nodeIoRunner.forget(machine.id());
        }
        String patternText = returned > 0 ? "（退回 " + returned + " 块样板）" : "";
        return NodeActions.Result.ok("已删除节点 [" + nodeId.toString().substring(0, 8) + "]"
                + patternText);
    }

    /**
     * 把一个节点的样板退回玩家。
     *
     * <p>样板是玩家的东西，删机器不能吞掉：优先塞接收者（没有接收者就找工厂拥有者）的背包，
     * 塞不下或不在线就掉在机器坐标；机器所在区块没加载时退回接收者脚下，再不行落到主世界
     * 出生点，保证不丢。</p>
     *
     * @param factory  工厂
     * @param node     要被删掉的节点
     * @param receiver 优先接收者；{@code null} 表示用工厂拥有者
     * @return 退回去的样板数
     */
    int returnPatterns(Factory factory, Node node, @Nullable ServerPlayer receiver) {
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
