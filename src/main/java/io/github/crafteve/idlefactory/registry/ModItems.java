package io.github.crafteve.idlefactory.registry;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.item.FactoryTerminalItem;
import io.github.crafteve.idlefactory.item.MachineBindingWandItem;
import io.github.crafteve.idlefactory.item.StorageBindingWandItem;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 物品注册表。
 *
 * <p>终端与两种绑定杖都不可堆叠、无耐久；这里只负责注册，绑定行为后续再加。</p>
 */
public final class ModItems {
    /** 物品注册表。 */
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, IdleFactory.MODID);

    /** 工厂终端。 */
    public static final DeferredHolder<Item, FactoryTerminalItem> FACTORY_TERMINAL =
            ITEMS.register("factory_terminal",
                    () -> new FactoryTerminalItem(unstackable()));

    /** 被动 IO 管理终端；右键界面在 P1 接入，当前只注册物品。 */
    public static final DeferredHolder<Item, Item> PASSIVE_IO_TERMINAL =
            ITEMS.register("passive_io_terminal", () -> new Item(unstackable()));

    /** 机器绑定杖。 */
    public static final DeferredHolder<Item, MachineBindingWandItem> MACHINE_BINDING_WAND =
            ITEMS.register("machine_binding_wand",
                    () -> new MachineBindingWandItem(unstackable()));

    /** 存储绑定杖。 */
    public static final DeferredHolder<Item, StorageBindingWandItem> STORAGE_BINDING_WAND =
            ITEMS.register("storage_binding_wand",
                    () -> new StorageBindingWandItem(unstackable()));

    /**
     * 四个物品共用的属性：不可堆叠，且不设置耐久。
     *
     * @return 新的物品属性
     */
    private static Item.Properties unstackable() {
        return new Item.Properties().stacksTo(1);
    }

    private ModItems() {
        // 纯注册表持有类，不实例化
    }
}
