package io.github.crafteve.idlefactory.observation;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.capability.CapabilityKind;
import io.github.crafteve.idlefactory.factory.capability.FaceTable;
import io.github.crafteve.idlefactory.location.BlockKey;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * 全项目唯一「判断这个位置有什么」的地方。
 *
 * <p>两档成本，别混用：</p>
 * <ul>
 *   <li>{@link #presence(MinecraftServer, BlockKey)}：加载判定 + 方块 id + 方块实体 + 三项
 *       无向能力，约 4 次能力查询。只回答「它还是不是一台能用的机器」。</li>
 *   <li>{@link #faces(ServerLevel, BlockPos)}：7 向 × 3 类 = 21 次能力查询。只回答「哪个面能干什么」，
 *       调用方必须缓存（{@link Observation} 负责）。</li>
 * </ul>
 *
 * <p><b>区块未加载时不许当作「不存在」</b>：{@link #presence} 会先查 {@code isLoaded}，未加载直接
 * 返回 {@link Health#UNLOADED}，不会去读方块。这是整套观测最容易被破坏的一条纪律。</p>
 *
 * <p>执行层（{@code IoExecutor} / {@code ContainerStorage}）为了搬运仍可直接取 handler，
 * 但不得据此改写观测——观测只由这里的结果驱动。</p>
 */
public final class BlockProbe {
    private BlockProbe() {
        // 纯静态工具类
    }

    /**
     * 一次「有什么」的探测结果。
     *
     * @param health          结论
     * @param blockId         观测到的方块 id；未加载／维度不存在时为 {@code null}
     * @param beTypeId        方块实体类型 id；没有方块实体时为 {@code null}
     * @param kinds           无向查询报告的能力
     * @param legacyContainer 是不是原版 {@code Container}（没注册能力的模组方块兜底）
     */
    public record Presence(Health health,
                           @Nullable ResourceLocation blockId,
                           @Nullable ResourceLocation beTypeId,
                           Set<CapabilityKind> kinds,
                           boolean legacyContainer) {

        public Presence {
            EnumSet<CapabilityKind> copy = EnumSet.noneOf(CapabilityKind.class);
            copy.addAll(kinds);
            kinds = Collections.unmodifiableSet(copy);
        }

        /**
         * 只要结论、没有细节的占位结果。
         *
         * @param health 结论
         * @return 空结果
         */
        public static Presence empty(Health health) {
            return new Presence(health, null, null, Set.of(), false);
        }

        /**
         * 能力摘要：{@code item+fluid+energy}，没有能力时是 {@code none}。
         *
         * @return 摘要文本
         */
        public String describe() {
            return FaceTable.describe(this.kinds);
        }
    }

    /**
     * 便宜档探测：这个位置现在是什么。
     *
     * @param server 服务端
     * @param key    观测键
     * @return 探测结果
     */
    public static Presence presence(MinecraftServer server, BlockKey key) {
        ServerLevel level = server.getLevel(key.dimension());
        if (level == null) {
            return Presence.empty(Health.NO_DIMENSION);
        }
        if (!level.isLoaded(key.pos())) {
            return Presence.empty(Health.UNLOADED);
        }

        BlockPos pos = key.pos();
        BlockState state = level.getBlockState(pos);
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return new Presence(Health.MISSING, blockId, null, Set.of(), false);
        }

        ResourceLocation beTypeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
        EnumSet<CapabilityKind> kinds = capabilities(level, pos, null);
        boolean legacy = blockEntity instanceof Container;
        Health health = kinds.isEmpty() && !legacy ? Health.MISSING : Health.PRESENT;
        return new Presence(health, blockId, beTypeId, kinds, legacy);
    }

    /**
     * 探测机器是否在工作。
     *
     * <p>本轮只读方块状态；没有可靠状态就返回 {@link WorkState#UNKNOWN}，
     * 绝不用 IO 结果反推。</p>
     *
     * @param level 服务端世界
     * @param pos   方块坐标
     * @return 工作状态
     */
    public static WorkState workState(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return WorkState.UNKNOWN;
        }
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof AbstractFurnaceBlock) {
            return state.getValue(AbstractFurnaceBlock.LIT) ? WorkState.WORKING : WorkState.IDLE;
        }
        if (state.getBlock() instanceof CampfireBlock) {
            return state.getValue(CampfireBlock.LIT) ? WorkState.WORKING : WorkState.IDLE;
        }

        for (Property<?> property : state.getProperties()) {
            if (property.getValueClass() != Boolean.class) {
                continue;
            }
            String name = property.getName();
            if (!name.equals("active") && !name.equals("working")
                    && !name.equals("running") && !name.equals("lit")) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Property<Boolean> booleanProperty = (Property<Boolean>) property;
            return state.getValue(booleanProperty) ? WorkState.WORKING : WorkState.IDLE;
        }
        return WorkState.UNKNOWN;
    }

    /**
     * 昂贵档探测：每个面能干什么。
     *
     * <p>调用方负责缓存与失效；这里每次都真的查 21 次。</p>
     *
     * @param level 世界（必须是服务端、且区块已加载）
     * @param pos   方块坐标
     * @return 面表；没有任何能力时是 {@link FaceTable#EMPTY}
     */
    public static FaceTable faces(ServerLevel level, BlockPos pos) {
        Map<Direction, Set<CapabilityKind>> byFace = new EnumMap<>(Direction.class);
        for (Direction face : Direction.values()) {
            EnumSet<CapabilityKind> kinds = capabilities(level, pos, face);
            if (!kinds.isEmpty()) {
                byFace.put(face, kinds);
            }
        }
        FaceTable table = new FaceTable(byFace, capabilities(level, pos, null));
        return table.any() ? table : FaceTable.EMPTY;
    }

    private static EnumSet<CapabilityKind> capabilities(Level level, BlockPos pos, @Nullable Direction face) {
        EnumSet<CapabilityKind> kinds = EnumSet.noneOf(CapabilityKind.class);
        if (level.getCapability(Capabilities.ItemHandler.BLOCK, pos, face) != null) {
            kinds.add(CapabilityKind.ITEM);
        }
        if (level.getCapability(Capabilities.FluidHandler.BLOCK, pos, face) != null) {
            kinds.add(CapabilityKind.FLUID);
        }
        if (level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, face) != null) {
            kinds.add(CapabilityKind.ENERGY);
        }
        return kinds;
    }
}
