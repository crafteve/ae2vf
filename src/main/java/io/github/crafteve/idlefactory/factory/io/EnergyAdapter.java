package io.github.crafteve.idlefactory.factory.io;

import appeng.api.stacks.AEKey;

import net.neoforged.neoforge.energy.IEnergyStorage;
import io.github.crafteve.idlefactory.factory.storage.AeKeySupport;

/**
 * 机器的能量接口，单位 FE。
 */
public final class EnergyAdapter implements ResourceAdapter {
    private final IEnergyStorage handler;

    /**
     * @param handler 能量处理器
     */
    public EnergyAdapter(IEnergyStorage handler) {
        this.handler = handler;
    }

    @Override
    public AEKey key() {
        return AeKeySupport.energy();
    }

    @Override
    public long capacity() {
        return Math.max(0, this.handler.getMaxEnergyStored() - this.handler.getEnergyStored());
    }

    @Override
    public long insert(long amount, boolean simulate) {
        if (amount <= 0L) {
            return 0L;
        }
        return this.handler.receiveEnergy((int) Math.min(amount, Integer.MAX_VALUE), simulate);
    }

    @Override
    public long extract(long amount, boolean simulate) {
        if (amount <= 0L) {
            return 0L;
        }
        return this.handler.extractEnergy((int) Math.min(amount, Integer.MAX_VALUE), simulate);
    }
}
