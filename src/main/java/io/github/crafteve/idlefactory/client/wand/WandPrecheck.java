package io.github.crafteve.idlefactory.client.wand;

import io.github.crafteve.idlefactory.client.wand.WandLinkCache;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.command.CommandReason;
import io.github.crafteve.idlefactory.command.WandDraft;
import io.github.crafteve.idlefactory.location.BlockKey;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端草稿预检：打开面板前的本地兜底判断，只回答「哪些方块有问题」。
 *
 * <p>服务端的预校验才是权威；这里的结论只用于面板打开瞬间与服务端回执到达之前的红行显示。
 * 判断维度比服务端少：不查占用表、不查上限，只查本地能看见的方块事实。</p>
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
     * @return 冲突方块；空表示客户端看不出问题
     */
    public static List<BlockKey> check(Minecraft minecraft, WandDraft draft) {
        List<BlockKey> conflicts = new ArrayList<>();
        ResourceLocation typeId = baseType(minecraft, draft);
        for (BlockKey block : draft.added()) {
            CommandReason reason = checkBlock(minecraft, block, typeId, true);
            if (reason != CommandReason.OK) {
                conflicts.add(block);
                continue;
            }
            ResourceLocation blockId = blockId(minecraft, block);
            if (blockId != null && typeId == null) {
                typeId = blockId;
            }
        }
        return conflicts;
    }

    /**
     * 检查一个待加入方块。
     *
     * @param minecraft 客户端实例
     * @param block     方块
     * @param typeId    草稿类型基准；新建草稿第一次加入时为空
     * @param checkType 是否顺便比对类型
     * @return 通过时为 {@link CommandReason#OK}
     */
    public static CommandReason checkBlock(Minecraft minecraft, BlockKey block,
            @Nullable ResourceLocation typeId, boolean checkType) {
        if (minecraft.level == null) {
            return CommandReason.BLOCK_UNLOADED;
        }
        if (!minecraft.level.dimension().equals(block.dimension())
                || !minecraft.level.isLoaded(block.pos())) {
            return CommandReason.BLOCK_UNLOADED;
        }
        if (minecraft.level.getBlockEntity(block.pos()) == null) {
            return CommandReason.NOT_A_MACHINE;
        }
        if (WandLinkCache.owner(block).isPresent()) {
            return CommandReason.BLOCK_CLAIMED;
        }
        if (checkType && typeId != null) {
            ResourceLocation blockId = blockId(minecraft, block);
            if (blockId != null && !typeId.equals(blockId)) {
                return CommandReason.TYPE_MISMATCH;
            }
        }
        return CommandReason.OK;
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
            return WandLinkCache.node(draft.nodeId())
                    .map(io.github.crafteve.idlefactory.network.payload.WandLinksS2C.NodeLink::typeBlockId)
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
