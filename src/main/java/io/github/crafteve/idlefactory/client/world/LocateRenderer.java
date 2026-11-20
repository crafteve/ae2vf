package io.github.crafteve.idlefactory.client.world;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 节点终端的「定位」高亮：把目标方块画成六面半透明填充，持续 3 秒。
 *
 * <p>纯客户端，不发包。绘制统一走 {@link BlockHighlightRenderer}，与两种绑定杖同一套外观；
 * 界面打开时也照常绘制，所以没有 {@code screen != null} 早退。</p>
 */
@EventBusSubscriber(modid = IdleFactory.MODID, bus = EventBusSubscriber.Bus.GAME,
        value = Dist.CLIENT)
public final class LocateRenderer {
    /** 高亮持续时间（毫秒）。 */
    private static final long DURATION_MS = 3000L;
    /** 高亮颜色：青蓝。 */
    private static final int COLOR = 0xFF59D9FF;

    @Nullable
    private static Target target;

    private LocateRenderer() {
        // 纯静态渲染
    }

    /**
     * 让一个方块高亮 3 秒；重复调用会重置计时。
     *
     * @param dimension 方块所在维度
     * @param pos       方块坐标
     */
    public static void show(ResourceKey<Level> dimension, BlockPos pos) {
        target = new Target(dimension, pos, Util.getMillis() + DURATION_MS);
    }

    /**
     * 世界渲染：只画当前维度里还没过期的目标。
     *
     * @param event 世界渲染事件
     */
    @SubscribeEvent
    static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Target current = target;
        if (current == null) {
            return;
        }
        if (Util.getMillis() > current.expiresAt()) {
            target = null;
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.dimension().equals(current.dimension())) {
            return;
        }
        BlockHighlightRenderer.drawBlock(COLOR, current.pos(), event);
    }

    /** 一次定位请求。 */
    private record Target(ResourceKey<Level> dimension, BlockPos pos, long expiresAt) {
    }
}
