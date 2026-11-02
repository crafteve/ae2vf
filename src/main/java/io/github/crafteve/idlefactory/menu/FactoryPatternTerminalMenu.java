package io.github.crafteve.idlefactory.menu;

import appeng.menu.me.items.PatternEncodingTermMenu;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

/**
 * 工厂终端的样板视图：右半是 AE2 样板编码终端，左半是主动目标面板。
 *
 * <p>编码格与编码按钮全部复用 AE2；左面板的 9 个样板格由屏幕自绘，点击走
 * {@code FactoryPatternSlotActionC2S} 直接读写机器样板池。所以菜单里不再挂真实槽位，
 * 这里只负责把 AE2 的编码终端菜单带起来。</p>
 */
public final class FactoryPatternTerminalMenu extends PatternEncodingTermMenu {

    /**
     * @param menuType    注册出来的菜单类型
     * @param containerId 本次会话的容器 id
     * @param inventory   玩家物品栏
     * @param host        工厂终端宿主
     */
    public FactoryPatternTerminalMenu(MenuType<?> menuType, int containerId, Inventory inventory,
            FactoryTerminalHost host) {
        super(menuType, containerId, inventory, host, true);
    }
}
