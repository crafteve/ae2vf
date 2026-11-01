package io.github.crafteve.idlefactory.menu;

import appeng.menu.me.items.CraftingTermMenu;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

/**
 * 工厂终端的合成视图：界面右半是 AE2 合成终端，左半是工厂管理面板。
 *
 * <p>只把 AE2 的菜单原样继承过来，槽位、合成、下单全部复用；工厂侧的数据不走槽位，
 * 走自己的负载。左面板的按钮与规则编辑因此不需要改动 AE2 的菜单逻辑。</p>
 */
public final class FactoryCraftingTerminalMenu extends CraftingTermMenu {
    /**
     * @param menuType    注册出来的菜单类型
     * @param containerId 本次会话的容器 id
     * @param inventory   玩家物品栏
     * @param host        工厂终端宿主
     */
    public FactoryCraftingTerminalMenu(MenuType<?> menuType, int containerId, Inventory inventory,
            FactoryTerminalHost host) {
        super(menuType, containerId, inventory, host, true);
    }
}
