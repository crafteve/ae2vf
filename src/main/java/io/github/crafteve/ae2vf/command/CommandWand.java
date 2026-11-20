package io.github.crafteve.ae2vf.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.ae2vf.VoidFactory;
import io.github.crafteve.ae2vf.claim.BlockOccupancyTable;
import io.github.crafteve.ae2vf.config.VoidFactoryConfig;
import io.github.crafteve.ae2vf.factory.Factory;
import io.github.crafteve.ae2vf.command.CommandChain;
import io.github.crafteve.ae2vf.factory.FactoryResolver;
import io.github.crafteve.ae2vf.factory.FactoryLifecycle;
import io.github.crafteve.ae2vf.command.WandDraft;
import io.github.crafteve.ae2vf.item.MachineBindingWandItem;
import io.github.crafteve.ae2vf.key.BlockKey;
import io.github.crafteve.ae2vf.machine.Machine;
import io.github.crafteve.ae2vf.machine.MatchMode;
import io.github.crafteve.ae2vf.machine.IoRuleSpec;
import io.github.crafteve.ae2vf.node.Node;
import io.github.crafteve.ae2vf.node.NodeIo;
import io.github.crafteve.ae2vf.node.NodeLibrary;
import io.github.crafteve.ae2vf.io.IoSide;
import io.github.crafteve.ae2vf.key.AeKeyAdapter;

import appeng.api.stacks.AEKey;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 绑定杖的服务端写入口：1:1 toggle、连锁查询、草稿预校验与提交。
 *
 * <p>校验一律先走 {@link CommandWandGate}；写库一律走 {@link CommandTopology}，所以
 * 「先全量校验、再一次写库」只有一套。提交成功后才推进玩家已接受的序号。</p>
 *
 * <p>本类不持有跨命令状态；所有方法都跑在服务端主线程。</p>
 */
public final class CommandWand {
    private final FactoryLifecycle runtime;
    private final CommandWandGate gate;

    /**
     * @param runtime 服务端运行时
     */
    public CommandWand(FactoryLifecycle runtime) {
        this.runtime = runtime;
        this.gate = new CommandWandGate(runtime.occupancy(), runtime.server());
    }

    /**
     * 空闲态右键方块：已绑定就断开，未绑定就建一个 1 台机器的节点。
     *
     * @param player 发起请求的玩家
     * @param block  目标方块
     * @return 回执
     */
    public CommandResult toggle(ServerPlayer player, BlockKey block) {
        Factory factory = owned(player);
        if (factory == null) {
            return CommandResult.of(CommandReason.FACTORY_MISSING);
        }
        ServerLevel level = this.runtime.server().getLevel(block.dimension());
        if (level == null || !level.isLoaded(block.pos())) {
            return CommandResult.of(CommandReason.BLOCK_UNLOADED);
        }
        Optional<Machine> bound = factory.nodes().machineAt(block);
        CommandResult result = bound.isPresent()
                ? disconnect(factory, bound.get(), block, player)
                : connect(factory, level, block, player);
        return result.ok()
                ? new CommandResult(result.reason(), result.nodeId(), block, result.blocks())
                : result;
    }

    /**
     * 连锁查找：只返回过滤后的机器方块，不写库。
     *
     * @param player 发起请求的玩家
     * @param origin 连锁起点
     * @return 机器列表与原因
     */
    public WandChainResult chain(ServerPlayer player, BlockKey origin) {
        if (!(player.getMainHandItem().getItem() instanceof MachineBindingWandItem)) {
            return new WandChainResult(CommandReason.NOT_HOLDING_WAND, List.of());
        }
        Factory factory = owned(player);
        if (factory == null) {
            return new WandChainResult(CommandReason.FACTORY_MISSING, List.of());
        }
        ServerLevel level = this.runtime.server().getLevel(origin.dimension());
        if (level == null || !level.isLoaded(origin.pos())) {
            return new WandChainResult(CommandReason.BLOCK_UNLOADED, List.of());
        }
        if (!CommandChain.isMachine(level, origin.pos())) {
            return new WandChainResult(CommandReason.NOT_A_MACHINE, List.of());
        }
        List<BlockPos> found = CommandChain.machines(level, origin.pos());
        if (found.isEmpty()) {
            return new WandChainResult(CommandReason.OK, List.of());
        }
        // BFS 的半径与数量上限按筛选前计算；这里只按占用表分流，过滤后不补位、不重扫。
        BlockOccupancyTable.Filter filter = this.runtime.occupancy().filter(factory.id(),
                BlockOccupancyTable.Role.MACHINE, level.dimension(), found);
        List<BlockKey> blocks = new ArrayList<>(filter.open().size());
        for (BlockPos pos : filter.open()) {
            blocks.add(BlockKey.of(level.dimension(), pos));
        }
        return new WandChainResult(CommandReason.OK, blocks);
    }

    /**
     * 打开面板前的只读预校验。
     *
     * @param player 发起请求的玩家
     * @param draft  当前草稿
     * @return 校验结果
     */
    public WandGateResult open(ServerPlayer player, WandDraft draft) {
        Factory factory = owned(player);
        if (factory == null) {
            return new WandGateResult(CommandReason.FACTORY_MISSING, List.of());
        }
        return this.gate.validate(factory, player, draft);
    }

