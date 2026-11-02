package io.github.crafteve.idlefactory.menu;

import org.jetbrains.annotations.Nullable;

import appeng.api.config.Settings;
import appeng.api.config.SortDir;
import appeng.api.config.SortOrder;
import appeng.api.config.ViewItems;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.api.inventories.ISegmentedInventory;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.storage.ILinkStatus;
import appeng.api.storage.MEStorage;
import appeng.api.storage.SupplierStorage;
import appeng.api.util.IConfigManager;
import appeng.helpers.IPatternTerminalLogicHost;
import appeng.helpers.IPatternTerminalMenuHost;
import appeng.me.storage.NullInventory;
import appeng.menu.ISubMenu;
import appeng.menu.locator.ItemMenuHostLocator;
import appeng.parts.encoding.PatternEncodingLogic;
import appeng.parts.reporting.CraftingTerminalPart;
import appeng.util.inv.AppEngInternalInventory;

import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.factory.storage.MeAnchor;
import io.github.crafteve.idlefactory.factory.storage.StorageMode;
import io.github.crafteve.idlefactory.integration.ae2.Ae2Binder;
import io.github.crafteve.idlefactory.item.FactoryTerminalItem;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 工厂终端的菜单宿主：把工厂仓库、ME 锚点与样板编码逻辑接到 AE2 的终端菜单上。
 *
 * <p>服务端拿工厂的真实仓库与锚点节点；客户端只能拿空库存和空节点，否则菜单构造会读到
 * 只存在于服务端的对象。两边共用同一个类，靠 {@code isClientSide()} 分支，避免菜单类型
 * 声明的主机类型与实际返回类型不一致导致终端静默打不开。</p>
 */
public final class FactoryTerminalHost extends ItemMenuHost<FactoryTerminalItem>
        implements IPatternTerminalMenuHost, IActionHost, ISegmentedInventory,
        IPatternTerminalLogicHost {
    /** 合成格数量；与 AE2 合成终端一致。 */
    private static final int CRAFTING_GRID_SIZE = 9;

    /** 服务端的工厂；客户端恒为 {@code null}。 */
    @Nullable
    private final Factory factory;
    /** 终端看到的仓库；服务端跟随工厂运行时，客户端为空库存。 */
    private final MEStorage inventory;
    /** 排序 / 显示模式等终端设置；写回物品栈，跟随终端走。 */
    private final IConfigManager configManager;
    /** 合成格；不持久化，关掉终端即清空。 */
    private final InternalInventory craftingGrid = new AppEngInternalInventory(CRAFTING_GRID_SIZE);
    /** 样板编码逻辑；模式 B 使用。 */
    private final PatternEncodingLogic logic = new PatternEncodingLogic(this);
    /** 动作节点缓存：主线程解析，合成计算线程只读。 */
    @Nullable
    private IGridNode actionableNode;

    /**
     * @param item    终端物品
     * @param player  打开终端的玩家
     * @param locator 物品定位器；客户端靠它还原同一个物品栈
     */
    public FactoryTerminalHost(FactoryTerminalItem item, Player player, ItemMenuHostLocator locator) {
        super(item, player, locator);
        this.factory = resolveFactory(player);
        this.inventory = this.factory == null
                ? NullInventory.of()
                : new SupplierStorage(() -> this.factory.live().storage());
        this.configManager = IConfigManager.builder(this::getItemStack)
                .registerSetting(Settings.SORT_BY, SortOrder.NAME)
                .registerSetting(Settings.VIEW_MODE, ViewItems.ALL)
                .registerSetting(Settings.SORT_DIRECTION, SortDir.ASCENDING)
                .build();
    }

    /**
     * 服务端按拥有者找工厂；客户端没有工厂运行时，返回 {@code null}。
     *
     * @param player 玩家
     * @return 工厂；客户端或尚未创建时为 {@code null}
     */
    @Nullable
    private static Factory resolveFactory(Player player) {
        if (player.level().isClientSide) {
            return null;
        }
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return null;
        }
        return runtime.manager().byOwner(player.getUUID()).orElse(null);
    }

    /** 服务端的工厂；客户端为 {@code null}。 */
    @Nullable
    public Factory factory() {
        return this.factory;
    }

    @Override
    public MEStorage getInventory() {
        return this.inventory;
    }

    /**
     * 终端连接状态：只有 ME 模式且仓库解析成功才算连上。
     *
     * <p>容器模式仍会走旧界面，这里只是兜底；仓库离线时 AE2 的终端会把库存格压暗并
     * 拒绝交互。</p>
     */
    @Override
    public ILinkStatus getLinkStatus() {
        if (this.isClientSide() || this.factory == null) {
            return ILinkStatus.ofDisconnected();
        }
        if (this.factory.config().storageMode() != StorageMode.ME) {
            return ILinkStatus.ofDisconnected(Component.literal("当前是容器模式"));
        }
        return this.factory.live().storageOnline()
                ? ILinkStatus.ofConnected()
                : ILinkStatus.ofDisconnected(Component.literal("仓库离线"));
    }

    @Override
    public IConfigManager getConfigManager() {
        return this.configManager;
    }

    /**
     * 动作节点：服务端是在工厂 ME 锚点方块上找到的节点，客户端为 {@code null}。
     *
     * <p>AE2 的终端拿它做能量源与合成服务查询；拿不到就等同于离线终端，菜单仍然能开。</p>
     *
     * <p><b>非主线程只读缓存</b>：AE2 的合成计算线程也会调这里，它一旦经
     * {@code GridHelper.getNodeHost} 走到 {@code level.getBlockState}，就会 join 主线程的
     * 区块加载；而主线程此时正卡在 {@code CraftingCalculation.simulateFor} 等计算线程，
     * 两者互等会直接把服务端线程锁死。所以只有主线程才真正解析，其余线程返回上一次缓存。</p>
     */
    @Override
    @Nullable
    public IGridNode getActionableNode() {
        if (this.isClientSide() || this.factory == null
                || !(this.factory.config().storageAnchor() instanceof MeAnchor anchor)) {
            return null;
        }
        MinecraftServer server = this.getPlayer().getServer();
        if (server == null) {
            return null;
        }
        if (!server.isSameThread()) {
            return this.actionableNode;
        }
        ServerLevel level = server.getLevel(anchor.dimension());
        this.actionableNode = level == null ? null : Ae2Binder.nodeAt(level, anchor.pos());
        return this.actionableNode;
    }

    @Override
    public PatternEncodingLogic getLogic() {
        return this.logic;
    }

    @Override
    public Level getLevel() {
        return this.getPlayer().level();
    }

    /**
     * 编码格不落盘：计划里这一版不持久化样板编码，关闭终端即清空，所以没有要保存的东西。
     */
    @Override
    public void markForSave() {
        // 编码格是会话级状态，不写物品也不写工厂。
    }

    @Override
    @Nullable
    public InternalInventory getSubInventory(ResourceLocation id) {
        return CraftingTerminalPart.INV_CRAFTING.equals(id) ? this.craftingGrid : null;
    }

    @Override
    public void returnToMainMenu(Player player, ISubMenu subMenu) {
        if (player instanceof ServerPlayer serverPlayer) {
            FactoryTerminalMenus.reopen(serverPlayer, this.getLocator());
        }
    }

    @Override
    public ItemStack getMainMenuIcon() {
        return this.getItemStack();
    }
}
