package io.github.crafteve.idlefactory.item;

import net.minecraft.world.item.Item;

/**
 * 存储绑定杖：后续用于绑定仓库或容器。
 *
 * <p>不可堆叠、无耐久；具体交互留到后续实现。</p>
 */
public class StorageBindingWandItem extends Item {
    /**
     * @param properties 物品属性；由注册处统一设置不可堆叠
     */
    public StorageBindingWandItem(Properties properties) {
        super(properties);
    }
}
