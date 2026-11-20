package io.github.crafteve.idlefactory.client.world;

import java.util.HashMap;
import java.util.Map;

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

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 世界里的方块高亮：把一组坐标画成六面半透明填充块。
 *
 * <p>定位、机器绑定杖、存储绑定杖共用这一份绘制：同一套混合与深度设置、同一套"同组相邻面
 * 不画"的剔除、同一种按颜色缓存的 VBO。每种颜色一份缓存，坐标集合不变就不重建，整组一次
 * draw，CPU 每帧不逐方块提交。</p>
 *
 * <p>只画填充，不管"该不该画"：调用方负责判断维度、手持物与开关。界面打开时也照画——高亮
 * 是世界信息，不该被 GUI 挡住。</p>
 */
public final class BlockHighlightRenderer {
    /** 顶点缓冲初始大小。 */
    private static final int BUFFER_SIZE = 256;
    /** 加法混合用的透明度；所有颜色共用。 */
    private static final int ALPHA = 100;
    /** 六面明暗步长：按面序号轻微递减，避免整块纯色。 */
    private static final double FACE_SHADE_STEP = 25.0D;

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

    /** 颜色 → 缓存；同色多来源共用一份，调用方保证同一帧内同色只画一组。 */
    private static final Map<Integer, Entry> CACHE = new HashMap<>();

    private BlockHighlightRenderer() {
        // 纯静态渲染
    }

    /**
     * 画一组方块。
     *
     * @param color     ARGB 颜色
     * @param positions 当前维度里的坐标（方块坐标打包成 long）
     * @param event     世界渲染事件
     */
    public static void draw(int color, LongOpenHashSet positions, RenderLevelStageEvent event) {
        if (positions.isEmpty()) {
            drop(color);
            return;
        }
        Entry entry = CACHE.get(color);
        if (entry == null || !entry.positions().equals(positions)) {
            if (entry != null) {
                entry.vbo().close();
            }
            entry = createEntry(color, positions);
            CACHE.put(color, entry);
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

    /** 释放全部 VBO；登录 / 登出 / 换维度时调用。 */
    public static void clearCache() {
        for (Entry entry : CACHE.values()) {
            entry.vbo().close();
        }
        CACHE.clear();
    }

    /**
     * 画一个方块。
     *
     * @param color ARGB 颜色
     * @param pos   方块坐标
     * @param event 世界渲染事件
     */
    public static void drawBlock(int color, BlockPos pos, RenderLevelStageEvent event) {
        LongOpenHashSet positions = new LongOpenHashSet(1);
        positions.add(pos.asLong());
        draw(color, positions, event);
    }

    private static void drop(int color) {
        Entry entry = CACHE.remove(color);
        if (entry != null) {
            entry.vbo().close();
        }
    }

    private static Entry createEntry(int color, LongOpenHashSet positions) {
        BlockPos origin = BlockPos.of(positions.longIterator().nextLong());
        VertexBuffer vbo = createVbo(positions, origin, color);
        return new Entry(new LongOpenHashSet(positions), origin, vbo);
    }

    /**
     * 把坐标集合编译成一个静态 VBO。
     *
     * @param positions 坐标集合
     * @param origin    平移到原点的基准
     * @param color     ARGB 颜色
     * @return 顶点缓冲
     */
    private static VertexBuffer createVbo(LongOpenHashSet positions, BlockPos origin, int color) {
        int red = color >> 16 & 0xFF;
        int green = color >> 8 & 0xFF;
        int blue = color & 0xFF;

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
                    writeFace(buffer, matrix, face, red, green, blue, ALPHA);
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
     * 写一个面。
     *
     * <p>按面方向做轻微明暗，避免整块纯色；顶点顺序保证法线朝外与绕序一致。</p>
     */
    private static void writeFace(VertexConsumer builder, Matrix4f matrix, Direction direction,
            int red, int green, int blue, int alpha) {
        double scale = 1.0D - (double) direction.ordinal() / FACE_SHADE_STEP;
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

    /** 一种颜色的缓存条目。 */
    private record Entry(LongOpenHashSet positions, BlockPos origin, VertexBuffer vbo) {
    }
}
