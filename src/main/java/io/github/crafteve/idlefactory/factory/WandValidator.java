package io.github.crafteve.idlefactory.factory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.config.IdleFactoryConfig;
import io.github.crafteve.idlefactory.claim.BlockOccupancyTable;

import io.github.crafteve.idlefactory.location.BlockKey;

import io.github.crafteve.idlefactory.node.Node;

import io.github.crafteve.idlefactory.factory.wand.WandDeleteReason;

import io.github.crafteve.idlefactory.command.WandDraft;

import io.github.crafteve.idlefactory.factory.wand.WandFailure;

import io.github.crafteve.idlefactory.factory.wand.WandIssue;

import io.github.crafteve.idlefactory.item.MachineBindingWandItem;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 绑定杖草稿的服务端校验：只读，不写库。
 *
 * <p>预校验（打开面板）与提交共用同一套判断，保证面板标的红与服务端最终结论一致；提交在预校验
 * 之后可能隔了一个 tick，所以提交时必须整份重验，不复用任何缓存。顺序固定：主手 → 工厂 →
 * 版本号 → 草稿结构 → 节点归属 → 重复块 → 逐块世界事实与占用 → 类型一致 → 上限 → 删到 0 台
 * 时的规则 / 样板拦截。</p>
 *
 * <p>失败用 {@link WandIssue} 表达：挂在方块上的问题给面板逐行标红，{@code block == null}
 * 的草稿级问题表示面板根本不该打开。</p>
 */
final class WandValidator {
    private final BlockOccupancyTable occupancy;
    private final MinecraftServer server;

    /**
     * @param manager 工厂档案柜，用于本次操作的占用快照
     * @param server  服务端，用于按维度取世界
     */
    WandValidator(BlockOccupancyTable occupancy, MinecraftServer server) {
        this.occupancy = occupancy;
        this.server = server;
    }

    /**
     * 只读预校验；不查提交版本号。
     *
     * @param factory 玩家自己的工厂
     * @param player  发起请求的玩家
     * @param draft   当前草稿
     * @return 问题列表；空表示通过
     */
    List<WandIssue> validate(Factory factory, ServerPlayer player, WandDraft draft) {
        List<WandIssue> issues = new ArrayList<>();
        if (checkContext(factory, player, issues)) {
            checkDraft(factory, draft, issues);
        }
        return issues;
    }

    /**
     * 提交前的全量校验：先查版本号，再走与预校验相同的判断。
     *
     * @param factory        玩家自己的工厂
     * @param player         发起请求的玩家
     * @param draft          当前草稿
     * @param lastAcceptedSeq 服务端已经接受过的最大提交序号
     * @return 问题列表；空表示通过
     */
    List<WandIssue> validateForCommit(Factory factory, ServerPlayer player, WandDraft draft,
            long lastAcceptedSeq) {
        List<WandIssue> issues = new ArrayList<>();
        if (!checkContext(factory, player, issues)) {
            return issues;
        }
        if (draft.requestSeq() <= lastAcceptedSeq) {
            issues.add(WandIssue.global(WandFailure.STALE_REQUEST));
            return issues;
        }
        checkDraft(factory, draft, issues);
        return issues;
    }

    /**
     * 主手与工厂归属；不通过时往列表里写一条草稿级问题。
     *
     * @param factory 玩家自己的工厂
     * @param player  玩家
     * @param issues  问题列表
     * @return 通过时为 {@code true}
     */
    private static boolean checkContext(Factory factory, ServerPlayer player,
            List<WandIssue> issues) {
        if (!(player.getMainHandItem().getItem() instanceof MachineBindingWandItem)) {
            issues.add(WandIssue.global(WandFailure.NOT_HOLDING_WAND));
            return false;
        }
        if (!factory.config().owner().equals(player.getUUID())) {
            issues.add(WandIssue.global(WandFailure.NO_FACTORY));
            return false;
        }
        return true;
    }