    /**
     * 提交整份草稿：全量重验后原子写库。
     *
     * @param player 发起请求的玩家
     * @param draft  完整草稿
     * @return 回执
     */
    public CommandResult commit(ServerPlayer player, WandDraft draft) {
        Factory factory = owned(player);
        if (factory == null) {
            return CommandResult.of(CommandReason.FACTORY_MISSING);
        }
        long lastAccepted = this.runtime.lastAcceptedSeq(player.getUUID());
        WandGateResult check = this.gate.validateForCommit(factory, player, draft, lastAccepted);
        if (check.reason() != CommandReason.OK || !check.conflicts().isEmpty()) {
            return CommandResult.block(check.reason() == CommandReason.OK
                    ? CommandReason.INVALID_DRAFT : check.reason(),
                    check.conflicts().isEmpty() ? null : check.conflicts().getFirst(),
                    check.conflicts());
        }
        Node base = draft.nodeId() == null ? null
                : factory.nodes().byId(draft.nodeId()).orElse(null);
        int baseMachines = base == null ? 0 : base.machineCount();
        int finalMachines = baseMachines - draft.removed().size() + draft.added().size();
        Node candidate = finalMachines == 0 ? null : buildCandidate(base, draft);
        if (finalMachines > 0 && candidate == null) {
            return CommandResult.of(CommandReason.INVALID_DRAFT);
        }
        CommandTopology topology = new CommandTopology(this.runtime, this.runtime.nodeManager(),
                this.runtime.nodeIoRunner(), this.runtime.server());
        CommandTopology.TopologyOutcome outcome = topology.apply(factory, base, candidate,
                draft.removed(), player);
        if (!outcome.applied()) {
            VoidFactory.LOGGER.warn("AE2 Void Factory: 绑定杖提交写库结果与预期不符：result={}, node={},"
                    + " added={}, removed={}", outcome.result(), draft.nodeId(),
                    draft.added().size(), draft.removed().size());
            return CommandResult.of(CommandReason.INTERNAL);
        }
        for (BlockKey block : draft.added()) {
            if (this.runtime.observations() != null) {
                this.runtime.observations().refresh(block);
            }
        }
        UUID nodeId = candidate != null ? candidate.id() : base.id();
        autoAddEnergyRule(factory, nodeId, draft.added());
        this.runtime.acceptCommitSeq(player.getUUID(), draft.requestSeq());
        return CommandResult.node(CommandReason.OK, nodeId);
    }

    /**
     * 新建节点：先过占用门禁，再走统一拓扑出口。
     *
     * @param factory 工厂
     * @param level   方块所在世界
     * @param block   方块
     * @param player  发起请求的玩家
     * @return 回执
     */
    private CommandResult connect(Factory factory, ServerLevel level, BlockKey block,
            ServerPlayer player) {
        if (level.getBlockEntity(block.pos()) == null
                || !CommandChain.isMachine(level, block.pos())) {
            return CommandResult.of(CommandReason.NOT_A_MACHINE);
        }
        if (this.runtime.occupancy().check(block, factory.id(),
                BlockOccupancyTable.Role.MACHINE).kind()
                != BlockOccupancyTable.ClaimCheck.Kind.FREE) {
            return CommandResult.of(CommandReason.BLOCK_CLAIMED);
        }
        ResourceLocation typeId = BuiltInRegistries.BLOCK
                .getKey(level.getBlockState(block.pos()).getBlock());
        Machine machine = Machine.of(block.dimension(), block.pos());
        Node candidate = new Node(UUID.randomUUID(), null, typeId, new NodeIo(),
                new io.github.crafteve.ae2vf.node.NodeCraft(), List.of(machine), 0L);
        CommandTopology topology = new CommandTopology(this.runtime, this.runtime.nodeManager(),
                this.runtime.nodeIoRunner(), this.runtime.server());
        CommandTopology.TopologyOutcome outcome = topology.apply(factory, null, candidate,
                Set.of(), player);
        if (!outcome.applied()) {
            return CommandResult.of(mapAddFailure(outcome.result()));
        }
        if (this.runtime.observations() != null) {
            this.runtime.observations().refresh(block);
        }
        autoAddEnergyRule(factory, candidate.id(), List.of(block));
        return CommandResult.node(CommandReason.OK, candidate.id());
    }

