package io.github.crafteve.idlefactory.menu;

import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.menu.locator.ItemMenuHostLocator;

import io.github.crafteve.idlefactory.item.FactoryNodeTerminalItem;

import net.minecraft.world.entity.player.Player;

/**
 * 节点管理终端的菜单宿主。
 *
 * <p>只提供 36 个玩家背包槽与会话 id，不接 ME 库存、不持有工厂数据。节点表由服务端按
 * 请求现算，宿主不缓存。</p>
 */
public final class FactoryNodeHost extends ItemMenuHost<FactoryNodeTerminalItem> {
    /**
     * @param item    终端物品
     * @param player  打开终端的玩家
     * @param locator 物品定位器
     */
    public FactoryNodeHost(FactoryNodeTerminalItem item, Player player,
            ItemMenuHostLocator locator) {
        super(item, player, locator);
    }
}
