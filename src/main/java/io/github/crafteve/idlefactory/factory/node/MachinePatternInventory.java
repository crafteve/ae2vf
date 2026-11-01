package io.github.crafteve.idlefactory.factory.node;

import appeng.api.inventories.InternalInventory;
import appeng.core.definitions.AEItems;

import net.minecraft.world.item.ItemStack;
import io.github.crafteve.idlefactory.factory.machine.MachineProvider;

/**
 * 机器样板槽的 {@link InternalInventory} 视图：AE2 样板访问终端读写的就是这里。
 *
 * <p>数据本身在 {@link MachineProvider}（跟着机器记录落盘），本类只把容量、槽位与「只收处理
 * 样板」的过滤规则翻译成 AE2 的库存接口。写入后由节点统一刷新解码缓存并提示存档，避免终端
 * 一次搬多格时反复解码。</p>
 */
final class MachinePatternInventory implements InternalInventory {
    private final MachineNode node;

    /**
     * @param node 所属机器节点
     */
    MachinePatternInventory(MachineNode node) {
        this.node = node;
    }

    @Override
    public int size() {
        return this.provider().capacity();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return this.provider().pattern(slot);
    }

    @Override
    public void setItemDirect(int slot, ItemStack stack) {
        MachineProvider provider = this.provider();
        int capacityBefore = provider.capacity();
        provider.setPattern(slot, stack);
        // 第一张处理样板意味着这台机器开始参与主动合成；玩家手动切过模式后不再自动改。
        if (!stack.isEmpty() && provider.autoActivateOnPattern()) {
            this.node.requestPatternUpdate();
        }
        this.node.onPatternSlotChanged(provider.capacity() != capacityBefore);
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return AEItems.PROCESSING_PATTERN.is(stack);
    }

    @Override
    public int getSlotLimit(int slot) {
        return 1;
    }

    /** 这台机器的供应器数据。 */
    private MachineProvider provider() {
        return this.node.machine().provider();
    }
}
