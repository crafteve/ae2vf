package io.github.crafteve.idlefactory.menu;

import appeng.menu.AEBaseMenu;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

/**
 * 节点管理终端的菜单：只负责 36 个玩家背包槽。
 *
 * <p>节点与成员不是真实槽位，数据走节点表快照包</p>
 */
public final class FactoryNodeMenu extends AEBaseMenu {
    /** 终端宿主。 */
    private final FactoryNodeHost host;

    /**
     * @param menuType    注册出来的菜单类型
     * @param containerId 本次会话的容器 id
     * @param inventory   玩家物品栏
     * @param host        节点终端宿主
     */
    public FactoryNodeMenu(MenuType<?> menuType, int containerId, Inventory inventory,
            FactoryNodeHost host) {
        super(menuType, containerId, inventory, host);
        this.host = host;
        this.createPlayerInventorySlots(inventory);
    }

    /**
     * @return 节点终端宿主
     */
    public FactoryNodeHost host() {
        return this.host;
    }
}
