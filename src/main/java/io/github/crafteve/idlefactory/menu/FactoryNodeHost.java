package io.github.crafteve.idlefactory.menu;

import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.menu.locator.ItemMenuHostLocator;

import io.github.crafteve.idlefactory.factory.FactoryService;
import io.github.crafteve.idlefactory.item.FactoryNodeTerminalItem;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * 节点管理终端的菜单宿主。
 *
 * <p>只提供 36 个玩家背包槽与会话 id，不接 ME 库存、不持有工厂数据。节点表由服务端按
 * 请求现算，宿主不缓存。</p>
 *
 * <p>服务端打开终端时顺手保证玩家有工厂，理由同被动 IO 终端宿主：指令删除后这是唯一的
 * 「进入工厂」入口。</p>
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
        if (player instanceof ServerPlayer serverPlayer) {
            FactoryService.ensureFactory(serverPlayer);
        }
    }
}
