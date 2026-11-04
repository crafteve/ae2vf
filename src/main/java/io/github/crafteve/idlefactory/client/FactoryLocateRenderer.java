package io.github.crafteve.idlefactory.client;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import io.github.crafteve.idlefactory.IdleFactory;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 节点终端的「定位」高亮：在目标方块周围画 3 秒线框。
 *
 * <p>纯客户端，不发包。与绑定杖的连接高亮不同，它在界面打开时也要画，所以不复用
 * {@link FactoryLinkRenderer} 的 {@code screen != null} 早退；只画一个方块，不需要 VBO 缓存。</p>
 */
@EventBusSubscriber(modid = IdleFactory.MODID, bus = EventBusSubscriber.Bus.GAME,
        value = Dist.CLIENT)
public final class FactoryLocateRenderer {
    /** 高亮持续时间（毫秒）。 */
    private static final long DURATION_MS = 3000L;
    /** 线框颜色：青蓝。 */
    private static final float RED = 0.35F;
    private static final float GREEN = 0.85F;
    private static final float BLUE = 1.0F;
    private static final float ALPHA = 1.0F;

    @Nullable
    private static Target target;

    private FactoryLocateRenderer() {
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
        if (minecraft.level == null || minecraft.player == null
                || !minecraft.level.dimension().equals(current.dimension())) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(current.pos().getX() - camera.x, current.pos().getY() - camera.y,
                current.pos().getZ() - camera.z);
        VertexConsumer consumer = minecraft.renderBuffers().bufferSource()
                .getBuffer(RenderType.lines());
        RenderSystem.disableDepthTest();
        try {
            LevelRenderer.renderLineBox(pose, consumer,
                    new AABB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D).inflate(0.002D),
                    RED, GREEN, BLUE, ALPHA);
        } finally {
            RenderSystem.enableDepthTest();
        }
        minecraft.renderBuffers().bufferSource().endBatch(RenderType.lines());
        pose.popPose();
    }

    /** 一次定位请求。 */
    private record Target(ResourceKey<Level> dimension, BlockPos pos, long expiresAt) {
    }
}
