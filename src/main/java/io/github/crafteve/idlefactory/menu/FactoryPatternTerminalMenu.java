package io.github.crafteve.idlefactory.menu;

import org.jetbrains.annotations.Nullable;

import appeng.menu.me.items.PatternEncodingTermMenu;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

/**
 * 工厂终端的样板视图：界面右半是 AE2 样板编码终端，左半是目标与主动 / 被动管理面板。
 *
 * <p>编码格与编码按钮全部复用 AE2；目标列表与样板槽视图走工厂自己的负载，避免把
 * 机器库塞进 AE2 的 ConfigInventory。选中状态只活在当前菜单里，切视图会重置。</p>
 */
public final class FactoryPatternTerminalMenu extends PatternEncodingTermMenu {
    /** 服务端的目标表；客户端为 {@code null}。 */
    @Nullable
    private final FactoryTerminalTargets targets;

    /**
     * @param menuType    注册出来的菜单类型
     * @param containerId 本次会话的容器 id
     * @param inventory   玩家物品栏
     * @param host        工厂终端宿主
     */
    public FactoryPatternTerminalMenu(MenuType<?> menuType, int containerId, Inventory inventory,
            FactoryTerminalHost host) {
        super(menuType, containerId, inventory, host, true);
        this.targets = host.factory() == null || !(inventory.player instanceof ServerPlayer player)
                ? null : new FactoryTerminalTargets(host.factory(), player);
    }

    /** 服务端的目标表；客户端为 {@code null}。 */
    @Nullable
    public FactoryTerminalTargets targets() {
        return this.targets;
    }

    /**
     * 选中一个目标；由 C2S 负载调用，服务端重新按键解析。
     *
     * @param player 玩家
     * @param key    目标键
     * @return 是否命中一个存在的目标
     */
    public boolean selectTarget(ServerPlayer player, String key) {
        if (this.targets == null) {
            return false;
        }
        this.targets.select(key);
        return this.targets.selectedKey().equals(key);
    }
}
