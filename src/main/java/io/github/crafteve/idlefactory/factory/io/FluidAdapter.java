package io.github.crafteve.idlefactory.factory.io;

import appeng.api.stacks.AEKey;

import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import io.github.crafteve.idlefactory.factory.storage.AeKeySupport;

/**
 * 机器的流体接口，单位 mB。
 */
public final class FluidAdapter implements ResourceAdapter {
    private final IFluidHandler handler;
    private final Fluid fluid;

    /**
     * @param handler 流体处理器
     * @param fluid   流体
     */
    public FluidAdapter(IFluidHandler handler, Fluid fluid) {
        this.handler = handler;
        this.fluid = fluid;
    }

    @Override
    public AEKey key() {
        return AeKeySupport.fluid(this.fluid);
    }

    @Override
    public long capacity() {
        long total = 0L;
        for (int tank = 0; tank < this.handler.getTanks(); tank++) {
            FluidStack inTank = this.handler.getFluidInTank(tank);
            if (!inTank.isEmpty() && !inTank.is(this.fluid)) {
                continue;
            }
            total += Math.max(0, this.handler.getTankCapacity(tank) - inTank.getAmount());
        }
        return total;
    }

    @Override
    public long insert(long amount, boolean simulate) {
        if (amount <= 0L) {
            return 0L;
        }
        int filled = this.handler.fill(
                new FluidStack(this.fluid, (int) Math.min(amount, Integer.MAX_VALUE)), action(simulate));
        return filled;
    }

    @Override
    public long extract(long amount, boolean simulate) {
        if (amount <= 0L) {
            return 0L;
        }
        FluidStack drained = this.handler.drain(
                new FluidStack(this.fluid, (int) Math.min(amount, Integer.MAX_VALUE)), action(simulate));
        return drained.getAmount();
    }

    private static IFluidHandler.FluidAction action(boolean simulate) {
        return simulate ? IFluidHandler.FluidAction.SIMULATE : IFluidHandler.FluidAction.EXECUTE;
    }
}
