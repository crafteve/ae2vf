package io.github.crafteve.idlefactory.menu;

import appeng.menu.implementations.PatternAccessTermMenu;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

/**
 * 工厂样板管理终端菜单：直接复用 AE2 的 {@link PatternAccessTermMenu}。
 *
 * <p>数据源不用改：样板管理终端从 {@link FactoryTerminalHost#getGridNode()} 所在的网格里扫
 * {@code PatternContainer}，而工厂的每台机器都挂着一个虚拟节点（{@code MachineNode}），节点
 * owner 实现了 {@code PatternContainer}，所以终端按机器列出容器、按容器管理样板——和 AE2
 * 原版行为一致。玩家背包槽位照原版创建（{@code true}），底部物品栏可用。</p>
 */
public final class FactoryPatternAccessMenu extends PatternAccessTermMenu {
    /**
     * @param type        菜单类型
     * @param containerId 容器 id
     * @param inventory   玩家物品栏
     * @param host        工厂终端宿主
     */
    public FactoryPatternAccessMenu(MenuType<?> type, int containerId, Inventory inventory,
            FactoryTerminalHost host) {
        super(type, containerId, inventory, host, true);
    }
}
