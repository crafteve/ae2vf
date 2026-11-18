package io.github.crafteve.idlefactory.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.claim.BlockOccupancyTable;
import io.github.crafteve.idlefactory.config.IdleFactoryConfig;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.wand.WandDraft;
import io.github.crafteve.idlefactory.item.MachineBindingWandItem;
import io.github.crafteve.idlefactory.location.BlockKey;
import io.github.crafteve.idlefactory.node.Node;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 绑定杖草稿的服务端校验：只读，不写库。
 *
 * <p>预校验（打开面板）与提交共用同一套判断，保证面板标的红与服务端最终结论一致；提交在
 * 预校验之后可能隔了一个 tick，所以提交时必须整份重验。顺序固定：主手 → 工厂 → 版本号 →
 * 草稿结构 → 节点归属 → 重复块 → 逐块世界事实与占用 → 类型一致 → 上限 → 删到 0 台时的
 * 规则 / 样板拦截。</p>
 */
public final class CommandWandGate {
    private final BlockOccupancyTable occupancy;
    private final MinecraftServer server;

    /**
     * @param occupancy 占用表
     * @param server    服务端，用于按维度取世界
     */
    public CommandWandGate(BlockOccupancyTable occupancy, MinecraftServer server) {
        this.occupancy = occupancy;
        this.server = server;
    }

    /**
     * 只读预校验；不查提交版本号。
     *
     * @param factory 玩家自己的工厂
     * @param player  发起请求的玩家
     * @param draft   当前草稿
     * @return 校验结果
     */
    public WandGateResult validate(Factory factory, ServerPlayer player, WandDraft draft) {
        CommandReason context = checkContext(factory, player);
        if (context != CommandReason.OK) {
            return new WandGateResult(context, List.of());
        }
        return checkDraft(factory, draft);
    }

    /**
     * 提交前的全量校验：先查版本号，再走与预校验相同的判断。
     *
     * @param factory        玩家自己的工厂
     * @param player         发起请求的玩家
     * @param draft          当前草稿
     * @param lastAcceptedSeq 服务端已经接受过的最大提交序号
     * @return 校验结果
     */
    public WandGateResult validateForCommit(Factory factory, ServerPlayer player, WandDraft draft,
            long lastAcceptedSeq) {
        CommandReason context = checkContext(factory, player);
        if (context != CommandReason.OK) {
            return new WandGateResult(context, List.of());
        }
        if (draft.requestSeq() <= lastAcceptedSeq) {
            return new WandGateResult(CommandReason.STALE_REQUEST, List.of());
        }
        return checkDraft(factory, draft);
    }

    /**
     * 主手与工厂归属。
     *
     * @param factory 玩家自己的工厂
     * @param player  玩家
     * @return 通过时为 {@link CommandReason#OK}
     */
    private static CommandReason checkContext(Factory factory, ServerPlayer player) {
        if (!(player.getMainHandItem().getItem() instanceof MachineBindingWandItem)) {
            return CommandReason.NOT_HOLDING_WAND;
        }
        if (!factory.config().owner().equals(player.getUUID())) {
            return CommandReason.FACTORY_MISSING;
        }
        return CommandReason.OK;
    }

