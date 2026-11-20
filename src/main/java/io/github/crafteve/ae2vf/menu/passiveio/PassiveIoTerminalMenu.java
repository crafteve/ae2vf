package io.github.crafteve.ae2vf.menu.passiveio;

import io.github.crafteve.ae2vf.menu.PassiveIoViewMode;

import io.github.crafteve.ae2vf.menu.TerminalSortMode;

import io.github.crafteve.ae2vf.menu.TerminalSetting;

import appeng.menu.AEBaseMenu;

import io.github.crafteve.ae2vf.network.ViewPort;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;

/**
 * 被动 IO 管理终端的菜单：只负责 36 个玩家背包槽。
 *
 * <p>不继承 AE2 的库存菜单，避免把 ME 库存同步、repo 与搜索控件一起带进来。机器与规则
 * 不是真实槽位，后续版本自己发快照包。</p>
 */
public final class PassiveIoTerminalMenu extends AEBaseMenu {
    /** 终端宿主；视图设置从这里读写。 */
    private final PassiveIoTerminalHost host;

    /**
     * @param menuType    注册出来的菜单类型
     * @param containerId 本次会话的容器 id
     * @param inventory   玩家物品栏
     * @param host        被动 IO 终端宿主
     */
    public PassiveIoTerminalMenu(MenuType<?> menuType, int containerId, Inventory inventory,
            PassiveIoTerminalHost host) {
        super(menuType, containerId, inventory, host);
        this.host = host;
        this.createPlayerInventorySlots(inventory);
    }

    /**
     * @return 被动 IO 终端宿主
     */
    public PassiveIoTerminalHost host() {
        return this.host;
    }

    /**
     * 关闭终端时注销视图会话。
     *
     * <p>最后一个会话关闭后服务端丢弃差量基线，重开走全量（草稿 23 的"节奏一"）。</p>
     *
     * @param player 关闭终端的玩家
     */
    @Override
    public void removed(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            ViewPort.close(serverPlayer);
        }
        super.removed(player);
    }
}
