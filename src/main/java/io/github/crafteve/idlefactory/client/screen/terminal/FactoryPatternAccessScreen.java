package io.github.crafteve.idlefactory.client.screen.terminal;

import appeng.client.gui.me.patternaccess.PatternAccessTermScreen;
import appeng.client.gui.style.ScreenStyle;

import io.github.crafteve.idlefactory.menu.FactoryPatternAccessMenu;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 工厂样板管理终端界面：直接复用 AE2 的 {@link PatternAccessTermScreen}。
 *
 * <p>列表、槽位、滚动条、拖拽、tooltip 全部是 AE2 原版实现，一行不改；机器来源由
 * {@link FactoryPatternAccessMenu} 决定的网格提供（工厂虚拟节点），所以每台机器会按
 * 「容器标题 + 9 列样板槽」的样子列出来。样式用 AE2 自己的样板管理终端样式文档。</p>
 */
public final class FactoryPatternAccessScreen
        extends PatternAccessTermScreen<FactoryPatternAccessMenu> {
    /**
     * @param menu      菜单
     * @param inventory 玩家物品栏
     * @param title     标题
     * @param style     AE2 样板管理终端样式
     */
    public FactoryPatternAccessScreen(FactoryPatternAccessMenu menu, Inventory inventory,
            Component title, ScreenStyle style) {
        super(menu, inventory, title, style);
    }
}
