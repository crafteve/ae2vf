package io.github.crafteve.idlefactory.client;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.wand.WandLinks;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.item.MachineBindingWandItem;
import io.github.crafteve.idlefactory.item.StorageBindingWandItem;
import io.github.crafteve.idlefactory.network.payload.FactoryLinksS2C;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 已绑定机器/仓库的荧光块。
 *
 * <p>按 SFM 标签枪的做法：在 {@code AFTER_PARTICLES} 画填充方块面，先乘相机旋转的逆矩阵，
 * 再平移到相机相对坐标；使用缓存 VBO，只在坐标集合变化时重建。只画没有被同组方块挡住的
 * 面，整组一次 draw，CPU 每帧不逐方块提交。</p>
 *
 * <p>颜色：机器绿、仓库蓝；深度永远通过，加法混合，所以穿墙可见。链接来自
 * {@link WandLinks}，只在主手拿对应绑定杖时画。</p>
 */
@EventBusSubscriber(modid = IdleFactory.MODID, bus = EventBusSubscriber.Bus.GAME,
        value = Dist.CLIENT)
public final class FactoryLinkRenderer {
    private static final int BUFFER_SIZE = 256;
    /** 加法混合用的透明度；机器绿 / 仓库蓝共用。 */
    private static final int ALPHA = 100;
    private static final int MACHINE_COLOR = 0xFF33FF73;
    private static final int STORAGE_COLOR = 0xFF40A6FF;

