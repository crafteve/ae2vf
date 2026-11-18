package io.github.crafteve.idlefactory.client.wand;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.location.BlockKey;
import io.github.crafteve.idlefactory.command.WandDraft;
import io.github.crafteve.idlefactory.factory.wand.WandFailure;
import io.github.crafteve.idlefactory.factory.wand.WandIssue;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端草稿预检：只做客户端做得到的事。
 *
 * <p>检查范围是「重复块、类型一致、快照三态、方块实体与区块加载」，不探测能力、不判上限——
 * 那些只有服务端知道。结果只用于即时红框与 HUD，面板以服务端预校验为准。</p>
 */
public final class WandPrecheck {
    private WandPrecheck() {
        // 纯静态检查
    }

    /**
     * 全量检查一份草稿。
     *
     * @param minecraft 客户端实例
     * @param draft     草稿
     * @return 问题列表；空表示客户端看不出问题
     */
    public static List<WandIssue> check(Minecraft minecraft, WandDraft draft) {
        List<WandIssue> issues = new ArrayList<>();
        if (draft.nodeId() != null && WandLinks.node(draft.nodeId()).isEmpty()) {
            issues.add(WandIssue.global(WandFailure.NODE_NOT_FOUND));
            return issues;
        }
        ResourceLocation typeId = baseType(minecraft, draft);
        for (BlockKey block : draft.added()) {
            WandIssue issue = checkBlock(minecraft, block, typeId, true);
            if (issue != null) {
                issues.add(issue);
                continue;
            }
            ResourceLocation blockId = blockId(minecraft, block);
            if (blockId != null && typeId == null) {
                typeId = blockId;
            }
        }
        return issues;
    }

    /**
     * 检查一个待加入方块。
     *
     * @param minecraft 客户端实例
     * @param block     方块
     * @param typeId    草稿类型基准；新建草稿第一次加入时为空
     * @param checkType 是否顺便比对类型
     * @return 问题；通过时为 {@code null}
     */
    @Nullable
    public static WandIssue checkBlock(Minecraft minecraft, BlockKey block,
            @Nullable ResourceLocation typeId, boolean checkType) {
        if (minecraft.level == null) {
            return WandIssue.at(block, WandFailure.BLOCK_UNLOADED);
        }
        if (!minecraft.level.dimension().equals(block.dimension())
                || !minecraft.level.isLoaded(block.pos())) {
            return WandIssue.at(block, WandFailure.BLOCK_UNLOADED);
        }
        if (minecraft.level.getBlockEntity(block.pos()) == null) {
            return WandIssue.at(block, WandFailure.NOT_A_MACHINE);
        }
        if (WandLinks.owner(block).isPresent()) {
            return WandIssue.at(block, WandFailure.BLOCK_CLAIMED);
        }
        if (checkType && typeId != null) {
            ResourceLocation blockId = blockId(minecraft, block);
            if (blockId != null && !typeId.equals(blockId)) {
                return WandIssue.at(block, WandFailure.TYPE_MISMATCH);
            }
        }
        return null;
    }

    /**
     * 草稿的类型基准：编辑已有节点取快照里的节点类型，新建草稿取第一台已加入机器。
     *
     * @param minecraft 客户端实例
     * @param draft     草稿
     * @return 类型方块 id；还判断不出来时为 {@code null}
     */
    @Nullable
    public static ResourceLocation baseType(Minecraft minecraft, WandDraft draft) {
        if (draft.nodeId() != null) {
            return WandLinks.node(draft.nodeId())
                    .map(io.github.crafteve.idlefactory.network.payload.FactoryLinksS2C.NodeLink::typeBlockId)
                    .orElse(null);
        }
        for (BlockKey block : draft.added()) {
            ResourceLocation blockId = blockId(minecraft, block);
            if (blockId != null) {
                return blockId;
            }
        }
        return null;
    }

    /**
     * 方块注册名。
     *
     * @param minecraft 客户端实例
     * @param block     方块
     * @return 注册名；世界或区块不可读时为 {@code null}
     */
    @Nullable
    public static ResourceLocation blockId(Minecraft minecraft, BlockKey block) {
        if (minecraft.level == null
                || !minecraft.level.dimension().equals(block.dimension())
                || !minecraft.level.isLoaded(block.pos())) {
            return null;
        }
        return BuiltInRegistries.BLOCK.getKey(minecraft.level.getBlockState(block.pos()).getBlock());
    }
}
