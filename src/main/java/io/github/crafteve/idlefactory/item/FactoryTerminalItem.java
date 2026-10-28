package io.github.crafteve.idlefactory.item;

import net.minecraft.world.item.Item;

/**
 * 工厂终端：后续用于打开工厂界面或远程接入工厂。
 *
 * <p>不可堆叠、无耐久；具体交互留到后续实现。</p>
 */
public class FactoryTerminalItem extends Item {
    /**
     * @param properties 物品属性；由注册处统一设置不可堆叠
     */
    public FactoryTerminalItem(Properties properties) {
        super(properties);
    }
}
