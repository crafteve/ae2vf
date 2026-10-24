package io.github.crafteve.idlefactory.registry;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.item.TerminalItem;

import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 物品注册表。
 *
 * <p>M0 只有终端一个物品：右键打开空工作台。</p>
 */
public final class ModItems {
    /** 物品注册表，统一在 mod 事件总线上注册。 */
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(IdleFactory.MODID);

    /** 工厂终端：右键打开 {@code FactoryMenu}。 */
    public static final DeferredItem<TerminalItem> TERMINAL =
            ITEMS.registerItem("terminal", TerminalItem::new);

    private ModItems() {
        // 纯注册表持有类，不实例化
    }
}