    /**
     * 草稿结构与世界事实校验。
     *
     * @param factory 玩家自己的工厂
     * @param draft   当前草稿
     * @param issues  问题列表
     */
    private void checkDraft(Factory factory, WandDraft draft, List<WandIssue> issues) {
        if (draft.isEmpty()) {
            issues.add(WandIssue.global(WandFailure.EMPTY_DRAFT));
            return;
        }
        if (draft.nodeId() == null && !draft.removed().isEmpty()) {
            issues.add(WandIssue.global(WandFailure.INVALID_REQUEST));
            return;
        }
        Node base = null;
        if (draft.nodeId() != null) {
            Optional<Node> found = factory.config().nodes().byId(draft.nodeId());
            if (found.isEmpty()) {
                issues.add(WandIssue.global(WandFailure.NODE_NOT_FOUND));
                return;
            }
            base = found.get();
            for (UUID machineId : draft.removed()) {
                if (base.machineOf(machineId).isEmpty()) {
                    issues.add(WandIssue.global(WandFailure.INVALID_REQUEST));
                    return;
                }
            }
        }
        Set<BlockKey> addedBlocks = new HashSet<>(draft.added().size());
        for (BlockKey block : draft.added()) {
            if (!addedBlocks.add(block)) {
                issues.add(WandIssue.at(block, WandFailure.DUPLICATE_BLOCK));
            }
        }
        // 加与删不能指向同一个方块：客户端 toggle 天然保证，服务端仍然查一遍
        if (base != null) {
            for (UUID machineId : draft.removed()) {
                BlockKey block = base.machineOf(machineId).orElseThrow().block();
                if (addedBlocks.contains(block)) {
                    issues.add(WandIssue.at(block, WandFailure.INVALID_REQUEST));
                }
            }
        }
        if (!issues.isEmpty()) {
            return;
        }
        BlockOccupancyTable gate = this.occupancy;
        ResourceLocation typeId = base == null ? null : base.typeBlockId();
        for (BlockKey block : draft.added()) {
            ResourceLocation blockId = checkBlock(factory, gate, block, issues);
            if (blockId == null) {
                continue;
            }
            if (typeId == null) {
                typeId = blockId;
            } else if (!typeId.equals(blockId)) {
                issues.add(WandIssue.at(block, WandFailure.TYPE_MISMATCH));
            }
        }
        if (!issues.isEmpty()) {
            return;
        }
        int baseMachines = base == null ? 0 : base.machineCount();
        int finalMachines = baseMachines - draft.removed().size() + draft.added().size();
        if (base == null && factory.config().nodes().size() + 1 > IdleFactoryConfig.maxNodes()) {
            issues.add(WandIssue.global(WandFailure.LIMIT_NODES));
        }
        if (finalMachines > IdleFactoryConfig.maxMachinesPerNode()) {
            issues.add(WandIssue.global(WandFailure.LIMIT_MACHINES));
        }
        if (factory.config().nodes().totalMachineCount() - baseMachines + finalMachines
                > IdleFactoryConfig.maxTotalMachines()) {
            issues.add(WandIssue.global(WandFailure.LIMIT_MACHINES));
        }
        if (base != null && finalMachines == 0) {
            WandDeleteReason reason = deleteReason(base);
            if (reason != WandDeleteReason.NONE) {
                for (UUID machineId : draft.removed()) {
                    base.machineOf(machineId)
                            .ifPresent(machine -> issues.add(
                                    WandIssue.blocked(machine.block(), reason)));
                }
            }
        }
    }

    /**
     * 校验一个待加入方块，失败时把问题追加进列表。
     *
     * @param factory 工厂
     * @param gate    本次操作的占用快照
     * @param block   方块
     * @param issues  问题列表
     * @return 方块 id；失败时为 {@code null}，问题已经写进列表
     */
    @Nullable
    private ResourceLocation checkBlock(Factory factory, BlockOccupancyTable gate, BlockKey block,
            List<WandIssue> issues) {
        ServerLevel level = this.server.getLevel(block.dimension());
        if (level == null || !level.isLoaded(block.pos())) {
            issues.add(WandIssue.at(block, WandFailure.BLOCK_UNLOADED));
            return null;
        }
        if (level.getBlockEntity(block.pos()) == null) {
            issues.add(WandIssue.at(block, WandFailure.NOT_A_MACHINE));
            return null;
        }
        if (!FactoryLinkFinder.isMachine(level, block.pos())) {
            issues.add(WandIssue.at(block, WandFailure.NOT_A_MACHINE));
            return null;
        }
        if (factory.config().nodes().machineAt(block).isPresent()) {
            issues.add(WandIssue.at(block, WandFailure.BLOCK_CLAIMED));
            return null;
        }
        if (gate.check(block, factory.id(), BlockOccupancyTable.Role.MACHINE).kind()
                != BlockOccupancyTable.ClaimCheck.Kind.FREE) {
            issues.add(WandIssue.at(block, WandFailure.BLOCK_CLAIMED));
            return null;
        }
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(block.pos()).getBlock());
    }

    /**
     * 删到 0 台时节点上还留着什么。
     *
     * @param node 要删掉的节点
     * @return 拦截原因；可以删时为 {@link WandDeleteReason#NONE}
     */
    private static WandDeleteReason deleteReason(Node node) {
        if (node.ruleCount() > 0) {
            return WandDeleteReason.HAS_RULES;
        }
        if (node.patternCount() > 0) {
            return WandDeleteReason.HAS_PATTERNS;
        }
        return WandDeleteReason.NONE;
    }
}
