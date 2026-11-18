package io.github.crafteve.idlefactory.menu;

import appeng.api.config.SortDir;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.api.util.IConfigManager;
import appeng.menu.locator.ItemMenuHostLocator;

import io.github.crafteve.idlefactory.command.CommandController;
import io.github.crafteve.idlefactory.item.FactoryNodeTerminalItem;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * 节点管理终端的菜单宿主。
 *
 * <p>只提供 36 个玩家背包槽与会话 id，不接 ME 库存、不持有工厂数据。节点表由服务端按
 * 请求现算，宿主不缓存。</p>
 *
 * <p>排序偏好与被动 IO 终端同一套机制：挂在物品的 {@code ae2:exported_settings} 组件上，
 * 跟着这一份终端走，两个终端各自记住自己的选择。</p>
 *
 * <p>服务端打开终端时顺手保证玩家有工厂，理由同被动 IO 终端宿主：指令删除后这是唯一的
 * 「进入工厂」入口。</p>
 */
public final class FactoryNodeHost extends ItemMenuHost<FactoryNodeTerminalItem> {
    /** 终端自己的物品设置：排序档位与排序方向。 */
    private final IConfigManager configManager;

    /**
     * @param item    终端物品
     * @param player  打开终端的玩家
     * @param locator 物品定位器
     */
    public FactoryNodeHost(FactoryNodeTerminalItem item, Player player,
            ItemMenuHostLocator locator) {
        super(item, player, locator);
        if (player instanceof ServerPlayer serverPlayer) {
            CommandController.ensureFactory(serverPlayer);
        }
        this.configManager = IConfigManager.builder(this::getItemStack)
                .registerSetting(FactoryTerminalSetting.SORT_MODE, FactorySortMode.NAME)
                .registerSetting(FactoryTerminalSetting.SORT_DIRECTION, SortDir.ASCENDING)
                .build();
    }

    /**
     * 当前排序档位；没有存过时退回默认的名称排序。
     *
     * @return 排序档位
     */
    public FactorySortMode sortMode() {
        return this.configManager.getSetting(FactoryTerminalSetting.SORT_MODE);
    }

    /**
     * 当前排序方向；没有存过时退回升序。
     *
     * @return 排序方向
     */
    public SortDir sortDirection() {
        return this.configManager.getSetting(FactoryTerminalSetting.SORT_DIRECTION);
    }

    /**
     * 一次写回排序档位与方向，避免两次 put 之间出现半新半旧的组合。
     *
     * @param mode      排序档位
     * @param direction 排序方向
     */
    public void setSort(FactorySortMode mode, SortDir direction) {
        this.configManager.putSetting(FactoryTerminalSetting.SORT_MODE, mode);
        this.configManager.putSetting(FactoryTerminalSetting.SORT_DIRECTION, direction);
    }
}