    @SuppressWarnings("deprecation")
    private static final RenderType LINK_TYPE = RenderType.create(
            "idlefactory_links",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            BUFFER_SIZE,
            false,
            false,
            RenderType.CompositeState.builder()
                    .setTextureState(new RenderStateShard.TextureStateShard(
                            TextureAtlas.LOCATION_BLOCKS, false, false))
                    .setDepthTestState(new RenderStateShard.DepthTestStateShard("always", 519))
                    .setTransparencyState(new RenderStateShard.TransparencyStateShard(
                            "src_to_one",
                            () -> {
                                RenderSystem.enableBlend();
                                RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA,
                                        GlStateManager.DestFactor.ONE);
                            },
                            () -> {
                                RenderSystem.disableBlend();
                                RenderSystem.defaultBlendFunc();
                            }))
                    .createCompositeState(true));

    private static final VBOCache CACHE = new VBOCache();

    private FactoryLinkRenderer() {
        // 纯静态渲染
    }

    /**
     * 世界渲染：主手绑定杖、当前维度、已缓存链接。
     *
     * @param event 世界渲染事件
     */
    @SubscribeEvent
    static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
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
        LongOpenHashSet positions = machineWand
                ? collectBlocks(WandLinks.machineBlocks(), minecraft.level.dimension())
                : collectLinks(WandLinks.storage(), minecraft.level.dimension());
        if (positions.isEmpty()) {
            return;
        }
        Kind kind = machineWand ? Kind.MACHINE : Kind.STORAGE;
        int color = machineWand ? MACHINE_COLOR : STORAGE_COLOR;
        VBOEntry entry = CACHE.get(kind, positions, event, color);
        if (entry == null) {
            return;
        }

        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.mulPose(camera.rotation().invert(new Quaternionf()));
        pose.translate(
                entry.origin().getX() - cameraPos.x,
                entry.origin().getY() - cameraPos.y,
                entry.origin().getZ() - cameraPos.z);

        LINK_TYPE.setupRenderState();
        RenderSystem.disableDepthTest();
        entry.vbo().bind();
        entry.vbo().drawWithShader(pose.last().pose(), event.getProjectionMatrix(),
                GameRenderer.getPositionColorShader());
        VertexBuffer.unbind();
        RenderSystem.enableDepthTest();
        LINK_TYPE.clearRenderState();
        pose.popPose();
    }

    /** 释放 VBO；登录/登出时由客户端缓存调用。 */
    public static void clearCache() {
        CACHE.clear();
    }

    /**
     * 收集当前维度里的机器方块坐标。
     *
     * @param blocks    全部机器方块
     * @param dimension 当前维度
     * @return 坐标集合
     */
    private static LongOpenHashSet collectBlocks(List<BlockKey> blocks, ResourceKey<Level> dimension) {
        LongOpenHashSet positions = new LongOpenHashSet(Math.max(16, blocks.size()));
        for (BlockKey block : blocks) {
            if (block.dimension().equals(dimension)) {
                positions.add(block.pos().asLong());
            }
        }
        return positions;
    }

    /**
     * 收集当前维度里的仓库坐标。
     *
     * @param links     仓库坐标
     * @param dimension 当前维度
     * @return 坐标集合
     */
    private static LongOpenHashSet collectLinks(List<FactoryLinksS2C.Link> links,
            ResourceKey<Level> dimension) {
        LongOpenHashSet positions = new LongOpenHashSet(Math.max(16, links.size()));
        for (FactoryLinksS2C.Link link : links) {
            if (link.dimension().equals(dimension)) {
                positions.add(link.pos().asLong());
            }
        }
        return positions;
    }

    private enum Kind {
        MACHINE,
        STORAGE
    }

    /**
     * 缓存一组坐标的 VBO。坐标未变时每帧只做一次集合比较，不重建网格；变化或首次才重建。
     */
    private static final class VBOCache {
        private final Map<Kind, VBOEntry> cache = new EnumMap<>(Kind.class);
        private int lastChangeCheck = -1;

        @Nullable
        private VBOEntry get(Kind kind, LongOpenHashSet positions, RenderLevelStageEvent event,
                int color) {
            if (positions.isEmpty()) {
                return null;
            }
            VBOEntry entry = cache.get(kind);
            boolean rebuild = entry == null;
            int renderTick = event.getRenderTick();
            // 集合比较比重建便宜，但也没必要每帧比；每 render tick 最多比一次。
            if (!rebuild && renderTick != lastChangeCheck) {
                lastChangeCheck = renderTick;
                if (!entry.positions().equals(positions)) {
                    rebuild = true;
                }
            }
            if (rebuild) {
                if (entry != null) {
                    entry.vbo().close();
                }
                BlockPos origin = BlockPos.of(positions.longIterator().nextLong());
                VertexBuffer vbo = createVbo(positions, origin, color);
                entry = new VBOEntry(new LongOpenHashSet(positions), origin, vbo);
                cache.put(kind, entry);
            }
            return entry;
        }

        private void clear() {
            for (VBOEntry entry : cache.values()) {
                entry.vbo().close();
            }
            cache.clear();
            lastChangeCheck = -1;
        }
    }

    private static VertexBuffer createVbo(LongOpenHashSet positions, BlockPos origin, int color) {
        int red = color >> 16 & 0xFF;
        int green = color >> 8 & 0xFF;
        int blue = color & 0xFF;
        int alpha = ALPHA;

        PoseStack pose = new PoseStack();
        BufferBuilder buffer = Tesselator.getInstance().begin(
                LINK_TYPE.mode(), LINK_TYPE.format());
        LongIterator iterator = positions.longIterator();
        while (iterator.hasNext()) {
            BlockPos pos = BlockPos.of(iterator.nextLong());
            pose.pushPose();
            pose.translate(
                    pos.getX() - origin.getX(),
                    pos.getY() - origin.getY(),
                    pos.getZ() - origin.getZ());
            Matrix4f matrix = pose.last().pose();
            for (Direction face : Direction.values()) {
                if (!positions.contains(pos.relative(face).asLong())) {
                    writeFace(buffer, matrix, face, red, green, blue, alpha);
                }
            }
            pose.popPose();
        }
        MeshData mesh = buffer.buildOrThrow();
        VertexBuffer vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
        vbo.bind();
        vbo.upload(mesh);
        VertexBuffer.unbind();
        return vbo;
    }

    private static void writeVertex(VertexConsumer builder, Matrix4f matrix, float x, float y,
            float z, int red, int green, int blue, int alpha) {
        builder.addVertex(matrix, x, y, z).setColor(red, green, blue, alpha);
    }

    /**
     * 写一个面。按面方向做轻微明暗，避免整块纯色；顶点顺序沿用 SFM，保证法线/绕序一致。
     */
    private static void writeFace(VertexConsumer builder, Matrix4f matrix, Direction direction,
            int red, int green, int blue, int alpha) {
        double scale = 1.0D - (double) direction.ordinal() / 25.0D;
        int r = (int) (red * scale);
        int g = (int) (green * scale);
        int b = (int) (blue * scale);
        int a = (int) (alpha * scale);
        switch (direction) {
            case DOWN -> {
                writeVertex(builder, matrix, 0F, 0F, 0F, r, g, b, a);
                writeVertex(builder, matrix, 1F, 0F, 0F, r, g, b, a);
                writeVertex(builder, matrix, 1F, 0F, 1F, r, g, b, a);
                writeVertex(builder, matrix, 0F, 0F, 1F, r, g, b, a);
            }
            case UP -> {
                writeVertex(builder, matrix, 0F, 1F, 1F, r, g, b, a);
                writeVertex(builder, matrix, 1F, 1F, 1F, r, g, b, a);
                writeVertex(builder, matrix, 1F, 1F, 0F, r, g, b, a);
                writeVertex(builder, matrix, 0F, 1F, 0F, r, g, b, a);
            }
            case NORTH -> {
                writeVertex(builder, matrix, 0F, 0F, 0F, r, g, b, a);
                writeVertex(builder, matrix, 0F, 1F, 0F, r, g, b, a);
                writeVertex(builder, matrix, 1F, 1F, 0F, r, g, b, a);
                writeVertex(builder, matrix, 1F, 0F, 0F, r, g, b, a);
            }
            case SOUTH -> {
                writeVertex(builder, matrix, 1F, 0F, 1F, r, g, b, a);
                writeVertex(builder, matrix, 1F, 1F, 1F, r, g, b, a);
                writeVertex(builder, matrix, 0F, 1F, 1F, r, g, b, a);
                writeVertex(builder, matrix, 0F, 0F, 1F, r, g, b, a);
            }
            case WEST -> {
                writeVertex(builder, matrix, 0F, 0F, 1F, r, g, b, a);
                writeVertex(builder, matrix, 0F, 1F, 1F, r, g, b, a);
                writeVertex(builder, matrix, 0F, 1F, 0F, r, g, b, a);
                writeVertex(builder, matrix, 0F, 0F, 0F, r, g, b, a);
            }
            case EAST -> {
                writeVertex(builder, matrix, 1F, 0F, 0F, r, g, b, a);
                writeVertex(builder, matrix, 1F, 1F, 0F, r, g, b, a);
                writeVertex(builder, matrix, 1F, 1F, 1F, r, g, b, a);
                writeVertex(builder, matrix, 1F, 0F, 1F, r, g, b, a);
            }
        }
    }

    private record VBOEntry(LongOpenHashSet positions, BlockPos origin, VertexBuffer vbo) {
    }
}
