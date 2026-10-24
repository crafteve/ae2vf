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
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 工厂工作台菜单。
 *
 * <p>M1：服务端在打开时读玩家附件里的奇点、解析出 {@link IGrid} 并持有；库存通过
 * {@link StorageSnapshotS2C} 发给客户端。菜单本身仍然没有槽位，客户端只读。</p>
 */
public class FactoryMenu extends AbstractContainerMenu {
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

        Player player = playerInventory.player;

        // 客户端拿不到服务端附件，也不该自己解析网络；绑定只在服务端做。
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

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // 空容器没有可搬运的槽位
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        // 不绑定方块，只要界面开着就有效
        return true;
    }
}
