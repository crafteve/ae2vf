package io.github.crafteve.idlefactory.menu;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.integration.ae2.Ae2Binder;
import io.github.crafteve.idlefactory.integration.ae2.Ae2StorageView;
import io.github.crafteve.idlefactory.network.payload.StorageSnapshotS2C;
import io.github.crafteve.idlefactory.player.ModAttachments;
import io.github.crafteve.idlefactory.registry.ModMenus;

import appeng.api.networking.IGrid;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 工厂工作台菜单。
 *
 * <p>M1：服务端在打开时读玩家附件里的奇点、解析出 {@link IGrid} 并持有；库存通过
 * {@link StorageSnapshotS2C} 发给客户端。</p>
 *
 * <p>菜单本身只有玩家背包的 36 个槽位（快捷栏 0–8、主背包 9–35），交给原版处理拖拽 / 拿取 / 放下；
 * 坐标由 {@code FactoryScreen} 在 {@code init()} 里按自适应面板尺寸重排。</p>
 */
public class FactoryMenu extends AbstractContainerMenu {
    /** 玩家背包槽位数（3×9 主背包 + 1×9 快捷栏）。 */
    public static final int PLAYER_SLOTS = 36;

    /** 服务端绑定的 ME 网络；未装备奇点或绑定失败时为 {@code null}。 */
    @Nullable
    private final IGrid grid;

    /**
     * 客户端与服务端共用的构造函数，由 {@link ModMenus#FACTORY} 的 {@code MenuType} 调用。
     *
     * @param containerId     本次会话的容器 id
     * @param playerInventory 打开界面的玩家物品栏
     */
    public FactoryMenu(int containerId, Inventory playerInventory) {
        super(ModMenus.FACTORY.get(), containerId);

        // 玩家背包槽位：索引与 Inventory 的槽位一一对应（0–8 快捷栏，9–35 主背包）
        for (int i = 0; i < PLAYER_SLOTS; i++) {
            this.addSlot(new Slot(playerInventory, i, 0, 0));
        }

        // 客户端拿不到服务端附件，也不该自己解析网络；绑定只在服务端做。
        Player player = playerInventory.player;
        IGrid boundGrid = null;
        ItemStack singularity = ModAttachments.get(player);
        if (!singularity.isEmpty() && player.level() instanceof ServerLevel serverLevel) {
            boundGrid = Ae2Binder.bind(singularity, serverLevel);
        }
        this.grid = boundGrid;
    }

    /** @return 绑定到的 ME 网络，可能为 {@code null} */
    @Nullable
    public IGrid getGrid() {
        return this.grid;
    }

    /**
     * 把当前库存快照发给指定玩家。由打开界面的服务端路径调用。
     *
     * @param player 目标玩家（服务端）
     */
    public void sendSnapshot(ServerPlayer player) {
        boolean equipped = ModAttachments.isEquipped(player);
        boolean bound = this.grid != null;

        List<StorageSnapshotS2C.Entry> entries = List.of();
        if (bound) {
            entries = Ae2StorageView.snapshot(this.grid, StorageSnapshotS2C.MAX_ENTRIES).stream()
                    .map(entry -> new StorageSnapshotS2C.Entry(entry.key(), entry.amount()))
                    .toList();
        }

        PacketDistributor.sendToPlayer(player, new StorageSnapshotS2C(equipped, bound, entries));
    }

    /**
     * Shift 点击：先在背包内部搬（快捷栏 ↔ 主背包）。
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }

        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();

        boolean moved;
        if (index < 9) {
            // 快捷栏 → 主背包
            moved = this.moveItemStackTo(stack, 9, PLAYER_SLOTS, false);
        } else {
            // 主背包 → 快捷栏
            moved = this.moveItemStackTo(stack, 0, 9, false);
        }
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
        // 不绑定方块，只要界面开着就有效
        return true;
    }
}
