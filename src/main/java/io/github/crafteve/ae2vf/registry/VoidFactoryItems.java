package io.github.crafteve.ae2vf.registry;

import io.github.crafteve.ae2vf.IdleFactory;
import io.github.crafteve.ae2vf.item.NodeTerminalItem;
import io.github.crafteve.ae2vf.item.PassiveIoTerminalItem;
import io.github.crafteve.ae2vf.item.MachineBindingWandItem;
import io.github.crafteve.ae2vf.item.MeBindingWandItem;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 物品注册表。
 *
 * <p>终端与两种绑定杖都不可堆叠、无耐久；这里只负责注册，绑定行为后续再加。</p>
 */
public final class IdleFactoryItems {
    /** 物品注册表。 */
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, IdleFactory.MODID);

    /** 被动 IO 管理终端；右键打开第三版框架界面。 */
    public static final DeferredHolder<Item, PassiveIoTerminalItem> PASSIVE_IO_TERMINAL =
            ITEMS.register("passive_io_terminal",
                    () -> new PassiveIoTerminalItem(unstackable()));

    /** 节点管理终端；只管节点、成员与节点级动作。 */
    public static final DeferredHolder<Item, NodeTerminalItem> NODE_TERMINAL =
            ITEMS.register("node_terminal",
                    () -> new NodeTerminalItem(unstackable()));

    /** 机器绑定杖。 */
    public static final DeferredHolder<Item, MachineBindingWandItem> MACHINE_BINDING_WAND =
            ITEMS.register("machine_binding_wand",
                    () -> new MachineBindingWandItem(unstackable()));

    /** ME 绑定杖。 */
    public static final DeferredHolder<Item, MeBindingWandItem> ME_BINDING_WAND =
            ITEMS.register("me_binding_wand",
                    () -> new MeBindingWandItem(unstackable()));

    /**
     * 全部物品共用的属性：不可堆叠，且不设置耐久。
     *
     * @return 新的物品属性
     */
    private static Item.Properties unstackable() {
        return new Item.Properties().stacksTo(1);
    }

    private IdleFactoryItems() {
        // 纯注册表持有类，不实例化
    }
}
