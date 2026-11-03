package io.github.crafteve.idlefactory.menu;

import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.menu.locator.ItemMenuHostLocator;

import io.github.crafteve.idlefactory.item.FactoryPassiveIoTerminalItem;

import net.minecraft.world.entity.player.Player;

/**
 * 被动 IO 管理终端的菜单宿主。
 *
 * <p>这一版只提供 36 个玩家背包槽与会话 id，不接 ME 库存、也不持有样板逻辑；机器与规则
 * 数据等中间内容区实现后再走自己的快照包。</p>
 */
public final class FactoryPassiveIoHost extends ItemMenuHost<FactoryPassiveIoTerminalItem> {
    /**
     * @param item    终端物品
     * @param player  打开终端的玩家
     * @param locator 物品定位器
     */
    public FactoryPassiveIoHost(FactoryPassiveIoTerminalItem item, Player player,
            ItemMenuHostLocator locator) {
        super(item, player, locator);
    }
}
