package io.github.crafteve.idlefactory.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.item.MachineBindingWandItem;
import io.github.crafteve.idlefactory.item.StorageBindingWandItem;
import io.github.crafteve.idlefactory.network.payload.FactoryLinksS2C;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 已绑定机器/仓库的荧光框。
 *
 * <p>只主手拿对应绑定杖时画：机器绿、仓库蓝；穿墙可见。按当前维度、64 格距离和视锥裁剪，
 * 一帧只提交两次线框（外层淡光 + 内层亮线），不逐个方块 draw。</p>
 */
@EventBusSubscriber(modid = IdleFactory.MODID, bus = EventBusSubscriber.Bus.GAME,
        value = Dist.CLIENT)
public final class FactoryLinkRenderer {
    /** 只画 64 格内的框。 */
    private static final double MAX_DISTANCE = 64.0D;
    private static final float MACHINE_R = 0.20F;
    private static final float MACHINE_G = 1.00F;
    private static final float MACHINE_B = 0.45F;
    private static final float STORAGE_R = 0.25F;
    private static final float STORAGE_G = 0.65F;
    private static final float STORAGE_B = 1.00F;

    private FactoryLinkRenderer() {
        // 纯静态渲染
    }

    /**
     * 在世界半透明层之后画框。
     *
     * @param event 世界渲染事件
     */
    @SubscribeEvent
    static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.screen != null) {
            return;
        }
        boolean machineWand = minecraft.player.getMainHandItem().getItem()
                instanceof MachineBindingWandItem;
        boolean storageWand = minecraft.player.getMainHandItem().getItem()
                instanceof StorageBindingWandItem;
        if (!machineWand && !storageWand) {
            return;
        }
        List<FactoryLinksS2C.Link> links = machineWand
                ? ClientFactoryLinks.machines() : ClientFactoryLinks.storage();
        if (links.isEmpty()) {
            return;
        }

        Vec3 camera = event.getCamera().getPosition();
        Frustum frustum = event.getLevelRenderer().getFrustum();
        List<AABB> boxes = new ArrayList<>(links.size());
        for (FactoryLinksS2C.Link link : links) {
            if (!link.dimension().equals(minecraft.level.dimension())) {
                continue;
            }
            Vec3 center = Vec3.atCenterOf(link.pos());
            if (center.distanceToSqr(camera) > MAX_DISTANCE * MAX_DISTANCE) {
                continue;
            }
            AABB box = new AABB(link.pos().getX(), link.pos().getY(), link.pos().getZ(),
                    link.pos().getX() + 1.0D, link.pos().getY() + 1.0D,
                    link.pos().getZ() + 1.0D);
            if (frustum != null && !frustum.isVisible(box)) {
                continue;
            }
            boxes.add(box);
        }
        if (boxes.isEmpty()) {
            return;
        }

        float red = machineWand ? MACHINE_R : STORAGE_R;
        float green = machineWand ? MACHINE_G : STORAGE_G;
        float blue = machineWand ? MACHINE_B : STORAGE_B;

        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        try {
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            drawBoxes(pose, boxes, red, green, blue, 0.25F, 0.02D);
            drawBoxes(pose, boxes, red, green, blue, 1.0F, 0.005D);
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            pose.popPose();
        }
    }

    private static void drawBoxes(PoseStack pose, List<AABB> boxes, float red, float green,
            float blue, float alpha, double inflate) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        BufferBuilder buffer = Tesselator.getInstance().begin(
                VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
        for (AABB box : boxes) {
            LevelRenderer.renderLineBox(pose, buffer, box.inflate(inflate), red, green, blue, alpha);
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }
}
