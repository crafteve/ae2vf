package io.github.crafteve.idlefactory.client.world;

import java.util.List;
import java.util.UUID;

import io.github.crafteve.idlefactory.client.wand.WandDraftState;
import io.github.crafteve.idlefactory.client.wand.WandLinkCache;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.item.MachineBindingWandItem;
import io.github.crafteve.idlefactory.key.BlockKey;

/**
 * 机器绑定杖的世界高亮：已绑定绿、草稿待加入橙、冲突或待删除红。
 *
 * <p>只决定"哪些方块、哪种颜色"，绘制统一交给 {@link BlockHighlightRenderer}——定位与存储杖
 * 共用同一套六面填充，三处不需要各自维护 VBO 与剔除逻辑。</p>
 *
 * <p>数据只读：机器与仓库来自 {@link WandLinkCache}，草稿与冲突来自 {@link WandDraftState}。
 * 界面打开时照常绘制：高亮是世界信息。</p>
 */
@EventBusSubscriber(modid = IdleFactory.MODID, value = Dist.CLIENT)
public final class MachineWandRenderer {
    private MachineWandRenderer() {
        // 纯静态渲染
    }

    /**
     * 世界渲染：按主手物品与草稿状态画对应颜色。
     *
     * @param event 世界渲染事件
     */
    @SubscribeEvent
    static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        if (!(minecraft.player.getMainHandItem().getItem() instanceof MachineBindingWandItem)) {
            return;
        }
        ResourceKey<Level> dimension = minecraft.level.dimension();
        boolean editing = WandDraftState.isEditing();
        UUID editingNode = editing ? WandDraftState.draft().nodeId() : null;
        LongOpenHashSet machineBlocks = new LongOpenHashSet();
        LongOpenHashSet editableBlocks = new LongOpenHashSet();
        for (BlockKey block : WandLinkCache.boundBlocks()) {
            if (!block.dimension().equals(dimension)) {
                continue;
            }
            boolean ownNode = editingNode != null
                    && WandLinkCache.owner(block).map(owner -> owner.nodeId().equals(editingNode))
                            .orElse(false);
            if (ownNode) {
                editableBlocks.add(block.pos().asLong());
            } else {
                machineBlocks.add(block.pos().asLong());
            }
        }
        BlockHighlightRenderer.draw(Kind.MACHINE.color(), machineBlocks, event);
        if (!editing) {
            return;
        }
        // 编辑已有节点时，本节点的成员从绿框改成橙框：玩家要一眼看出这次能改哪些机器。
        addBlocks(editableBlocks, WandDraftState.draft().added(), dimension);
        for (BlockKey block : WandDraftState.removedBlocks()) {
            if (block.dimension().equals(dimension)) {
                editableBlocks.remove(block.pos().asLong());
            }
        }
        BlockHighlightRenderer.draw(Kind.DRAFT_ADDED.color(), editableBlocks, event);
        LongOpenHashSet conflicts = new LongOpenHashSet();
        addBlocks(conflicts, WandDraftState.conflictBlocks(), dimension);
        addBlocks(conflicts, WandDraftState.removedBlocks(), dimension);
        BlockHighlightRenderer.draw(Kind.DRAFT_CONFLICT.color(), conflicts, event);
    }

    /** 释放全部 VBO；登录 / 登出 / 换维度时由客户端缓存调用。 */
    public static void clearCache() {
        BlockHighlightRenderer.clearCache();
    }

    /**
     * 把指定维度的方块坐标加进集合。
     *
     * @param positions 目标集合
     * @param blocks    方块
     * @param dimension 当前维度
     */
    private static void addBlocks(LongOpenHashSet positions, Iterable<BlockKey> blocks,
            ResourceKey<Level> dimension) {
        for (BlockKey block : blocks) {
            if (block.dimension().equals(dimension)) {
                positions.add(block.pos().asLong());
            }
        }
    }

    /** 三种世界框颜色。 */
    private enum Kind {
        /** 已绑定机器。 */
        MACHINE(0xFF33FF73),
        /** 草稿待加入。 */
        DRAFT_ADDED(0xFFFFCC33),
        /** 校验冲突或待删除。 */
        DRAFT_CONFLICT(0xFFFF4D4D);

        private final int color;

        Kind(int color) {
            this.color = color;
        }

        int color() {
            return this.color;
        }
    }
}
