package io.github.crafteve.idlefactory.menu;

import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.api.util.IConfigManager;
import appeng.menu.locator.ItemMenuHostLocator;

import io.github.crafteve.idlefactory.factory.FactoryService;
import io.github.crafteve.idlefactory.item.FactoryPassiveIoTerminalItem;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * 被动 IO 管理终端的菜单宿主。
 *
 * <p>只提供 36 个玩家背包槽与会话 id，不接 ME 库存、也不持有样板逻辑。视图设置按 AE2
 * 无线终端的方式挂在物品的 {@code ae2:exported_settings} 组件上：构建时导入，改设置时
 * 整张表写回。</p>
 *
 * <p>服务端打开终端时顺手保证玩家有工厂：指令删除后，这里是唯一会经过的「进入工厂」入口，
 * 原 {@code /idf gui} 与快捷键入口的自动建厂职责移到这里，否则界面会一直显示「你还没有工厂」。</p>
 */
public final class FactoryPassiveIoHost extends ItemMenuHost<FactoryPassiveIoTerminalItem> {
    /** 终端自己的物品设置；当前只有列表 / 卡片视图。 */
    private final IConfigManager configManager;

    /**
     * @param item    终端物品
     * @param player  打开终端的玩家
     * @param locator 物品定位器
     */
    public FactoryPassiveIoHost(FactoryPassiveIoTerminalItem item, Player player,
            ItemMenuHostLocator locator) {
        super(item, player, locator);
        if (player instanceof ServerPlayer serverPlayer) {
            FactoryService.ensureFactory(serverPlayer);
        }
        this.configManager = IConfigManager.builder(this::getItemStack)
                .registerSetting(FactoryPassiveIoSetting.VIEW, FactoryPassiveIoView.LIST)
                .build();
    }

    /**
     * 当前视图；没有存过或值非法时由 {@code IConfigManager} 退回默认列表。
     *
     * @return 视图
     */
    public FactoryPassiveIoView view() {
        return this.configManager.getSetting(FactoryPassiveIoSetting.VIEW);
    }

    /**
     * 写回视图；会同步写物品组件。
     *
     * @param view 新视图
     */
    public void setView(FactoryPassiveIoView view) {
        this.configManager.putSetting(FactoryPassiveIoSetting.VIEW, view);
    }
}
