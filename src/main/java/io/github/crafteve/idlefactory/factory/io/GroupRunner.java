package io.github.crafteve.idlefactory.factory.io;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.line.IoGroup;
import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
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
 * <p>按资源类型取对应的方块能力，取不到就跳过该规则，不报错。</p>
 */
public final class GroupRunner {
    /**
     * 一次组级搬运的结果。
     *
     * @param fed         补进机器的总数
     * @param pulled      从机器抽出的总数
     * @param fedByKey    按资源拆分的补料量
     * @param pulledByKey 按资源拆分的抽取量
     * @param lastReason  没搬动时的最后一个原因
     */
    public record GroupRun(long fed, long pulled,
            Map<ResourceKey, Long> fedByKey, Map<ResourceKey, Long> pulledByKey,
            IoResult.Reason lastReason) {
        /** 一共搬了多少。 */
        public long moved() {
            return this.fed + this.pulled;
        }

        /** 这一轮是否搬了东西。 */
        public boolean movedAnything() {
            return this.fed + this.pulled > 0L;
        }
    }

    private GroupRunner() {
        // 纯静态工具类
    }

    /**
     * 跑一台机器的一轮。
     *
     * @param storage 仓库
     * @param level   机器所在维度
     * @param pos     机器坐标
     * @param group   IO 组
     * @return 本轮结果
     */
    public static GroupRun run(Storage storage, ServerLevel level, BlockPos pos, IoGroup group) {
        long fed = 0L;
        long pulled = 0L;
        Map<ResourceKey, Long> fedByKey = new LinkedHashMap<>();
        Map<ResourceKey, Long> pulledByKey = new LinkedHashMap<>();
        IoResult.Reason lastReason = IoResult.Reason.NO_ITEM;

        for (ResourceSpec spec : group.inputs()) {
            ResourceAdapter adapter = adapter(level, pos, spec, true);
            if (adapter == null) {
                IdleFactory.LOGGER.debug("IdleFactory IO: no adapter, machine={} spec={}",
                        pos.toShortString(), spec.describe());
                continue;
            }
            IoResult result = IoTransfer.feed(storage, adapter);
            fed += result.moved();
            if (result.moved() > 0L) {
                fedByKey.merge(spec.key(), result.moved(), Long::sum);
            }
            if (result.reason() != IoResult.Reason.OK) {
                lastReason = result.reason();
            }
            if (!result.movedAnything()) {
                IdleFactory.LOGGER.debug("IdleFactory IO feed blocked: machine={} spec={} reason={} capacity={}",
                        pos.toShortString(), spec.describe(), result.reason(), adapter.capacity());
            }
        }

        for (ResourceSpec spec : group.outputs()) {
            ResourceAdapter adapter = adapter(level, pos, spec, false);
            if (adapter == null) {
                IdleFactory.LOGGER.debug("IdleFactory IO: no adapter, machine={} spec={}",
                        pos.toShortString(), spec.describe());
                continue;
            }
            IoResult result = IoTransfer.pull(storage, adapter);
            pulled += result.moved();
            if (result.moved() > 0L) {
                pulledByKey.merge(spec.key(), result.moved(), Long::sum);
            }
            if (result.reason() != IoResult.Reason.OK) {
                lastReason = result.reason();
            }
            if (!result.movedAnything()) {
                IdleFactory.LOGGER.debug("IdleFactory IO pull blocked: machine={} spec={} reason={}",
                        pos.toShortString(), spec.describe(), result.reason());
            }
        }

        return new GroupRun(fed, pulled, fedByKey, pulledByKey, lastReason);
    }

    private static ResourceAdapter adapter(ServerLevel level, BlockPos pos, ResourceSpec spec, boolean input) {
        return switch (spec.key().kind()) {
            case ITEM -> {
                ItemStack sample = itemSample(spec.key());
                IItemHandler handler = level.getCapability(
                        Capabilities.ItemHandler.BLOCK, pos, spec.face());
                yield handler == null || sample.isEmpty() ? null : new ItemAdapter(handler, sample);
            }
            case FLUID -> {
                Fluid fluid = BuiltInRegistries.FLUID.get(spec.key().id());
                IFluidHandler handler = fluidHandler(level, pos, spec.face(), fluid, input);
                yield handler == null || fluid == Fluids.EMPTY ? null : new FluidAdapter(handler, fluid);
            }
            case ENERGY -> {
                IEnergyStorage handler = energyInput(level, pos, spec.face());
                yield handler == null ? null : new EnergyAdapter(handler);
            }
            case CHEMICAL -> null;
        };
    }

    /**
     * 取机器的流体面。
     *
     * <p>规则没指定面时逐面「模拟灌/抽 1 mB」探测：Mekanism 的流体 capability 也受面配置控制。</p>
     */
    @Nullable
    private static IFluidHandler fluidHandler(ServerLevel level, BlockPos pos, @Nullable Direction face,
            Fluid fluid, boolean input) {
        if (face != null) {
            return level.getCapability(Capabilities.FluidHandler.BLOCK, pos, face);
        }
        FluidStack probe = new FluidStack(fluid, 1);
        for (Direction side : Direction.values()) {
            IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side);
            if (handler == null) {
                continue;
            }
            int result = input
                    ? handler.fill(probe, IFluidHandler.FluidAction.SIMULATE)
                    : handler.drain(probe, IFluidHandler.FluidAction.SIMULATE).getAmount();
            if (result > 0) {
                return handler;
            }
        }
        return level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
    }

    /**
     * 取机器的能量输入面。
     *
     * <p>规则没指定面时逐面「模拟接收 1 FE」探测：Mekanism 的 {@code canReceive} 恒为 true，
     * 真正的输入面限制在按面筛选后的 handler 里。</p>
     */
    @Nullable
    private static IEnergyStorage energyInput(ServerLevel level, BlockPos pos, @Nullable Direction face) {
        if (face != null) {
            return level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, face);
        }
        for (Direction side : Direction.values()) {
            IEnergyStorage handler = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side);
            if (handler != null && handler.receiveEnergy(1000, true) > 0) {
                return handler;
            }
        }
        return level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, null);
    }

    private static ItemStack itemSample(ResourceKey key) {
        Item item = BuiltInRegistries.ITEM.get(key.id());
        if (item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(item);
    }
}
