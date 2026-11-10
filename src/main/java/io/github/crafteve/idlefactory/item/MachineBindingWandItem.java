package io.github.crafteve.idlefactory.item;

import net.minecraft.world.item.Item;

/**
 * 机器绑定杖：1:1 toggle 与草稿编辑的入口物品。
 *
 * <p>不可堆叠、无耐久。它自己不做任何交互判断：右键手势在客户端
 * {@code client.wand.WandGesture}，服务端判定在 {@code factory.WandService}。这样物品类不再
 * 引用 {@code client.*}，服务端加载时也不会碰到客户端类。</p>
 */
public class MachineBindingWandItem extends Item {
    /**
     * @param properties 物品属性；由注册处统一设置不可堆叠
     */
    public MachineBindingWandItem(Properties properties) {
        super(properties);
    }
}
