package io.github.crafteve.idlefactory.menu;

import org.jetbrains.annotations.Nullable;

import appeng.api.inventories.InternalInventory;
import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.menu.slot.AppEngSlot;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

/**
 * 工厂终端的样板视图：界面右半是 AE2 样板编码终端，左半是目标与主动 / 被动管理面板。
 *
 * <p>编码格与编码按钮全部复用 AE2；左面板的 9 个样板槽是真实 {@link AppEngSlot}，背后由
 * {@link FactoryPatternSlotView} 映射到「选中目标的样板库存 + 当前页」。槽位在构造时固定，
 * 切目标 / 翻页只换视图，不增删槽。选中状态只活在当前菜单里，切视图会重置。</p>
 */
public final class FactoryPatternTerminalMenu extends PatternEncodingTermMenu {
    /** 服务端的目标表；客户端为 {@code null}。 */
    @Nullable
    private final FactoryTerminalTargets targets;
    /** 样板槽视图。 */
    private final FactoryPatternSlotView slotView = new FactoryPatternSlotView();

    /** 当前选中的目标键；空串表示没选。 */
    private String selectedKey = "";
    /** 当前样板页。 */
    private int page;

    /**
     * @param menuType    注册出来的菜单类型
     * @param containerId 本次会话的容器 id
     * @param inventory   玩家物品栏
     * @param host        工厂终端宿主
     */
    public FactoryPatternTerminalMenu(MenuType<?> menuType, int containerId, Inventory inventory,
            FactoryTerminalHost host) {
        super(menuType, containerId, inventory, host, true);
        this.targets = host.factory() == null || !(inventory.player instanceof ServerPlayer player)
                ? null : new FactoryTerminalTargets(host.factory(), player);
        for (int slot = 0; slot < FactoryPatternSlotView.PAGE_SIZE; slot++) {
            this.addSlot(new AppEngSlot(this.slotView, slot), FactoryPatternSlots.PATTERN_SLOT);
        }
    }

    /** 服务端的目标表；客户端为 {@code null}。 */
    @Nullable
    public FactoryTerminalTargets targets() {
        return this.targets;
    }

    /** 样板槽视图；屏幕用它摆位。 */
    public FactoryPatternSlotView slotView() {
        return this.slotView;
    }

    /** 当前页。 */
    public int page() {
        return this.page;
    }

    /** 当前选中的目标键；没选时为空串。 */
    public String selectedKey() {
        return this.selectedKey;
    }

    /**
     * 选中一个目标并翻到指定页；由 C2S 负载调用，服务端重新按键解析。
     *
     * @param player 玩家
     * @param key    目标键
     * @param page   页号；越界会被夹回第一页 / 最后一页
     * @return 是否命中一个存在的目标
     */
    public boolean selectTarget(ServerPlayer player, String key, int page) {
        if (this.targets == null) {
            return false;
        }
        this.targets.select(key);
        if (!this.targets.selectedKey().equals(key)) {
            return false;
        }
        InternalInventory inventory = this.targets.inventoryOf(key);
        this.selectedKey = key;
        this.page = clampPage(inventory, page);
        this.slotView.bind(inventory, this.page);
        return true;
    }

    /**
     * 把页号夹到目标库存的合法范围。
     *
     * @param inventory 目标库存；{@code null} 时只允许第一页
     * @param page      请求页
     * @return 合法页号
     */
    private static int clampPage(@Nullable InternalInventory inventory, int page) {
        if (inventory == null) {
            return 0;
        }
        int pages = Math.max(1, (inventory.size() + FactoryPatternSlotView.PAGE_SIZE - 1)
                / FactoryPatternSlotView.PAGE_SIZE);
        return Math.max(0, Math.min(pages - 1, page));
    }
}
