package io.github.crafteve.idlefactory.client.world;

import io.github.crafteve.idlefactory.client.wand.WandLinkCache;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.item.MeBindingWandItem;
import io.github.crafteve.idlefactory.location.BlockKey;

/**
 * 存储绑定杖的世界高亮：把仓库锚点画成蓝色六面半透明填充。
 *
 * <p>锚点来自 {@link WandLinkCache#storageAnchor()}（随链接快照整体替换，未绑定为 {@code null}）。
 * 只决定"画哪块、什么颜色"，绘制交给 {@link BlockHighlightRenderer}；界面打开时照常绘制。</p>
 */
@EventBusSubscriber(modid = IdleFactory.MODID, bus = EventBusSubscriber.Bus.GAME,
        value = Dist.CLIENT)
public final class MeWandRenderer {
    /** 仓库锚点颜色：蓝。 */
    private static final int COLOR = 0xFF4DA6FF;

    private MeWandRenderer() {
        // 纯静态渲染
    }

    /**
     * 世界渲染：主手持存储杖且锚点就在当前维度时画一块。
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
        if (!(minecraft.player.getMainHandItem().getItem() instanceof MeBindingWandItem)) {
            return;
        }
        BlockKey anchor = WandLinkCache.storageAnchor();
        if (anchor == null || !anchor.dimension().equals(minecraft.level.dimension())) {
            return;
        }
        BlockHighlightRenderer.drawBlock(COLOR, anchor.pos(), event);
    }
}
