package io.github.crafteve.idlefactory.factory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.location.BlockKey;

/**
 * 绑定杖草稿：模式 + 目标节点 + 待接入的机器。
 *
 * <p>草稿的权威副本在客户端静态缓存，同时写进绑定杖的非 persistent、非 network-synchronized
 * 物品组件，供 tooltip 与重开界面恢复；服务端不读组件，只信提交包。</p>
 *
 * <p>{@code nodeId == null} 表示新建节点；编辑已有节点时只追加机器，绝不删除原有机器。</p>
 *
 * <p>机器与方块已经统一：一台机器就是一个方块，所以草稿里的每台机器只记一个
 * {@link BlockKey}，节点靠机器列表承担「1 台 / 多台」的区别。</p>
 *
 * @param mode     绑定模式
 * @param nodeId   目标节点 id；{@code null} 表示新建
 * @param machines 待接入的机器，每台一个方块
 */
public record BindingDraft(BindingMode mode, @Nullable UUID nodeId, List<MachineDraft> machines) {

    public BindingDraft {
        machines = List.copyOf(machines);
    }

    /**
     * 草稿里的一台机器。
     *
     * @param block 这台机器占用的唯一方块
     */
    public record MachineDraft(BlockKey block) {
    }

    /** 草稿是否为空。 */
    public boolean isEmpty() {
        return this.machines.isEmpty();
    }

    /**
     * 追加一台机器。
     *
     * @param block 机器所在方块
     * @return 新草稿
     */
    public BindingDraft withMachine(BlockKey block) {
        List<MachineDraft> next = new ArrayList<>(this.machines);
        next.add(new MachineDraft(block));
        return new BindingDraft(this.mode, this.nodeId, next);
    }

    /**
     * 移除一台机器。
     *
     * @param block 机器所在方块
     * @return 新草稿；原草稿里没有这个方块时返回自身
     */
    public BindingDraft withoutMachine(BlockKey block) {
        List<MachineDraft> next = new ArrayList<>(this.machines.size());
        boolean removed = false;
        for (MachineDraft machine : this.machines) {
            if (machine.block().equals(block)) {
                removed = true;
            } else {
                next.add(machine);
            }
        }
        return removed ? new BindingDraft(this.mode, this.nodeId, next) : this;
    }
}
