package io.github.crafteve.idlefactory.menu;

import io.github.crafteve.idlefactory.factory.storage.FactoryStorageActions;
import io.github.crafteve.idlefactory.registry.ModMenus;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import appeng.api.storage.MEStorage;

/**
 * 工厂工作台菜单。
 *
 * <p>P0 阶段它只做一件事：**持有 36 个玩家槽位**，让背包能拖拽、能拿放。所有工厂数据都走我们
 * 自己的包，不经过菜单的槽位同步——菜单只负责会话存在与槽位。</p>
 *
 * <p>槽位坐标不在这里定：客户端 {@code FactoryScreen.init()} 按当前布局摆。服务端不需要坐标，
 * 摆了也只影响客户端渲染。</p>
 *
 * <p>为什么不再继承（或持有）AE2 的 {@code MEStorageMenu}：仓库后端可能是箱子组，没有 ME 网络；
 * 走我们自己的 {@code MEStorage} 抽象，AE2 和容器是同一条路。</p>
 */
public class FactoryMenu extends AbstractContainerMenu {
    /** 玩家背包槽位数（3×9 主背包 + 1×9 快捷栏）。 */
    public static final int PLAYER_SLOTS = 36;

    /**
     * 客户端与服务端共用的构造函数，由 {@link ModMenus#FACTORY} 的 {@code MenuType} 调用。
     *
     * @param containerId     本次会话的容器 id
     * @param playerInventory 打开界面的玩家物品栏
     */
    public FactoryMenu(int containerId, Inventory playerInventory) {
        super(ModMenus.FACTORY.get(), containerId);

        // 索引与 Inventory 一一对应：0–8 快捷栏，9–35 主背包
        for (int i = 0; i < PLAYER_SLOTS; i++) {
            this.addSlot(new Slot(playerInventory, i, 0, 0));
        }
    }

    /**
     * Shift 点击：先尝试把背包槽放进在线仓库，仓库拒绝时再走快捷栏 ↔ 主背包。
     *
     * <p>仓库里的物品不是真实槽位，所以仓库侧点击走 {@code FactoryStorageActionC2S}；
     * 这里只负责「背包 → 仓库」。</p>
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        /*
         * 客户端不做 Shift 预测。
         *
         * 仓库在服务端，客户端 quickMoveStack 无法知道“会不会进仓库”；如果本地先按原版
         * 快捷栏 ↔ 主背包搬一次，服务端再把它送进仓库，快捷键栏就会闪一下。这里让客户端
         * 保持原样，等服务端结果同步即可。
         */
        if (!(player instanceof ServerPlayer)) {
            return ItemStack.EMPTY;
        }
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }

        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();

        if (player instanceof ServerPlayer serverPlayer
                && FactoryStorageActions.quickMoveFromPlayer(serverPlayer, slot)) {
            return original;
        }

        boolean moved = index < 9
                ? this.moveItemStackTo(stack, 9, PLAYER_SLOTS, false)
                : this.moveItemStackTo(stack, 0, 9, false);
        if (!moved) {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        slot.onTake(player, stack);
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        // 不绑定方块，界面开着就有效
        return true;
    }
}
