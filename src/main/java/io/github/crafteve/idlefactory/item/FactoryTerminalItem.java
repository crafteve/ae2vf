package io.github.crafteve.idlefactory.item;

import io.github.crafteve.idlefactory.registry.ModItems;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 工厂终端物品。
 *
 * <p>第二版终端删除后，右键不再打开任何界面；物品本身保留，是因为快捷键仍用它判断
 * 玩家是否带着终端。旧工作台的兜底入口改为 {@code /idf gui} 与快捷键，不依赖右键。</p>
 */
public class FactoryTerminalItem extends Item {
    /**
     * @param properties 物品属性；由注册处统一设置不可堆叠
     */
    public FactoryTerminalItem(Properties properties) {
        super(properties);
    }

    /**
     * 玩家身上（主背包或副手）是否带工厂终端。
     *
     * <p>快捷键发请求前先查一次，服务端收到请求后再查一次；两边用的是同一个方法，
     * 避免客户端显示能开、服务端却拒绝。</p>
     *
     * @param player 玩家
     * @return 带终端时为 {@code true}
     */
    public static boolean isCarriedBy(Player player) {
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(ModItems.FACTORY_TERMINAL.get())) {
                return true;
            }
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (stack.is(ModItems.FACTORY_TERMINAL.get())) {
                return true;
            }
        }
        return false;
    }
}
