package io.github.crafteve.ae2vf.menu.wand;

import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.menu.locator.ItemMenuHostLocator;

import io.github.crafteve.ae2vf.item.MachineBindingWandItem;

import net.minecraft.world.entity.player.Player;

/**
 * 草稿面板的菜单宿主。
 *
 * <p>只提供物品定位器：AE2 用它把面板和主手那把机器绑定杖绑起来，关闭面板或重新打开时
 * 定位仍然是同一件物品。宿主不缓存草稿、不判业务。</p>
 */
public final class WandDraftHost extends ItemMenuHost<MachineBindingWandItem> {
    /**
     * @param item    机器绑定杖
     * @param player  打开面板的玩家
     * @param locator 物品定位器
     */
    public WandDraftHost(MachineBindingWandItem item, Player player,
            ItemMenuHostLocator locator) {
        super(item, player, locator);
    }
}
