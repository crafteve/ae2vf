package io.github.crafteve.idlefactory.menu;

import io.github.crafteve.idlefactory.registry.ModMenus;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * 工厂工作台菜单。
 *
 * <p>M0 是纯空容器：没有任何槽位，物品栏拖放、Shift 搬运都不参与。</p>
 */
public class FactoryMenu extends AbstractContainerMenu {
    /**
     * 客户端与服务端共用的构造函数，由 {@link ModMenus#FACTORY} 的 {@code MenuType} 调用。
     *
     * @param containerId     本次会话的容器 id
     * @param playerInventory 打开界面的玩家物品栏
     */
    public FactoryMenu(int containerId, Inventory playerInventory) {
        super(ModMenus.FACTORY.get(), containerId);
        // M0 不添加任何槽位
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // 空容器没有可搬运的槽位
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        // M0 不绑定方块，只要界面开着就有效
        return true;
    }
}
