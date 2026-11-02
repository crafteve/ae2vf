package io.github.crafteve.idlefactory.menu;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import appeng.menu.AEBaseMenu;
import appeng.menu.MenuOpener;
import appeng.menu.locator.ItemMenuHostLocator;
import appeng.menu.locator.MenuHostLocator;
import appeng.menu.locator.MenuLocators;

import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.factory.FactoryService;
import io.github.crafteve.idlefactory.factory.storage.MeAnchor;
import io.github.crafteve.idlefactory.factory.storage.StorageMode;
import io.github.crafteve.idlefactory.item.FactoryTerminalItem;
import io.github.crafteve.idlefactory.registry.ModMenus;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * 工厂终端的入口与视图选择：决定开旧界面还是新终端，以及新终端开合成视图还是样板视图。
 *
 * <p>视图只存在服务端的临时表里：重启丢失，但终端本身没有持久状态，丢的是「下次打开
 * 默认回到合成视图」这一点，可以接受。用临时表而不是物品组件，是为了少注册一个数据组件，
 * 也避免同一玩家多份终端时状态不一致。</p>
 */
public final class FactoryTerminalMenus {
    /** 终端右半边的视图。 */
    public enum View {
        /** 合成终端：库存列表 + 3×3 合成。 */
        CRAFTING,
        /** 样板编码终端：样板编码与上传目标。 */
        PATTERN
    }

    /** 玩家 → 上次使用的终端视图；只服务端写读。 */
    private static final Map<UUID, View> VIEWS = new HashMap<>();

    private FactoryTerminalMenus() {
        // 纯静态入口类
    }

    /**
     * 打开玩家的工厂终端。
     *
     * <p>只有「ME 模式且已绑定锚点」才开新终端；其余情况（容器模式、还没绑 ME 网络、
     * 玩家身上没有终端物品）回退到旧工作台，保证前期通路不会断。</p>
     *
     * @param player 玩家
     * @param hand   手持终端的手；从指令或快捷键打开时传 {@code null}
     * @return 是否已请求打开界面
     */
    public static boolean open(ServerPlayer player, @Nullable InteractionHand hand) {
        Factory factory = ownedFactory(player);
        if (factory == null || !isMeMode(factory)) {
            openLegacy(player);
            return true;
        }
        ItemMenuHostLocator locator = findLocator(player, hand);
        if (locator == null) {
            openLegacy(player);
            return true;
        }
        if (!MenuOpener.open(menuType(view(player)), player, locator)) {
            // 宿主对不上（例如手持物在发包前被换掉）时退回旧界面，至少不是点了没反应。
            openLegacy(player);
        }
        return true;
    }

    /**
     * 打开默认工厂界面（{@code /idf gui}、快捷键与请求包都走这里）。
     *
     * <p>先保证玩家有工厂，再按当前模式选界面；自动建厂失败不挡打开，界面仍显示未创建。</p>
     *
     * @param player 玩家
     * @return 是否已请求打开界面
     */
    public static boolean openDefault(ServerPlayer player) {
        FactoryService.ensureFactory(player);
        return open(player, null);
    }

    /**
     * 手持终端右键打开界面。
     *
     * <p>和 {@link #openDefault(ServerPlayer)} 的区别只有定位器：右键知道是哪只手的物品，
     * 菜单里可以直接锁定这个槽位；指令与快捷键只能扫背包。</p>
     *
     * @param player 玩家
     * @param hand   使用的手
     * @return 是否已请求打开界面
     */
    public static boolean openFromHand(ServerPlayer player, InteractionHand hand) {
        FactoryService.ensureFactory(player);
        return open(player, hand);
    }

    /**
     * 切换终端视图并重开当前菜单。
     *
     * <p>两个视图是两个 MenuType，不能就地换；关掉再开是 AE2 通用终端同一套做法。
     * 选中状态与编码格随菜单重建，这是模式切换的预期代价。</p>
     *
     * @param player 玩家
     * @param menu   当前打开的终端菜单
     * @return 是否切换成功
     */
    public static boolean switchView(ServerPlayer player, AEBaseMenu menu) {
        View next = view(player) == View.CRAFTING ? View.PATTERN : View.CRAFTING;
        VIEWS.put(player.getUUID(), next);
        MenuHostLocator locator = menu.getLocator();
        if (locator == null) {
            return false;
        }
        // 不先 closeContainer：那会让客户端有一帧「没有界面」，鼠标被拉回屏幕中心。
        return MenuOpener.open(menuType(next), player, locator);
    }