    /**
     * 草稿结构与世界事实校验。
     *
     * @param factory 工厂
     * @param draft   草稿
     * @return 校验结果
     */
    private WandGateResult checkDraft(Factory factory, WandDraft draft) {
        if (draft.isEmpty()) {
            return new WandGateResult(CommandReason.EMPTY_DRAFT, List.of());
        }
        if (draft.nodeId() == null && !draft.removed().isEmpty()) {
            return new WandGateResult(CommandReason.INVALID_DRAFT, List.of());
        }
        Node base = null;
        if (draft.nodeId() != null) {
            Optional<Node> found = factory.config().nodes().byId(draft.nodeId());
            if (found.isEmpty()) {
                return new WandGateResult(CommandReason.NODE_MISSING, List.of());
            }
            base = found.get();
            for (UUID machineId : draft.removed()) {
                if (base.machineOf(machineId).isEmpty()) {
                    return new WandGateResult(CommandReason.INVALID_DRAFT, List.of());
                }
            }
        }
        Set<BlockKey> addedBlocks = new HashSet<>(draft.added().size());
        for (BlockKey block : draft.added()) {
            if (!addedBlocks.add(block)) {
                return new WandGateResult(CommandReason.DUPLICATE_BLOCK, List.of(block));
            }
        }
        // 加与删不能指向同一个方块：客户端 toggle 天然保证，服务端仍然查一遍
        List<BlockKey> conflicts = new ArrayList<>();
        if (base != null) {
            for (UUID machineId : draft.removed()) {
                BlockKey block = base.machineOf(machineId).orElseThrow().block();
                if (addedBlocks.contains(block)) {
                    conflicts.add(block);
                }
            }
            if (!conflicts.isEmpty()) {
                return new WandGateResult(CommandReason.INVALID_DRAFT, conflicts);
            }
        }
        CommandReason blockFailure = CommandReason.OK;
        ResourceLocation typeId = base == null ? null : base.typeBlockId();
        for (BlockKey block : draft.added()) {
            BlockCheck check = checkBlock(factory, block);
            if (check.blockId() == null) {
                conflicts.add(block);
                if (blockFailure == CommandReason.OK) {
                    blockFailure = check.reason();
                }
                continue;
            }
            if (typeId == null) {
                typeId = check.blockId();
            } else if (!typeId.equals(check.blockId())) {
                conflicts.add(block);
                if (blockFailure == CommandReason.OK) {
                    blockFailure = CommandReason.TYPE_MISMATCH;
                }
            }
        }
        if (!conflicts.isEmpty()) {
            return new WandGateResult(blockFailure, conflicts);
        }
        int baseMachines = base == null ? 0 : base.machineCount();
        int finalMachines = baseMachines - draft.removed().size() + draft.added().size();
        if (base == null && factory.config().nodes().size() + 1 > IdleFactoryConfig.maxNodes()) {
            return new WandGateResult(CommandReason.LIMIT_NODES, List.of());
        }
        if (finalMachines > IdleFactoryConfig.maxMachinesPerNode()) {
            return new WandGateResult(CommandReason.LIMIT_MACHINES, List.of());
        }
        if (factory.config().nodes().totalMachineCount() - baseMachines + finalMachines
                > IdleFactoryConfig.maxTotalMachines()) {
            return new WandGateResult(CommandReason.LIMIT_MACHINES, List.of());
        }
        if (base != null && finalMachines == 0) {
            CommandReason blocker = deleteReason(base);
            if (blocker != CommandReason.OK) {
                for (UUID machineId : draft.removed()) {
                    base.machineOf(machineId).ifPresent(machine -> conflicts.add(machine.block()));
                }
                return new WandGateResult(blocker, conflicts);
            }
        }
        return new WandGateResult(CommandReason.OK, List.of());
    }

    /**
     * 一个待加入方块的校验结果。
     *
     * @param blockId 方块 id；失败时为 {@code null}
     * @param reason  失败原因；成功时为 {@link CommandReason#OK}
     */
    private record BlockCheck(@Nullable ResourceLocation blockId, CommandReason reason) {
        static BlockCheck ok(ResourceLocation blockId) {
            return new BlockCheck(blockId, CommandReason.OK);
        }

        static BlockCheck failed(CommandReason reason) {
            return new BlockCheck(null, reason);
        }
    }

    /**
     * 校验一个待加入方块。
     *
     * @param factory 工厂
     * @param block   方块
     * @return 校验结果
     */
    private BlockCheck checkBlock(Factory factory, BlockKey block) {
        ServerLevel level = this.server.getLevel(block.dimension());
        if (level == null || !level.isLoaded(block.pos())) {
            return BlockCheck.failed(CommandReason.BLOCK_UNLOADED);
        }
        if (level.getBlockEntity(block.pos()) == null) {
            return BlockCheck.failed(CommandReason.NOT_A_MACHINE);
        }
        if (!io.github.crafteve.idlefactory.factory.FactoryLinkFinder.isMachine(level, block.pos())) {
            return BlockCheck.failed(CommandReason.NOT_A_MACHINE);
        }
        if (factory.config().nodes().machineAt(block).isPresent()) {
            return BlockCheck.failed(CommandReason.BLOCK_CLAIMED);
        }
        if (this.occupancy.check(block, factory.id(), BlockOccupancyTable.Role.MACHINE).kind()
                != BlockOccupancyTable.ClaimCheck.Kind.FREE) {
            return BlockCheck.failed(CommandReason.BLOCK_CLAIMED);
        }
        return BlockCheck.ok(
                BuiltInRegistries.BLOCK.getKey(level.getBlockState(block.pos()).getBlock()));
    }

    /**
     * 删到 0 台时节点上还留着什么。
     *
     * @param node 要删掉的节点
     * @return 拦截原因；可以删时为 {@link CommandReason#OK}
     */
    private static CommandReason deleteReason(Node node) {
        if (node.ruleCount() > 0) {
            return CommandReason.DELETE_BLOCKED_RULES;
        }
        if (node.patternCount() > 0) {
            return CommandReason.DELETE_BLOCKED_PATTERNS;
        }
        return CommandReason.OK;
    }
}
