package io.github.crafteve.idlefactory.menu;

import org.jetbrains.annotations.Nullable;

import appeng.api.inventories.InternalInventory;
import appeng.util.inv.AppEngInternalInventory;

import net.minecraft.world.item.ItemStack;

/**
 * 样板槽的「目标 + 分页」视图：固定 9 格，映射到选中目标样板库存的某一页。
 *
 * <p>槽位数量在菜单构造时固定，运行期只能换背后的库存；所以切目标 / 翻页不是增删槽，
 * 而是换 {@link #bind(InternalInventory, int)} 的委托与页偏移。客户端没有目标的真实库存，
 * 委托为空时退回本地 9 格，由容器同步把服务端槽位内容写进来。</p>
 */
public final class FactoryPatternSlotView implements InternalInventory {
    /** 一页的槽数。 */
    public static final int PAGE_SIZE = 9;

    /** 客户端兜底库存；服务端不会用到。 */
    private final InternalInventory clientFallback = new AppEngInternalInventory(PAGE_SIZE);
    /** 当前目标的样板库存；客户端为 {@code null}。 */
    @Nullable
    private InternalInventory delegate;
    /** 当前页，从 0 开始。 */
    private int page;

    /**
     * 绑定目标库存与页。
     *
     * @param target 目标样板库存；{@code null} 表示没有选中目标
     * @param page   页号，调用方负责夹在合法范围内
     */
    public void bind(@Nullable InternalInventory target, int page) {
        this.delegate = target;
        this.page = Math.max(0, page);
    }

    /** 当前页。 */
    public int page() {
        return this.page;
    }

    @Override
    public int size() {
        return PAGE_SIZE;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        if (slot < 0 || slot >= PAGE_SIZE) {
            return ItemStack.EMPTY;
        }
        InternalInventory active = this.active();
        int index = this.page * PAGE_SIZE + slot;
        return index < active.size() ? active.getStackInSlot(index) : ItemStack.EMPTY;
    }

    @Override
    public void setItemDirect(int slot, ItemStack stack) {
        if (slot < 0 || slot >= PAGE_SIZE) {
            return;
        }
        InternalInventory active = this.active();
        int index = this.page * PAGE_SIZE + slot;
        if (index < active.size()) {
            active.setItemDirect(index, stack);
        }
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        if (this.delegate == null) {
            return true;
        }
        int index = this.page * PAGE_SIZE + slot;
        return index < this.delegate.size() && this.delegate.isItemValid(index, stack);
    }

    @Override
    public int getSlotLimit(int slot) {
        if (this.delegate == null) {
            return 1;
        }
        int index = this.page * PAGE_SIZE + slot;
        return index < this.delegate.size() ? this.delegate.getSlotLimit(index) : 1;
    }

    /**
     * 当前实际读写的库存。
     *
     * @return 服务端是目标库存，客户端是本地兜底库存
     */
    private InternalInventory active() {
        return this.delegate == null ? this.clientFallback : this.delegate;
    }
}
