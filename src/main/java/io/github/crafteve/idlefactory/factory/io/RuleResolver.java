package io.github.crafteve.idlefactory.factory.io;

import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.capability.LevelCapabilityCache;
import io.github.crafteve.idlefactory.factory.machine.ResourceSpec;

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
import io.github.crafteve.idlefactory.factory.storage.AeKeySupport;

/**
 * 把一条规则解析成「用哪个面、哪个能力句柄」。
 *
 * <p>三层策略，从便宜到贵：</p>
 * <ol>
 *   <li><b>玩家配了面</b>：直接用，不查路由、不扫面（配置优先）；</li>
 *   <li><b>路由表里有记录</b>：直接按上次搬通的面取 handler，一次查表；</li>
 *   <li><b>都没有</b>：扫面。物品先试无向 handler，再逐面找能接收/抽出该物品的面；
 *       流体/能量逐面「模拟搬一点」。</li>
 * </ol>
 *
 * <p>物品规则本身仍然是无向配置（{@code face} 恒为 {@code null}）：无向 handler 能服务时
 * 优先用它，保持「整个库存」的旧语义；像 Mekanism 那样无向查询只返回只读代理、真正 handler
 * 按面注册的机器，才退到六面探测。流体和能量一直尊重机器的面配置。</p>
 */
public final class RuleResolver {
    /** 能量面探测用的模拟量：Mekanism 的 FE↔J 换算会在小数值上截断。 */
    private static final int ENERGY_PROBE = 1000;
    /** 物品面探测的模拟量：1 个就足以判断这个面收不收 / 抽不抽得出来。 */
    private static final int ITEM_PROBE = 1;

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
        Resolved swept = sweep(level, pos, spec, input, caps);
        return swept;
    }

    /**
     * 按指定面构建适配器（不扫面、不模拟）。
     *
     * @param face 面；{@code null} 表示无向
     */
    @Nullable
    private static Resolved build(ServerLevel level, BlockPos pos, ResourceSpec spec,
            @Nullable LevelCapabilityCache caps, @Nullable Direction face) {
        return switch (AeKeySupport.kind(spec.key())) {
            case ITEM -> {
                ItemStack sample = AeKeySupport.toStack(spec.key());
                IItemHandler handler = item(level, pos, caps, face);
                yield handler == null || sample.isEmpty()
                        ? null
                        : new Resolved(new ItemAdapter(handler, sample), face);
            }
            case FLUID -> {
                Fluid fluid = BuiltInRegistries.FLUID.get(spec.key().getId());
                IFluidHandler handler = fluid(level, pos, caps, face);
                yield handler == null || fluid == Fluids.EMPTY
                        ? null
                        : new Resolved(new FluidAdapter(handler, fluid), face);
            }
            case ENERGY -> {
                IEnergyStorage handler = energy(level, pos, caps, face);
                yield handler == null ? null : new Resolved(new EnergyAdapter(handler), face);
            }
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
        return switch (AeKeySupport.kind(spec.key())) {
            case ITEM -> sweepItem(level, pos, spec, input, caps);
            case FLUID -> sweepFluid(level, pos, spec, input, caps);
            case ENERGY -> sweepEnergy(level, pos, spec, input, caps);
        };
    }

    /**
     * 物品：先无向、再逐面。
     *
     * <p>物品规则是无向配置，但「无向查询」不等于「可写」：Mekanism 的 {@code side == null}
     * 返回只读代理，insert/extract 都返回空。因此无向 handler 不存在或当前方向用不了时，必须
     * 逐个面找真正可用的 handler。找不到可用的面时仍回退无向 / 第一个面 handler，让下游报
     * 「满了 / 没料」而不是「没有 handler」。</p>
     */
    @Nullable
    private static Resolved sweepItem(ServerLevel level, BlockPos pos, ResourceSpec spec, boolean input,
            @Nullable LevelCapabilityCache caps) {
        ItemStack sample = AeKeySupport.toStack(spec.key());
        if (sample.isEmpty()) {
            return null;
        }
        IItemHandler unsided = item(level, pos, caps, null);
        if (unsided != null && itemAccessible(unsided, sample, spec.fuzzy(), input)) {
            return new Resolved(new ItemAdapter(unsided, sample), null);
        }
        IItemHandler firstSide = null;
        Direction firstFace = null;
        for (Direction side : Direction.values()) {
            IItemHandler handler = item(level, pos, caps, side);
            if (handler == null) {
                continue;
            }
            if (firstSide == null) {
                firstSide = handler;
                firstFace = side;
            }
            if (itemAccessible(handler, sample, spec.fuzzy(), input)) {
                return new Resolved(new ItemAdapter(handler, sample), side);
            }
        }
        if (unsided != null) {
            return new Resolved(new ItemAdapter(unsided, sample), null);
        }
        return firstSide == null
                ? null
                : new Resolved(new ItemAdapter(firstSide, sample), firstFace);
    }

    /**
     * 这个 handler 此刻能不能按规则方向服务该物品。
     *
     * <p>精确规则按完整组件比较；模糊规则按注册名，先试默认组件栈、再试规则自带的样本，
     * 最后扫机器里已有的变体（上限 {@link ItemAdapter#MAX_SCAN_SLOTS}），避免只探默认栈
     * 就否掉一个只认带组件变体的面。</p>
     */
    private static boolean itemAccessible(IItemHandler handler, ItemStack sample, boolean fuzzy,
            boolean input) {
        if (!fuzzy) {
            return probeItem(handler, sample, input);
        }
        ItemStack plain = new ItemStack(sample.getItem());
        if (probeItem(handler, plain, input)) {
            return true;
        }
        if (!ItemStack.isSameItemSameComponents(plain, sample)
                && probeItem(handler, sample, input)) {
            return true;
        }
        int slots = Math.min(handler.getSlots(), ItemAdapter.MAX_SCAN_SLOTS);
        for (int slot = 0; slot < slots; slot++) {
            ItemStack inSlot = handler.getStackInSlot(slot);
            if (!inSlot.isEmpty() && inSlot.getItem() == sample.getItem()
                    && probeItem(handler, inSlot.copyWithCount(1), input)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 用 1 个物品模拟一次「收 / 抽」。
     *
     * <p>复用 {@link ItemAdapter} 的槽位遍历，保证探测与实际搬运用的是同一套
     * insert/extract 语义。</p>
     */
    private static boolean probeItem(IItemHandler handler, ItemStack sample, boolean input) {
        ItemAdapter adapter = new ItemAdapter(handler, sample);
        return input
                ? adapter.insert(ITEM_PROBE, true) > 0L
                : adapter.extract(ITEM_PROBE, true) > 0L;
    }

    @Nullable
    private static Resolved sweepFluid(ServerLevel level, BlockPos pos, ResourceSpec spec, boolean input,
            @Nullable LevelCapabilityCache caps) {
        Fluid fluid = BuiltInRegistries.FLUID.get(spec.key().getId());
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