    /**
     * 从子界面回到主菜单；视图取当前玩家的记录。
     *
     * @param player  玩家
     * @param locator 物品定位器
     * @return 是否已请求打开
     */
    public static boolean reopen(ServerPlayer player, @Nullable MenuHostLocator locator) {
        if (locator == null) {
            return false;
        }
        return MenuOpener.returnTo(menuType(view(player)), player, locator);
    }

    /**
     * 当前玩家用的终端视图。
     *
     * @param player 玩家
     * @return 视图；没记录过时为 {@link View#CRAFTING}
     */
    public static View view(ServerPlayer player) {
        return VIEWS.getOrDefault(player.getUUID(), View.CRAFTING);
    }

    /**
     * 当前打开的菜单是不是工厂自己的菜单。
     *
     * <p>旧工作台与新终端共用一套编辑负载，服务端只认「容器 id 对得上且确实是工厂菜单」，
     * 不信任客户端自称的界面身份。</p>
     *
     * @param menu        玩家当前菜单
     * @param containerId 负载里的容器 id
     * @return 是否允许处理该负载
     */
    public static boolean isFactoryMenu(AbstractContainerMenu menu, int containerId) {
        if (menu.containerId != containerId) {
            return false;
        }
        return menu instanceof FactoryMenu
                || menu instanceof FactoryCraftingTerminalMenu
                || menu instanceof FactoryPatternTerminalMenu;
    }

    /**
     * 玩家手上的终端物品定位器。
     *
     * @param player 玩家
     * @param hand   使用的手；为 {@code null} 时扫背包与副手
     * @return 定位器；没有终端物品时为 {@code null}
     */
    @Nullable
    private static ItemMenuHostLocator findLocator(ServerPlayer player, @Nullable InteractionHand hand) {
        if (hand != null) {
            ItemStack held = player.getItemInHand(hand);
            if (held.getItem() instanceof FactoryTerminalItem) {
                try {
                    return MenuLocators.forHand(player, hand);
                } catch (IllegalArgumentException e) {
                    // 物品不在背包索引里（被别的逻辑换过），退回扫背包。
                }
            }
        }
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot).getItem() instanceof FactoryTerminalItem) {
                return MenuLocators.forInventorySlot(slot);
            }
        }
        return null;
    }

    /**
     * 按视图取菜单类型。
     *
     * @param view 视图
     * @return 菜单类型
     */
    private static MenuType<? extends AEBaseMenu> menuType(View view) {
        return switch (view) {
            case CRAFTING -> ModMenus.FACTORY_CRAFTING_TERMINAL.get();
            case PATTERN -> ModMenus.FACTORY_PATTERN_TERMINAL.get();
        };
    }

    /**
     * 玩家自己的工厂。
     *
     * @param player 玩家
     * @return 工厂；运行时未就绪或没有工厂时为 {@code null}
     */
    @Nullable
    private static Factory ownedFactory(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return null;
        }
        Optional<Factory> factory = runtime.manager().byOwner(player.getUUID());
        return factory.orElse(null);
    }

    /**
     * 工厂是否已经接在 ME 网络上；容器模式与未绑定的 ME 模式都算否。
     *
     * @param factory 工厂
     * @return 可以开 AE 终端时为 {@code true}
     */
    private static boolean isMeMode(Factory factory) {
        return factory.config().storageMode() == StorageMode.ME
                && factory.config().storageAnchor() instanceof MeAnchor;
    }

    /**
     * 打开旧工作台；`/idf gui` 在没有终端物品时也走这里。
     *
     * @param player 玩家
     */
    private static void openLegacy(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (containerId, playerInventory, ignored) -> new FactoryMenu(containerId, playerInventory),
                Component.translatable("screen.idlefactory.title")));
    }
}
