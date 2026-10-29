package io.github.crafteve.idlefactory.factory.storage;

import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
import io.github.crafteve.idlefactory.factory.resource.ResourceKind;
import io.github.crafteve.idlefactory.menu.FactoryMenu;
import io.github.crafteve.idlefactory.network.FactoryStorageSnapshots;
import io.github.crafteve.idlefactory.network.payload.FactoryStorageActionC2S.Gesture;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 服务端仓库动作。
 *
 * <p>所有数量都在这里按仓库和玩家背包的实际状态重新计算。客户端只发手势，不能指定数量，
 * 也不能指定物品栈。</p>
 */
public final class FactoryStorageActions {
    private FactoryStorageActions() {
        // 纯静态动作层
    }

    /**
     * 处理一次仓库格手势。
     *
     * @param player  玩家
     * @param menu    工厂菜单
     * @param key     被点击的资源；空气格为 {@code null}
     * @param gesture 手势
     */
    public static void handle(ServerPlayer player, FactoryMenu menu,
            @Nullable ResourceKey key, Gesture gesture) {
        Factory factory = ownedFactory(player);
        if (factory == null) {
            return;
        }
        factory.refreshStorage(FactoryRuntime.get().storageResolver());
        if (factory.config().storageAnchor() == null) {
            message(player, "还没有绑定仓库");
            return;
        }
        if (!factory.live().storageOnline()) {
            message(player, "仓库未连接");
            return;
        }
        if (key != null && key.kind() != ResourceKind.ITEM) {
            message(player, "当前仅物品支持直接存取");
            return;
        }
        Storage storage = factory.live().storage();
        boolean changed = switch (gesture) {
            case LEFT -> menu.getCarried().isEmpty()
                    ? takeToCarried(menu, storage, key, Integer.MAX_VALUE)
                    : putCarried(menu, storage, Integer.MAX_VALUE);
            case RIGHT -> menu.getCarried().isEmpty()
                    ? takeToCarried(menu, storage, key, -1)
                    : putCarried(menu, storage, 1);
            case SHIFT_LEFT -> takeToInventory(player, storage, key, 64);
            case SHIFT_RIGHT -> takeToInventory(player, storage, key, 1);
            case SCROLL_UP -> takeToInventory(player, storage, key, 1);
            case SCROLL_DOWN -> putOneFromInventory(player, storage, key);
            case PUT_FROM_INVENTORY -> putFromInventory(player, storage, key);
        };
        if (changed) {
            menu.broadcastChanges();
            player.getInventory().setChanged();
            FactoryStorageSnapshots.send(player, factory, menu.containerId);
        }
    }

    /**
     * 玩家背包 Shift 点击时，优先尝试放进仓库。
     *
     * @param player 玩家
     * @param slot   被点击的玩家背包槽
     * @return 真的放进仓库时为 {@code true}；否则调用方继续原版背包移动
     */
    public static boolean quickMoveFromPlayer(ServerPlayer player, Slot slot) {
        if (slot == null || !slot.hasItem()) {
            return false;
        }
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) {
            return false;
        }
        Factory factory = ownedFactory(player);
        if (factory == null) {
            return false;
        }
        factory.refreshStorage(FactoryRuntime.get().storageResolver());
        if (!factory.live().storageOnline()) {
            return false;
        }
        Storage storage = factory.live().storage();
        ResourceKey key = ResourceKey.item(stack);
        long accepted = storage.insert(key, stack.getCount(), true);
        if (accepted <= 0L) {
            return false;
        }
        long inserted = storage.insert(key, accepted, false);
        if (inserted <= 0L) {
            return false;
        }
        slot.remove((int) inserted);
        slot.setChanged();
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        FactoryStorageSnapshots.send(player, factory, player.containerMenu.containerId);
        return true;
    }

    private static boolean takeToCarried(FactoryMenu menu, Storage storage,
            @Nullable ResourceKey key, int amount) {
        if (key == null || !menu.getCarried().isEmpty()) {
            return false;
        }
        ItemStack template = template(key);
        if (template.isEmpty()) {
            return false;
        }
        int request = amount < 0
                ? Math.max(1, template.getMaxStackSize() / 2)
                : Math.min(amount, template.getMaxStackSize());
        long available = storage.extract(key, request, true);
        if (available <= 0L) {
            return false;
        }
        long extracted = storage.extract(key, available, false);
        if (extracted <= 0L) {
            return false;
        }
        menu.setCarried(template.copyWithCount((int) extracted));
        return true;
    }

    private static boolean putCarried(FactoryMenu menu, Storage storage, int amount) {
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) {
            return false;
        }
        ResourceKey key = ResourceKey.item(carried);
        int request = Math.min(amount, carried.getCount());
        long accepted = storage.insert(key, request, true);
        if (accepted <= 0L) {
            return false;
        }
        long inserted = storage.insert(key, accepted, false);
        if (inserted <= 0L) {
            return false;
        }
        carried.shrink((int) inserted);
        menu.setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
        return true;
    }

    private static boolean takeToInventory(ServerPlayer player, Storage storage,
            @Nullable ResourceKey key, int amount) {
        if (key == null) {
            return false;
        }
        ItemStack template = template(key);
        if (template.isEmpty()) {
            return false;
        }
        int request = Math.min(amount, template.getMaxStackSize());
        long available = storage.extract(key, request, true);
        if (available <= 0L) {
            return false;
        }
        long extracted = storage.extract(key, available, false);
        if (extracted <= 0L) {
            return false;
        }

        ItemStack stack = template.copyWithCount((int) extracted);
        player.getInventory().add(stack);
        if (!stack.isEmpty()) {
            // 背包只收下一部分时，剩余原路回仓库；仓库也拒收就掉在玩家脚下，不静默消失。
            long returned = storage.insert(key, stack.getCount(), false);
            if (returned < stack.getCount()) {
                player.drop(stack.copyWithCount(stack.getCount() - (int) returned), false);
            }
        }
        return true;
    }

    private static boolean putOneFromInventory(ServerPlayer player, Storage storage,
            @Nullable ResourceKey key) {
        if (key == null) {
            return false;
        }
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty() || !ResourceKey.item(stack).equals(key)) {
                continue;
            }
            long accepted = storage.insert(key, 1L, true);
            if (accepted <= 0L) {
                return false;
            }
            long inserted = storage.insert(key, accepted, false);
            if (inserted <= 0L) {
                return false;
            }
            stack.shrink((int) inserted);
            return true;
        }
        return false;
    }

    private static boolean putFromInventory(ServerPlayer player, Storage storage,
            @Nullable ResourceKey key) {
        if (key == null) {
            return false;
        }
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty() || !ResourceKey.item(stack).equals(key)) {
                continue;
            }
            long accepted = storage.insert(key, stack.getCount(), true);
            if (accepted <= 0L) {
                return false;
            }
            long inserted = storage.insert(key, accepted, false);
            if (inserted <= 0L) {
                return false;
            }
            stack.shrink((int) inserted);
            return true;
        }
        return false;
    }

    @Nullable
    private static Factory ownedFactory(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return null;
        }
        Optional<Factory> factory = runtime.manager().byOwner(player.getUUID());
        return factory.orElse(null);
    }

    private static ItemStack template(ResourceKey key) {
        if (key.kind() != ResourceKind.ITEM) {
            return ItemStack.EMPTY;
        }
        return key.toStack();
    }

    private static void message(ServerPlayer player, String text) {
        player.displayClientMessage(Component.literal(text), true);
    }
}
