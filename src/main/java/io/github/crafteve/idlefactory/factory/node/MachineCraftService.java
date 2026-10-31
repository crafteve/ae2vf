package io.github.crafteve.idlefactory.factory.node;

import java.util.List;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;

/**
 * 机器的虚拟样板供应器：把机器上的样板广告给 CPU，并接住 CPU 派来的一份输入。
 *
 * <p>这是挂到虚拟节点上的 {@link ICraftingProvider} 服务，生命周期跟着节点走。AE2 的节点服务
 * 只能在 {@code create} 之前挂，所以它常驻，样板为空时就是「没有任何样板的供应器」。</p>
 *
 * <p>本分片只接通服务本身；样板解码、派单与余量在后续分片补齐。</p>
 */
final class MachineCraftService implements ICraftingProvider {
    private final MachineNode node;

    /**
     * @param node 所属机器节点
     */
    MachineCraftService(MachineNode node) {
        this.node = node;
    }

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        return List.of();
    }

    @Override
    public boolean pushPattern(IPatternDetails details, KeyCounter[] inputHolder) {
        return false;
    }

    @Override
    public boolean isBusy() {
        return false;
    }

    /**
     * 让 CPU 重新读一遍这台机器的样板。
     *
     * <p>样板或机器可用性变了就要调：AE2 的合成服务在节点注册时拍快照，不刷新的话 CPU 看到的
     * 永远是旧的。节点已经拆掉或还没接进网络时静默跳过。</p>
     */
    void refresh() {
        this.node.requestPatternUpdate();
    }
}
