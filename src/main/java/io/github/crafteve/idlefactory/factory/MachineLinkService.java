package io.github.crafteve.idlefactory.factory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.io.MachineRunner;
import io.github.crafteve.idlefactory.factory.links.LinkRole;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.node.Node;
import io.github.crafteve.idlefactory.factory.node.NodeLibrary;
import io.github.crafteve.idlefactory.factory.node.NodeManager;
import io.github.crafteve.idlefactory.factory.observation.Observation;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;
import io.github.crafteve.idlefactory.factory.probe.BlockProbe;
import io.github.crafteve.idlefactory.factory.probe.Health;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

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
    private final MachineRunner machineRunner;
    private final NodeLibraryService nodeLibrary;
    @Nullable
    private final ObservationService observations;
    private final MinecraftServer server;

    /**
     * @param manager       工厂档案柜
     * @param nodeManager   虚拟节点管理器
     * @param machineRunner 执行器
     * @param nodeLibrary   节点库写入口（落盘由它统一触发）
     * @param observations  观测服务；服务端未就绪时为 {@code null}
     * @param server        服务端
     */
    MachineLinkService(FactoryManager manager, NodeManager nodeManager,
            MachineRunner machineRunner, NodeLibraryService nodeLibrary,
            @Nullable ObservationService observations, MinecraftServer server) {
        this.manager = manager;
        this.nodeManager = nodeManager;
        this.machineRunner = machineRunner;
        this.nodeLibrary = nodeLibrary;
        this.observations = observations;
        this.server = server;
    }

    /**
     * 把准星选中的机器接入玩家自己的工厂。
     *
     * <p>只接受「方块实体 + 至少提供 {@code IItemHandler} / {@code IFluidHandler}」的方块。
     * 节点 id 与机器 id 都是随机 UUID，重复接入按方块占用去重。</p>
     *
     * @param player 发起请求的玩家
     * @return 命令结果（{@code 1} 成功，{@code 0} 失败）
     */
    public int connectMachine(ServerPlayer player) {
        Factory factory = owned(player);
        if (factory == null) {
            return 0;
        }
        BlockPos pos = LinkTargetResolver.pickedBlock(player);
        if (pos == null) {
            FactoryMessages.err(player, "没有对准方块（射线 " + (int) LinkTargetResolver.REACH + " 格）");
            return 0;
        }
        ServerLevel level = player.serverLevel();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            FactoryMessages.err(player, "这个方块没有方块实体");
            return 0;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            FactoryMessages.err(player, "服务端未就绪");
            return 0;
        }
        // 指令路径也是绑定入口，必须和绑定杖走同一套占用规则。
        BlockKey block = BlockKey.of(level.dimension(), pos);
        LinkGate gate = LinkGate.of(this.manager);
        LinkGate.ClaimCheck claim = gate.check(block, factory.id(), LinkRole.MACHINE);
        if (claim.blocked()) {
            FactoryMessages.err(player, LinkGateMessages.gateMessage(claim));
            return 0;
        }
        BlockProbe.Presence presence = BlockProbe.presence(server, block);
        if (presence.health() != Health.PRESENT || presence.kinds().isEmpty()) {
            FactoryMessages.err(player, "这个方块没有可搬运的存储（需要物品 / 流体 / 能量能力）");
            return 0;
        }
        if (factory.config().nodes().machineAt(block).isPresent()) {
            FactoryMessages.err(player, "这个方块已经接入过了");
            return 0;
        }
        Node node = Node.of(null, BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()),
                block);
        Machine machine = node.machines().get(0);
        NodeLibraryService.Batch batch = this.nodeLibrary.begin(factory);
        NodeLibrary.Result added = batch.add(node);
        if (added != NodeLibrary.Result.ADDED) {
            FactoryMessages.err(player, added == NodeLibrary.Result.FULL
                    ? "节点库已满（上限 " + FactoryLimitsConfig.maxNodes() + " 个节点）"
                    : "这个方块已经接入过了");
            return 0;
        }
        refreshObservation(block);
        batch.close();
        FactoryMessages.ok(player, "已接入机器：" + machine.shortId() + " @ "
                + level.dimension().location() + " " + pos.toShortString()
                + " [" + presence.describe() + "]");
        return 1;
    }

    /**
     * 删除准星选中的机器；有 IO 规则时先要求确认。
     *
     * @param player 发起请求的玩家
     * @return 命令结果
     */
    public int disconnectMachine(ServerPlayer player) {
        return deleteMachine(player, DeletePolicy.PROMPT);
    }

    /**
     * 确认删除准星选中的机器。
     *
     * @param player 发起请求的玩家
     * @return 命令结果
     */
    public int confirmDisconnectMachine(ServerPlayer player) {
        return deleteMachine(player, DeletePolicy.CONFIRMED);
    }

    /**
     * 按短 id 删除机器；有 IO 规则时先要求确认。
     *
     * @param player  发起请求的玩家
     * @param shortId 短 id（前 8 位）
     * @return 命令结果
     */
    public int disconnectMachine(ServerPlayer player, String shortId) {
        return deleteMachine(player, shortId, DeletePolicy.PROMPT);
    }

    /**
     * 按短 id 确认删除机器。
     *
     * @param player  发起请求的玩家
     * @param shortId 短 id（前 8 位）
     * @return 命令结果
     */
    public int confirmDisconnectMachine(ServerPlayer player, String shortId) {
        return deleteMachine(player, shortId, DeletePolicy.CONFIRMED);
    }

    private int deleteMachine(ServerPlayer player, DeletePolicy policy) {
        Factory factory = owned(player);
        if (factory == null) {
            return 0;
        }
        BlockPos pos = LinkTargetResolver.pickedBlock(player);
        if (pos == null) {
            FactoryMessages.err(player, "没有对准方块（射线 " + (int) LinkTargetResolver.REACH + " 格）");
            return 0;
        }
        ServerLevel level = player.serverLevel();
        Optional<Machine> machine = factory.config().nodes()
                .machineAt(BlockKey.of(level.dimension(), pos));
        if (machine.isEmpty()) {
            FactoryMessages.err(player, "这个位置没有接入的机器");
            return 0;
        }
        return reportDelete(player, factory, machine.get(), policy,
                "/idf factory disconnect confirm");
    }

    private int deleteMachine(ServerPlayer player, String shortId, DeletePolicy policy) {
        Factory factory = owned(player);
        if (factory == null) {
            return 0;
        }
        Optional<Machine> target = factory.config().nodes().machineByShortId(shortId);
        if (target.isEmpty()) {
            FactoryMessages.err(player, "找不到目标 " + shortId + "（或前缀有歧义）");
            return 0;
        }
        return reportDelete(player, factory, target.get(), policy,
                "/idf factory disconnect " + shortId + " confirm");
    }

    /**
     * 删除策略：决定「有 IO 规则」的机器怎么处理。
     *
     * <p>不用布尔参数：三处调用点需要三种语义，一个 {@code confirmed} 布尔只能表达两种。</p>
     */
    enum DeletePolicy {
        /** 命令首次删除：有规则只提示确认，不删。 */
        PROMPT,
        /** 已确认：有规则也删；命令 confirm 与界面删除都走这里。 */
        CONFIRMED,
        /** 连锁断开：有规则就跳过，规则不能批量丢。 */
        SKIP_WITH_RULES
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
     * @param policy   删除策略
     * @param receiver 样板优先接收者；{@code null} 表示找工厂拥有者
     * @param batch    本次操作的节点库批量句柄
     * @return 结果；{@code deleted=false} 表示策略要求跳过
     */
    DeleteOutcome removeMachine(Factory factory, Node node, Machine machine,
            DeletePolicy policy, @Nullable ServerPlayer receiver, NodeLibraryService.Batch batch) {
        int rules = node.ruleCount();
        boolean blocked = switch (policy) {
            case PROMPT, SKIP_WITH_RULES -> rules > 0;
            case CONFIRMED -> false;
        };
        if (blocked) {
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
        this.machineRunner.forget(machine.id());
        return new DeleteOutcome(true, rules, returned, nodeDeleted);
    }

    /**
     * 删除成功后的回执文案；命令与界面共用以保证逐字一致。
     *
     * @param machine 被删掉的机器
     * @param outcome 删除结果
     * @return 如「已删除机器 [ab12cd34]（2 条 IO 规则已删除）（退回 1 块样板）」
     */
    private static String deletedText(Machine machine, DeleteOutcome outcome) {
        String rulesText = outcome.rules() > 0 ? "（" + outcome.rules() + " 条 IO 规则已删除）" : "";
        String patternText = outcome.patterns() > 0 ? "（退回 " + outcome.patterns() + " 块样板）" : "";
        String nodeText = outcome.nodeDeleted() ? "（节点已删除）" : "";
        return "已删除机器 [" + machine.shortId() + "]" + nodeText + rulesText + patternText;
    }

    /**
     * 命令侧删除：取机器、按策略删、出文案。
     *
     * @param player         玩家
     * @param factory        工厂
     * @param target         机器
     * @param policy         删除策略
     * @param confirmCommand 给玩家的确认指令
     * @return 命令结果
     */
    private int reportDelete(ServerPlayer player, Factory factory, Machine target,
            DeletePolicy policy, String confirmCommand) {
        Optional<Node> nodeFound = factory.config().nodes().nodeOfMachine(target.id());
        if (nodeFound.isEmpty()) {
            FactoryMessages.err(player, "机器不属于任何节点");
            return 0;
        }
        Node node = nodeFound.get();
        NodeLibraryService.Batch batch = this.nodeLibrary.begin(factory);
        DeleteOutcome outcome = removeMachine(factory, node, target, policy, null, batch);
        if (!outcome.deleted()) {
            FactoryMessages.warn(player, "机器 [" + target.shortId() + "] 还有 " + outcome.rules()
                    + " 条 IO 配置，确认删除请执行 " + confirmCommand + "，或在机器库界面删除");
            return 0;
        }
        batch.close();
        FactoryMessages.ok(player, deletedText(target, outcome));
        return 1;
    }

    /**
     * 按机器 id 删除；界面确认后走这个入口。
     *
     * @param factory   工厂
     * @param machineId 机器 id
     * @return 动作结果
     */
    public NodeActions.Result deleteMachineById(Factory factory, UUID machineId) {
        Optional<Machine> direct = findMachine(factory, machineId);
        if (direct.isEmpty()) {
            return NodeActions.Result.err("找不到机器");
        }
        Machine target = direct.get();
        Optional<Node> nodeFound = factory.config().nodes().nodeOfMachine(machineId);
        if (nodeFound.isEmpty()) {
            return NodeActions.Result.err("机器不属于任何节点");
        }
        NodeLibraryService.Batch batch = this.nodeLibrary.begin(factory);
        DeleteOutcome outcome = removeMachine(factory, nodeFound.get(), target,
                DeletePolicy.CONFIRMED, null, batch);
        batch.close();
        return NodeActions.Result.ok(deletedText(target, outcome));
    }

    /**
     * 按机器 id 在所有节点里找机器。
     *
     * @param factory   工厂
     * @param machineId 机器 id
     * @return 机器；找不到时为空
     */
    private static Optional<Machine> findMachine(Factory factory, UUID machineId) {
        for (Node node : factory.config().nodes().all()) {
            Optional<Machine> machine = node.machineOf(machineId);
            if (machine.isPresent()) {
                return machine;
            }
        }
        return Optional.empty();
    }

    /**
     * 取玩家自己的工厂；没有就提示。
     *
     * @param player 玩家
     * @return 工厂；玩家没有工厂时为 {@code null}
     */
    @Nullable
    private Factory owned(ServerPlayer player) {
        Factory factory = FactoryRefs.owned(this.manager, player);
        if (factory == null) {
            FactoryMessages.err(player, "你还没有工厂，先执行 /idlefactory create");
        }
        return factory;
    }

    /**
     * 立刻刷新一个方块的观测。
     *
     * @param block 方块
     * @return 刷新后的观测；观测服务未就绪时为 {@code null}
     */
    @Nullable
    private Observation refreshObservation(BlockKey block) {
        return this.observations == null ? null : this.observations.refresh(block);
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
        BlockKey typeBlock = machine.typeBlock();
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
