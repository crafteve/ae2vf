package io.github.crafteve.idlefactory.item;

import net.minecraft.world.item.Item;

/**
 * 机器绑定杖：后续用于把机器绑定到产线或工厂。
 *
 * <p>不可堆叠、无耐久；具体交互留到后续实现。</p>
 */
public class MachineBindingWandItem extends Item {
    /**
     * @param properties 物品属性；由注册处统一设置不可堆叠
     */
    public MachineBindingWandItem(Properties properties) {
        super(properties);
    }
}
