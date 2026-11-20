package io.github.crafteve.idlefactory.client.world;

import io.github.crafteve.idlefactory.client.wand.WandLinkCache;

import io.github.crafteve.idlefactory.client.wand.WandDraftState;


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
import io.github.crafteve.idlefactory.location.BlockKey;
import io.github.crafteve.idlefactory.item.MachineBindingWandItem;
import io.github.crafteve.idlefactory.network.payload.WandLinksS2C;

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
 * 两把绑定杖的世界框：机器绿、草稿黄、冲突（含待删除）红、仓库蓝。
 *
 * <p>按 SFM 标签枪的做法：在 {@code AFTER_PARTICLES} 画填充方块面，先乘相机旋转的逆矩阵，
 * 再平移到相机相对坐标；每种颜色一份缓存 VBO，只在坐标集合变化时重建。只画没有被同组方块
 * 挡住的面，整组一次 draw，CPU 每帧不逐方块提交。</p>
 *
 * <p>数据只读：机器与仓库来自 {@link WandLinkCache}，草稿与冲突来自 {@link WandDraftState}。任意界面
 * 打开时（含草稿面板）不画；不做曲线、悬停、世界内动画。</p>
 */
@EventBusSubscriber(modid = IdleFactory.MODID, bus = EventBusSubscriber.Bus.GAME,
        value = Dist.CLIENT)
public final class MachineWandRenderer {
    /** 顶点缓冲初始大小。 */
    private static final int BUFFER_SIZE = 256;
    /** 加法混合用的透明度；三种颜色共用。 */
    private static final int ALPHA = 100;

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

    private static final Map<Kind, VBOEntry> CACHE = new EnumMap<>(Kind.class);

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
        if (minecraft.player == null || minecraft.level == null || minecraft.screen != null) {
            return;
        }
        if (!(minecraft.player.getMainHandItem().getItem() instanceof MachineBindingWandItem)) {
            return;
        }
        ResourceKey<Level> dimension = minecraft.level.dimension();
        boolean editing = WandDraftState.isEditing();
        java.util.UUID editingNode = editing ? WandDraftState.draft().nodeId() : null;
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
        draw(Kind.MACHINE, machineBlocks, event);
        if (!editing) {
            return;
        }
        // 编辑已有节点时，本节点的成员从绿框改成黄框：玩家要一眼看出这次能改哪些机器。
        addBlocks(editableBlocks, WandDraftState.draft().added(), dimension);
        for (BlockKey block : WandDraftState.removedBlocks()) {
            if (block.dimension().equals(dimension)) {
                editableBlocks.remove(block.pos().asLong());
            }
        }
        draw(Kind.DRAFT_ADDED, editableBlocks, event);
        LongOpenHashSet conflicts = new LongOpenHashSet();
        addBlocks(conflicts, WandDraftState.conflictBlocks(), dimension);
        addBlocks(conflicts, WandDraftState.removedBlocks(), dimension);
        draw(Kind.DRAFT_CONFLICT, conflicts, event);
    }

    /** 释放全部 VBO；登录 / 登出 / 换维度时由客户端缓存调用。 */
    public static void clearCache() {
        for (VBOEntry entry : CACHE.values()) {
            entry.vbo().close();
        }
        CACHE.clear();
    }

    /**
     * 画一种颜色的坐标集合。
     *
     * @param kind      颜色种类
     * @param positions 当前维度里的坐标
     * @param event     渲染事件
     */
    private static void draw(Kind kind, LongOpenHashSet positions, RenderLevelStageEvent event) {
        if (positions.isEmpty()) {
            drop(kind);
            return;
        }
        VBOEntry entry = CACHE.get(kind);
        if (entry == null || !entry.positions().equals(positions)) {
            if (entry != null) {
                entry.vbo().close();
            }
            entry = createEntry(kind, positions);
            CACHE.put(kind, entry);
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

    /**
     * 丢掉一种颜色的缓存；坐标集合空了时调。
     *
     * @param kind 颜色种类
     */
    private static void drop(Kind kind) {
        VBOEntry entry = CACHE.remove(kind);
        if (entry != null) {
            entry.vbo().close();
        }
    }

    /**
     * 新建一份 VBO。
     *
     * @param kind      颜色种类
     * @param positions 坐标集合
     * @return 缓存条目
     */
    private static VBOEntry createEntry(Kind kind, LongOpenHashSet positions) {
        BlockPos origin = BlockPos.of(positions.longIterator().nextLong());
        VertexBuffer vbo = createVbo(positions, origin, kind.color());
        return new VBOEntry(new LongOpenHashSet(positions), origin, vbo);
    }

    /**
     * 收集当前维度里的机器方块坐标。
     *
     * @param blocks    全部方块
     * @param dimension 当前维度
     * @return 坐标集合
     */
    private static LongOpenHashSet collectBlocks(List<BlockKey> blocks,
            ResourceKey<Level> dimension) {
        LongOpenHashSet positions = new LongOpenHashSet(Math.max(16, blocks.size()));
        addBlocks(positions, blocks, dimension);
        return positions;
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

    /** 一种颜色的缓存条目。 */
    private record VBOEntry(LongOpenHashSet positions, BlockPos origin, VertexBuffer vbo) {
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
