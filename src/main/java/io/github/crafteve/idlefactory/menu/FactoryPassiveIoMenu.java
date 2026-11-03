package io.github.crafteve.idlefactory.menu;

import appeng.menu.AEBaseMenu;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

/**
 * 被动 IO 管理终端的菜单：只负责 36 个玩家背包槽。
 *
 * <p>不继承 AE2 的库存菜单，避免把 ME 库存同步、repo 与搜索控件一起带进来。机器与规则
 * 不是真实槽位，后续版本自己发快照包。</p>
 */
public final class FactoryPassiveIoMenu extends AEBaseMenu {
    /** 终端宿主；视图设置从这里读写。 */
    private final FactoryPassiveIoHost host;

    /**
     * @param menuType    注册出来的菜单类型
     * @param containerId 本次会话的容器 id
     * @param inventory   玩家物品栏
     * @param host        被动 IO 终端宿主
     */
    public FactoryPassiveIoMenu(MenuType<?> menuType, int containerId, Inventory inventory,
            FactoryPassiveIoHost host) {
        super(menuType, containerId, inventory, host);
        this.host = host;
        this.createPlayerInventorySlots(inventory);
    }

    /**
     * @return 被动 IO 终端宿主
     */
    public FactoryPassiveIoHost host() {
        return this.host;
    }
}
