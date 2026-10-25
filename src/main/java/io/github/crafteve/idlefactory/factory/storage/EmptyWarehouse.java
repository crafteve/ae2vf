package io.github.crafteve.idlefactory.factory.storage;

import net.minecraft.world.item.ItemStack;

/**
 * 空仓库：什么都没装，也什么都收不下。
 *
 * <p>它是 {@link WarehouseLink} 在「没绑地址」或「仓库暂时连不上」时的降级实现。
 * 工厂拿到它不会以为出了错——它只会看到「没料」和「没地方放」，然后按正常业务逻辑罢工。</p>
 */
public final class EmptyWarehouse implements Warehouse {
    /** 无状态，单例即可。 */
    public static final EmptyWarehouse INSTANCE = new EmptyWarehouse();

    private EmptyWarehouse() {
        // 单例
    }

    @Override
    public long insert(ItemStack stack, long amount, boolean simulate) {
        return 0;
    }

    @Override
    public long extract(ItemStack stack, long amount, boolean simulate) {
        return 0;
    }

    @Override
    public boolean isEmpty() {
        return true;
    }
}
