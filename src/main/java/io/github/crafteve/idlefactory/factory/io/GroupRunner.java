package io.github.crafteve.idlefactory.factory.io;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.line.IoGroup;
import io.github.crafteve.idlefactory.factory.observation.LevelCapabilityCache;
import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
import io.github.crafteve.idlefactory.factory.resource.ResourceKind;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;
import io.github.crafteve.idlefactory.factory.storage.Storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 一个 IO 组的执行器：对一台机器按配置补料、抽产物。
 *
 * <p>三层取面策略，从便宜到贵：</p>
 * <ol>
 *   <li><b>玩家配了面</b>：直接用，不查路由、不扫面（配置优先）；</li>
 *   <li><b>路由表里有记录</b>：直接按上次搬通的面取 handler，一次查表；</li>
 *   <li><b>都没有</b>：扫面。流体/能量逐面「模拟搬一点」，物品按无向语义（见下）。</li>
 * </ol>
 *
 * <p>搬成功就记住这个面；{@link IoResult.Reason#REJECTED}（有容量却不收）说明面不对，踢掉路由下次重扫。
 * 玩家改规则、方块被换、能力失效都会让路由整体作废（观测层的面表代数 +1）。</p>
 *
 * <p><b>物品保持无向语义</b>：{@code getCapability(..., null)} 才是「整个库存」，符合仓库对容器的
 * 直觉；改成按面会悄悄改变既有玩法。流体和能量才需要尊重机器的面配置。</p>
 */
public final class GroupRunner {
    /** 能量面探测用的模拟量：Mekanism 的 FE↔J 换算会在小数值上截断。 */
    private static final int ENERGY_PROBE = 1000;

    /**
     * 一次组级搬运的结果。
     *
     * @param fed         补进机器的总数
     * @param pulled      从机器抽出的总数
     * @param fedByKey    按资源拆分的补料量
     * @param pulledByKey 按资源拆分的抽取量
     * @param fedByRule   按规则拆分的补料量
     * @param pulledByRule 按规则拆分的抽取量
     * @param lastReason  没搬动时的最后一个原因
     * @param inputFull   所有输入规则都无法接收
     * @param inputReason 输入侧原因
     * @param outputReason 输出侧原因
     * @param inputReasons 逐输入规则的本轮原因，供界面显示每条规则的诊断
     * @param outputReasons 逐输出规则的本轮原因
     */
    public record GroupRun(long fed, long pulled,
            Map<ResourceKey, Long> fedByKey, Map<ResourceKey, Long> pulledByKey,
            Map<RuleKey, Long> fedByRule, Map<RuleKey, Long> pulledByRule,
            IoResult.Reason lastReason, boolean inputFull,
            IoResult.Reason inputReason, IoResult.Reason outputReason,
            Map<RuleKey, IoResult.Reason> inputReasons,
            Map<RuleKey, IoResult.Reason> outputReasons) {

        public GroupRun {
            // 逐规则原因会被快照读取，先冻结成只读，避免调用方后续改动影响已发出的视图。
            inputReasons = Map.copyOf(inputReasons);
            outputReasons = Map.copyOf(outputReasons);
        }

        /** 一共搬了多少。 */
        public long moved() {
            return this.fed + this.pulled;
        }

        /** 这一轮是否搬了东西。 */
        public boolean movedAnything() {
            return this.fed + this.pulled > 0L;
        }
    }

    /** 解析出来的搬运通道：适配器 + 实际用的面（{@code null} = 无向）。 */
    private record Resolved(ResourceAdapter adapter, @Nullable Direction face) {
    }

    private static IoResult.Reason worse(@Nullable IoResult.Reason current,
            IoResult.Reason next) {
        if (current == null) {
            return next;
        }
        return severity(next) > severity(current) ? next : current;
    }

    private static int severity(IoResult.Reason reason) {
        return switch (reason) {
            case REJECTED -> 4;
            case NO_HANDLER -> 3;
            case NO_SPACE -> 2;
            case NO_ITEM -> 1;
            case OK -> 0;
        };
    }

    private static IoResult.Reason combine(@Nullable IoResult.Reason input,
            @Nullable IoResult.Reason output) {
        if (output != null && output != IoResult.Reason.OK) {
            return output;
        }
        if (input != null && input != IoResult.Reason.OK) {
            return input;
        }
        return IoResult.Reason.OK;
    }

    private GroupRunner() {
        // 纯静态工具类
    }

    /**
     * 跑一台机器的一轮。
     *
     * @param storage    仓库
     * @param level      机器所在维度
     * @param pos        机器坐标
     * @param group      IO 组
     * @param caps       能力句柄缓存；{@code null} 时直接查世界
     * @param routes     面路由表；{@code null} 时每次扫面
     * @param generation 观测层的面表代数
     * @return 本轮结果
     */
    public static GroupRun run(Storage storage, ServerLevel level, BlockPos pos, IoGroup group,
            @Nullable LevelCapabilityCache caps, @Nullable RouteTable routes, long generation) {
        long fed = 0L;
        long pulled = 0L;
        Map<ResourceKey, Long> fedByKey = new LinkedHashMap<>();
        Map<ResourceKey, Long> pulledByKey = new LinkedHashMap<>();
        Map<RuleKey, Long> fedByRule = new LinkedHashMap<>();
        Map<RuleKey, Long> pulledByRule = new LinkedHashMap<>();
        Map<RuleKey, IoResult.Reason> inputReasons = new LinkedHashMap<>();
        Map<RuleKey, IoResult.Reason> outputReasons = new LinkedHashMap<>();
        IoResult.Reason inputReason = null;
        IoResult.Reason outputReason = null;
        boolean anyInputAccepted = false;
        boolean anyInputRule = false;

        for (ResourceSpec spec : group.inputs()) {
            anyInputRule = true;
            RuleKey ruleKey = new RuleKey(true, spec.key(), spec.face());
            Resolved resolved = resolve(level, pos, spec, true, caps, routes, generation);
            if (resolved == null) {
                IdleFactory.LOGGER.debug("IdleFactory IO: no adapter, machine={} spec={}",
                        pos.toShortString(), spec.describe());
                inputReason = worse(inputReason, IoResult.Reason.NO_HANDLER);
                inputReasons.put(ruleKey, IoResult.Reason.NO_HANDLER);
                continue;
            }
            IoResult result = IoTransfer.feed(storage, resolved.adapter());
            inputReasons.put(ruleKey, result.reason());
            fed += result.moved();
            if (result.moved() > 0L) {
                fedByKey.merge(spec.key(), result.moved(), Long::sum);
                fedByRule.merge(new RuleKey(true, spec.key(), spec.face()),
                        result.moved(), Long::sum);
                remember(routes, spec, true, resolved, generation);
            } else if (result.reason() == IoResult.Reason.REJECTED) {
                // 有容量却不收：这个面不对，下次重扫
                clear(routes, spec, true);
            }
            if (result.reason() == IoResult.Reason.OK
                    || result.reason() == IoResult.Reason.NO_ITEM) {
                // NO_ITEM 只表示仓库没料，机器侧已经点头接收
                anyInputAccepted = true;
            } else {
                inputReason = worse(inputReason, result.reason());
            }
            if (!result.movedAnything()) {
                IdleFactory.LOGGER.debug("IdleFactory IO feed blocked: machine={} spec={} reason={} capacity={}",
                        pos.toShortString(), spec.describe(), result.reason(),
                        resolved.adapter().capacity());
            }
        }

        for (ResourceSpec spec : group.outputs()) {
            RuleKey ruleKey = new RuleKey(false, spec.key(), spec.face());
            Resolved resolved = resolve(level, pos, spec, false, caps, routes, generation);
            if (resolved == null) {
                IdleFactory.LOGGER.debug("IdleFactory IO: no adapter, machine={} spec={}",
                        pos.toShortString(), spec.describe());
                outputReason = worse(outputReason, IoResult.Reason.NO_HANDLER);
                outputReasons.put(ruleKey, IoResult.Reason.NO_HANDLER);
                continue;
            }
            IoResult result = IoTransfer.pull(storage, resolved.adapter());
            outputReasons.put(ruleKey, result.reason());
            pulled += result.moved();
            if (result.moved() > 0L) {
                pulledByKey.merge(spec.key(), result.moved(), Long::sum);
                pulledByRule.merge(new RuleKey(false, spec.key(), spec.face()),
                        result.moved(), Long::sum);
                remember(routes, spec, false, resolved, generation);
            } else if (result.reason() == IoResult.Reason.REJECTED) {
                clear(routes, spec, false);
            }
            if (result.reason() != IoResult.Reason.OK) {
                outputReason = worse(outputReason, result.reason());
            }
            if (!result.movedAnything()) {
                IdleFactory.LOGGER.debug("IdleFactory IO pull blocked: machine={} spec={} reason={}",
                        pos.toShortString(), spec.describe(), result.reason());
            }
        }

        boolean inputFull = anyInputRule && !anyInputAccepted;
        IoResult.Reason lastReason = combine(inputReason, outputReason);
        return new GroupRun(fed, pulled, fedByKey, pulledByKey,
                fedByRule, pulledByRule, lastReason, inputFull,
                inputReason == null ? IoResult.Reason.OK : inputReason,
                outputReason == null ? IoResult.Reason.OK : outputReason,
                inputReasons, outputReasons);
    }

    /**
     * 解析一条规则该用哪个面、哪个 handler。
     *
     * <p>配置的面优先（不查路由）；其次查路由；最后扫面。</p>
     */
    @Nullable
    private static Resolved resolve(ServerLevel level, BlockPos pos, ResourceSpec spec, boolean input,
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
                ItemStack sample = itemSample(spec.key());
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

    private static void remember(@Nullable RouteTable routes, ResourceSpec spec, boolean input,
            Resolved resolved, long generation) {
        if (routes == null || spec.face() != null || spec.key().kind() == ResourceKind.ITEM) {
            return;
        }
        routes.remember(spec, input, new RouteTable.Route(resolved.face()), generation);
    }

    private static void clear(@Nullable RouteTable routes, ResourceSpec spec, boolean input) {
        if (routes != null && spec.face() == null) {
            routes.clear(spec, input);
        }
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

    private static ItemStack itemSample(ResourceKey key) {
        Item item = BuiltInRegistries.ITEM.get(key.id());
        if (item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(item);
    }
}