    /**
     * 1:1 断开：只对 1 台机器的节点生效，有规则或样板都拒绝。
     *
     * @param factory 工厂
     * @param machine 已绑定的机器
     * @param block   机器方块
     * @param player  发起请求的玩家
     * @return 回执
     */
    private CommandResult disconnect(Factory factory, Machine machine, BlockKey block,
            ServerPlayer player) {
        Optional<Node> found = factory.nodes().nodeOfMachine(machine.id());
        if (found.isEmpty()) {
            return CommandResult.of(CommandReason.NODE_MISSING);
        }
        Node node = found.get();
        if (node.machineCount() > 1) {
            return CommandResult.of(CommandReason.MEMBER_NOT_REMOVABLE);
        }
        // 自动补的 FE 输入规则（无面、输入侧）不算玩家配置：否则接一台 FE 机器就再也拆不掉。
        long blockingRules = node.io().inputs().stream()
                .filter(spec -> !isAutoEnergy(spec)).count()
                + node.io().outputs().size();
        if (blockingRules > 0L) {
            return CommandResult.block(CommandReason.DELETE_BLOCKED_RULES, block, List.of(block));
        }
        if (node.patternCount() > 0) {
            return CommandResult.block(CommandReason.DELETE_BLOCKED_PATTERNS, block, List.of(block));
        }
        CommandTopology topology = new CommandTopology(this.runtime, this.runtime.nodeManager(),
                this.runtime.nodeIoRunner(), this.runtime.server());
        CommandTopology.TopologyOutcome outcome = topology.apply(factory, node, null,
                Set.of(machine.id()), player);
        if (!outcome.applied()) {
            return CommandResult.of(CommandReason.INTERNAL);
        }
        return CommandResult.node(CommandReason.OK, node.id());
    }

    /**
     * 新接入的机器带 FE 能力时，给节点补一条输入 FE 规则。
     *
     * @param factory 工厂
     * @param nodeId  落库后的节点 id
     * @param added   本次新接入的方块
     */
    private void autoAddEnergyRule(Factory factory, UUID nodeId, Collection<BlockKey> added) {
        AEKey energy = AeKeyAdapter.energy();
        if (energy == null || this.runtime.observations() == null || added.isEmpty()) {
            return;
        }
        Optional<Node> found = factory.nodes().byId(nodeId);
        if (found.isEmpty()) {
            return;
        }
        for (IoRuleSpec spec : found.get().io().inputs()) {
            if (spec.key().equals(energy)) {
                return;
            }
        }
        for (IoRuleSpec spec : found.get().io().outputs()) {
            if (spec.key().equals(energy)) {
                return;
            }
        }
        boolean hasEnergy = false;
        for (BlockKey block : added) {
            // 直接探能力：绑定时观测往往还没扫到这一格，只读观测会漏补 FE 规则。
            ServerLevel level = this.runtime.server().getLevel(block.dimension());
            if (level != null && io.github.crafteve.ae2vf.io.EnergyPick
                    .capable(level, block.pos())) {
                hasEnergy = true;
                break;
            }
        }
        if (!hasEnergy) {
            return;
        }
        CommandResult result = CommandApply.addRule(factory, nodeId,
                new FactoryCommand.Rule(IoSide.INPUT, energy, null, MatchMode.EXACT, null));
        if (!result.ok()) {
            VoidFactory.LOGGER.debug("AE2 Void Factory: 自动补 FE 规则未写入：{}", result.reason());
        }
    }

    /**
     * 是不是「接入时自动补的 FE 输入规则」：FE key、不限面。
     *
     * @param spec 规则
     * @return 是自动形状的 FE 输入规则
     */
    private static boolean isAutoEnergy(IoRuleSpec spec) {
        return AeKeyAdapter.isEnergy(spec.key()) && spec.face() == null;
    }

    /**
     * 按草稿构造候选节点：新建用随机 id，改节点先删后加。
     *
     * @param base  原节点；新建时为 {@code null}
     * @param draft 草稿
     * @return 候选节点；方块维度读不到时为 {@code null}
     */
    @Nullable
    private Node buildCandidate(@Nullable Node base, WandDraft draft) {
        ResourceLocation typeId = base == null ? null : base.typeBlockId();
        List<Machine> machines = new ArrayList<>(draft.added().size());
        for (BlockKey block : draft.added()) {
            if (typeId == null) {
                ServerLevel level = this.runtime.server().getLevel(block.dimension());
                if (level == null) {
                    return null;
                }
                typeId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(block.pos()).getBlock());
            }
            machines.add(Machine.of(block.dimension(), block.pos()));
        }
        if (base == null) {
            return new Node(UUID.randomUUID(), null, typeId, new NodeIo(),
                    new io.github.crafteve.ae2vf.node.NodeCraft(), machines, 0L);
        }
        Node candidate = base;
        for (UUID machineId : draft.removed()) {
            candidate = candidate.withRemovedMachine(machineId);
        }
        for (Machine machine : machines) {
            candidate = candidate.withAddedMachine(machine);
        }
        return candidate;
    }

    /**
     * 把节点库结果映射成失败原因。
     *
     * @param result 节点库结果
     * @return 失败原因
     */
    private static CommandReason mapAddFailure(NodeLibrary.Result result) {
        return switch (result) {
            case FULL -> CommandReason.LIMIT_NODES;
            case DUPLICATE, CONFLICT -> CommandReason.BLOCK_CLAIMED;
            case ADDED, REPLACED, REMOVED, NOT_FOUND -> CommandReason.INVALID_DRAFT;
        };
    }

    @Nullable
    private Factory owned(ServerPlayer player) {
        return FactoryResolver.owned(this.runtime.manager(), player);
    }
}
