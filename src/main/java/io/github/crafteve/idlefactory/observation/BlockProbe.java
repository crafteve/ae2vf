package io.github.crafteve.idlefactory.observation;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.key.BlockKey;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * 全项目唯一「判断这个位置有什么」的地方。
 *
 * <p>两条档位：{@link #presence} 读方块 id、方块实体、是否具备机器能力、是否具备能量能力；
 * {@link #workState} 读方块状态判断是否在干活。执行层（{@code PortExecutor}）为了搬运仍会
 * 直接取能力，但不得据此改写观测——观测只由这里的结果驱动。</p>
 *
 * <p><b>区块未加载时不许当作「不存在」</b>：{@link #presence} 会先查 {@code isLoaded}，
 * 未加载直接返回 {@link Health#UNLOADED}，不会去读方块。</p>
 */
public final class BlockProbe {
    private BlockProbe() {
        // 纯静态工具类
    }

    /**
     * 一次「有什么」的探测结果。
     *
     * @param health          结论
     * @param blockId         方块 id；未加载／维度不存在时为 {@code null}
     * @param beTypeId        方块实体类型 id；没有方块实体时为 {@code null}
     * @param machineCapable  有方块实体，且至少提供物品 / 流体 / 能量之一，或是原版容器
         * @param legacyContainer 是不是原版 {@code Container}（没注册能力的模组方块兜底）
     */
    public record Presence(Health health,
                           @Nullable ResourceLocation blockId,
                           @Nullable ResourceLocation beTypeId,
                           boolean machineCapable,
                           boolean legacyContainer) {

        /**
         * 只要结论、没有细节的占位结果。
         *
         * @param health 结论
         * @return 空结果
         */
        public static Presence empty(Health health) {
            return new Presence(health, null, null, false, false);
        }

        /**
         * 能力摘要，只用于诊断日志。
         *
         * @return 如 {@code machine+energy}；都没有时是 {@code none}
         */
        public String describe() {
            return this.machineCapable ? "machine" : "none";
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
            return new Presence(Health.MISSING, blockId, null, false, false);
        }

        ResourceLocation beTypeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
        boolean item = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null) != null;
        boolean fluid = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null) != null;
        boolean energy = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, null) != null;
        boolean legacy = blockEntity instanceof Container;
        boolean machine = item || fluid || energy || legacy;
        return new Presence(machine ? Health.PRESENT : Health.MISSING, blockId, beTypeId,
                machine, legacy);
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
                    && !name.equals("lit") && !name.equals("running")) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Property<Boolean> booleanProperty = (Property<Boolean>) property;
            return state.getValue(booleanProperty) ? WorkState.WORKING : WorkState.IDLE;
        }
        return WorkState.UNKNOWN;
    }
}
