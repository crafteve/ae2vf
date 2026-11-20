package io.github.crafteve.ae2vf.io;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.ae2vf.machine.Machine;
import io.github.crafteve.ae2vf.node.NodeDispatchQueue;

import appeng.api.stacks.AEKey;

import net.minecraft.core.Direction;

/**
 * 这一 tick 投递需要的输入：队列、在线机器、派单模式、阻挡开关、样板输入、产物白名单。
 *
 * <p>本类是纯粹的值对象：只带值，不带存储句柄——网络由 Runner 从工厂拿。队列是<b>活引用</b>
 * （只有 {@link NodeDispatchQueue} 能改它），规划器用它的方法更新进度，而不是拿到一份副本算完再丢。</p>
 *
 * <p>节点不认识执行层，执行层也不 import 节点：两边只交换这个记录。</p>
 *
 * @param nodeId         节点 id
 * @param queue          待投递队列（活引用）
 * @param onlineMachines 在线机器，顺序即节点成员顺序
 * @param memberMachines 节点全部成员机器 id；用来区分「机器被删」与「机器暂时没加载」
 * @param dispatch       派单模式
 * @param blocking       阻挡开关
 * @param patternInputs  样板输入的并集（已去组件）
 * @param products       样板产物的并集（回收白名单）
 * @param inputFaces     样板输入的配置面：资源 → 面（玩家给输入规则配的面）
 */
public record DispatchState(UUID nodeId, NodeDispatchQueue queue, List<Machine> onlineMachines,
        DispatchMode dispatch, BlockingMode blocking, Set<AEKey> patternInputs,
        Set<AEKey> products, Map<AEKey, Direction> inputFaces, Set<UUID> memberMachines) {

    public DispatchState {
        onlineMachines = List.copyOf(onlineMachines);
        memberMachines = Set.copyOf(memberMachines);
        patternInputs = Set.copyOf(patternInputs);
        products = Set.copyOf(products);
        inputFaces = Map.copyOf(inputFaces);
    }

    /**
     * 空状态：节点还没有可投的东西（机器全掉线、样板为空）。
     *
     * @param nodeId 节点 id
     * @param queue  队列
     * @return 空状态
     */
    public static DispatchState empty(UUID nodeId, NodeDispatchQueue queue) {
        return new DispatchState(nodeId, queue, List.of(), DispatchMode.ROUND_ROBIN,
                BlockingMode.OFF, Set.of(), Set.of(), Map.of(), Set.of());
    }

    /**
     * 给一个资源查配置面：先精确、再同注册名的模糊规则。
     *
     * @param key 资源
     * @return 配置面；没配时为 {@code null}
     */
    @Nullable
    public Direction inputFace(AEKey key) {
        Direction exact = this.inputFaces.get(key);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<AEKey, Direction> entry : this.inputFaces.entrySet()) {
            AEKey configured = entry.getKey();
            if (configured.getType().equals(key.getType())
                    && configured.getId().equals(key.getId())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 这台机器此刻是否被阻挡（库存里还有这批样板的输入）。
     *
     * @param inventory 机器各面库存的并集（资源 → 数量），由调用方按需读
     * @return 任一输入 key 还在机器里时为 {@code true}
     */
    public boolean blockingHit(Map<AEKey, Long> inventory) {
        if (!this.blocking.isOn() || this.patternInputs.isEmpty() || inventory.isEmpty()) {
            return false;
        }
        for (AEKey key : inventory.keySet()) {
            if (this.patternInputs.contains(key.dropSecondary())) {
                return true;
            }
        }
        return false;
    }
}
