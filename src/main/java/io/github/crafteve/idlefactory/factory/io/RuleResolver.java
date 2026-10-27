package io.github.crafteve.idlefactory.factory.io;

import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.observation.LevelCapabilityCache;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 把一条规则解析成「用哪个面、哪个能力句柄」。
 *
 * <p>三层策略，从便宜到贵：</p>
 * <ol>
 *   <li><b>玩家配了面</b>：直接用，不查路由、不扫面（配置优先）；</li>
 *   <li><b>路由表里有记录</b>：直接按上次搬通的面取 handler，一次查表；</li>
 *   <li><b>都没有</b>：扫面。流体/能量逐面「模拟搬一点」，物品按无向语义。</li>
 * </ol>
 *
 * <p>物品保持无向语义：{@code getCapability(..., null)} 才是「整个库存」，符合仓库对容器的
 * 直觉；流体和能量才需要尊重机器的面配置。</p>
 */
public final class RuleResolver {
    /** 能量面探测用的模拟量：Mekanism 的 FE↔J 换算会在小数值上截断。 */
    private static final int ENERGY_PROBE = 1000;

    /**
     * 解析出来的搬运通道。
     *
     * @param adapter 适配器
     * @param face    实际用的面；{@code null} 表示无向
     */
    public record Resolved(ResourceAdapter adapter, @Nullable Direction face) {
    }

    private RuleResolver() {
        // 纯静态工具类
    }

    /**
     * 解析一条规则。
     *
     * @param level      机器所在维度
     * @param pos        机器坐标
     * @param spec       规则
     * @param input      {@code true} 表示输入方向
     * @param caps       能力句柄缓存；{@code null} 时直接查世界
     * @param routes     面路由表；{@code null} 时每次扫面
     * @param generation 观测层的面表代数
     * @return 通道；没有可用 handler 时为 {@code null}
     */
    @Nullable
    public static Resolved resolve(ServerLevel level, BlockPos pos, ResourceSpec spec, boolean input,
            @Nullable LevelCapabilityCache caps, @Nullable RouteTable routes, long generation) {
        if (spec.face() == null && routes != null) {
            Optional<RouteTable.Route> route = routes.route(spec, input, generation);
            if (route.isPresent()) {
                Resolved direct = build(level, pos, spec, caps, route.get().face());
                if (direct != null) {
                    return direct;
                }
                // 记录的面已经没有 handler（能力没了 / 方块换了）：踢掉重扫
                routes.clear(spec, input);
            }
        }
        return sweep(level, pos, spec, input, caps);
    }

    /**
     * 按指定面构建适配器（不扫面、不模拟）。
     *
     * @param face 面；{@code null} 表示无向
     */
    @Nullable
    private static Resolved build(ServerLevel level, BlockPos pos, ResourceSpec spec,
            @Nullable LevelCapabilityCache caps, @Nullable Direction face) {
        return switch (spec.key().kind()) {
            case ITEM -> {
                ItemStack sample = spec.key().toStack();
                IItemHandler handler = item(level, pos, caps, face);
                yield handler == null || sample.isEmpty()
                        ? null
                        : new Resolved(new ItemAdapter(handler, sample), face);
            }
            case FLUID -> {
                Fluid fluid = BuiltInRegistries.FLUID.get(spec.key().id());
                IFluidHandler handler = fluid(level, pos, caps, face);
                yield handler == null || fluid == Fluids.EMPTY
                        ? null
                        : new Resolved(new FluidAdapter(handler, fluid), face);
            }
            case ENERGY -> {
                IEnergyStorage handler = energy(level, pos, caps, face);
                yield handler == null ? null : new Resolved(new EnergyAdapter(handler), face);
            }
            case CHEMICAL -> null;
        };
    }

    /**
     * 扫面：配置优先，其次逐面模拟，最后无向兜底。
     */
    @Nullable
    private static Resolved sweep(ServerLevel level, BlockPos pos, ResourceSpec spec, boolean input,
            @Nullable LevelCapabilityCache caps) {
        Direction configured = spec.face();
        if (configured != null) {
            return build(level, pos, spec, caps, configured);
        }
        return switch (spec.key().kind()) {
            case ITEM -> build(level, pos, spec, caps, null);
            case FLUID -> sweepFluid(level, pos, spec, input, caps);
            case ENERGY -> sweepEnergy(level, pos, spec, input, caps);
            case CHEMICAL -> null;
        };
    }

    @Nullable
    private static Resolved sweepFluid(ServerLevel level, BlockPos pos, ResourceSpec spec, boolean input,
            @Nullable LevelCapabilityCache caps) {
        Fluid fluid = BuiltInRegistries.FLUID.get(spec.key().id());
        if (fluid == Fluids.EMPTY) {
            return null;
        }
        FluidStack probe = new FluidStack(fluid, 1);
        for (Direction side : Direction.values()) {
            IFluidHandler handler = fluid(level, pos, caps, side);
            if (handler == null) {
                continue;
            }
            int amount = input
                    ? handler.fill(probe, IFluidHandler.FluidAction.SIMULATE)
                    : handler.drain(probe, IFluidHandler.FluidAction.SIMULATE).getAmount();
            if (amount > 0) {
                return new Resolved(new FluidAdapter(handler, fluid), side);
            }
        }
        IFluidHandler fallback = fluid(level, pos, caps, null);
        return fallback == null ? null : new Resolved(new FluidAdapter(fallback, fluid), null);
    }

    @Nullable
    private static Resolved sweepEnergy(ServerLevel level, BlockPos pos, ResourceSpec spec, boolean input,
            @Nullable LevelCapabilityCache caps) {
        for (Direction side : Direction.values()) {
            IEnergyStorage handler = energy(level, pos, caps, side);
            if (handler == null) {
                continue;
            }
            // 输入看能不能收，输出看能不能抽——旧代码两边都用 receiveEnergy，是错的
            int probe = input
                    ? handler.receiveEnergy(ENERGY_PROBE, true)
                    : handler.extractEnergy(ENERGY_PROBE, true);
            if (probe > 0) {
                return new Resolved(new EnergyAdapter(handler), side);
            }
        }
        IEnergyStorage fallback = energy(level, pos, caps, null);
        return fallback == null ? null : new Resolved(new EnergyAdapter(fallback), null);
    }

    @Nullable
    private static IItemHandler item(ServerLevel level, BlockPos pos,
            @Nullable LevelCapabilityCache caps, @Nullable Direction face) {
        return caps == null
                ? level.getCapability(Capabilities.ItemHandler.BLOCK, pos, face)
                : caps.item(pos, face);
    }

    @Nullable
    private static IFluidHandler fluid(ServerLevel level, BlockPos pos,
            @Nullable LevelCapabilityCache caps, @Nullable Direction face) {
        return caps == null
                ? level.getCapability(Capabilities.FluidHandler.BLOCK, pos, face)
                : caps.fluid(pos, face);
    }

    @Nullable
    private static IEnergyStorage energy(ServerLevel level, BlockPos pos,
            @Nullable LevelCapabilityCache caps, @Nullable Direction face) {
        return caps == null
                ? level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, face)
                : caps.energy(pos, face);
    }
}
