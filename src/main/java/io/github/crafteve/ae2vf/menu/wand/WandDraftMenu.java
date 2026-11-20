package io.github.crafteve.ae2vf.menu.wand;

import appeng.menu.AEBaseMenu;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

/**
 * 草稿确认面板的菜单：登录壳，不建任何玩家槽位。
 *
 * <p>面板内容全是客户端自绘的草稿行，服务端不持有草稿；这里只负责让原版打开界面、并在
 * 关闭时按容器 id 结束会话。玩家背包的 36 个槽位故意不创建：面板不是容器界面，鼠标移出
 * 也没有可交互槽位。</p>
 */
public final class WandDraftMenu extends AEBaseMenu {
    /** 面板宿主；只用于取物品定位器，不持有草稿。 */
    private final WandDraftHost host;

    /**
     * @param menuType    注册出来的菜单类型
     * @param containerId 本次会话的容器 id
     * @param inventory   玩家物品栏
     * @param host        面板宿主
     */
    public WandDraftMenu(MenuType<?> menuType, int containerId, Inventory inventory,
            WandDraftHost host) {
        super(menuType, containerId, inventory, host);
        this.host = host;
    }

    /**
     * @return 面板宿主
     */
    public WandDraftHost host() {
        return this.host;
    }
}
