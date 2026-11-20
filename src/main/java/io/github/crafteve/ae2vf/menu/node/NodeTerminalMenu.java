package io.github.crafteve.ae2vf.menu.node;

import io.github.crafteve.ae2vf.menu.TerminalSortMode;

import io.github.crafteve.ae2vf.menu.TerminalSetting;

import appeng.menu.AEBaseMenu;

import io.github.crafteve.ae2vf.network.ViewPort;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;

/**
 * 节点管理终端的菜单：只负责 36 个玩家背包槽。
 *
 * <p>节点与成员不是真实槽位，数据走节点表快照包</p>
 */
public final class NodeTerminalMenu extends AEBaseMenu {
    /** 终端宿主。 */
    private final NodeTerminalHost host;

    /**
     * @param menuType    注册出来的菜单类型
     * @param containerId 本次会话的容器 id
     * @param inventory   玩家物品栏
     * @param host        节点终端宿主
     */
    public NodeTerminalMenu(MenuType<?> menuType, int containerId, Inventory inventory,
            NodeTerminalHost host) {
        super(menuType, containerId, inventory, host);
        this.host = host;
        this.createPlayerInventorySlots(inventory);
    }

    /**
     * @return 节点终端宿主
     */
    public NodeTerminalHost host() {
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
