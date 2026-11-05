package io.github.crafteve.idlefactory.factory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.location.BlockKey;

/**
 * 绑定杖草稿：模式 + 目标节点 + 待接入的机器与方块。
 *
 * <p>草稿的权威副本在客户端静态缓存，同时写进绑定杖的非 persistent、非 network-synchronized
 * 物品组件，供 tooltip 与重开界面恢复；服务端不读组件，只信提交包。</p>
 *
 * <p>{@code nodeId == null} 表示新建节点；编辑已有节点时只追加机器，绝不删除原有机器/方块。</p>
 *
 * @param mode     绑定模式
 * @param nodeId   目标节点 id；{@code null} 表示新建
 * @param machines 待接入的机器，每台至少一个方块
 */
public record BindingDraft(BindingMode mode, @Nullable UUID nodeId, List<MachineDraft> machines) {

    public BindingDraft {
        machines = List.copyOf(machines);
    }

    /**
     * 草稿里的一台机器。
     *
     * @param blocks 方块列表；第 0 个是类型方块
     */
    public record MachineDraft(List<BlockKey> blocks) {
        public MachineDraft {
            blocks = List.copyOf(blocks);
        }
    }

    /** 草稿是否为空。 */
    public boolean isEmpty() {
        return this.machines.isEmpty();
    }

    /** 方块总数。 */
    public int blockCount() {
        int total = 0;
        for (MachineDraft machine : this.machines) {
            total += machine.blocks().size();
        }
        return total;
    }

    /**
     * 换模式；草稿非空时由调用方拒绝。
     *
     * @param next 新模式
     * @return 新草稿
     */
    public BindingDraft withMode(BindingMode next) {
        return new BindingDraft(next, this.nodeId, this.machines);
    }

    /**
     * 清空草稿，保留模式。
     *
     * @return 空草稿
     */
    public BindingDraft cleared() {
        return new BindingDraft(this.mode, null, List.of());
    }

    /**
     * 追加一台机器。
     *
     * @param blocks 方块列表
     * @return 新草稿
     */
    public BindingDraft withMachine(List<BlockKey> blocks) {
        List<MachineDraft> next = new ArrayList<>(this.machines);
        next.add(new MachineDraft(blocks));
        return new BindingDraft(this.mode, this.nodeId, next);
    }
}
