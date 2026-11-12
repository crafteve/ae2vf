package io.github.crafteve.idlefactory.factory.node;

import java.util.Map;
import java.util.Set;

import io.github.crafteve.idlefactory.factory.io.IoExecutor;
import io.github.crafteve.idlefactory.factory.io.MoveResult;
import io.github.crafteve.idlefactory.factory.machine.Machine;

import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;

/**
 * 常驻产物回收：把机器上的样板产物抽出来插进 ME 网络，让 CPU 自己判定任务完成。
 *
 * <p>白名单只有样板产物，不加容器物品——输入槽里的原料不是产物，抽走会让加工停摆。
 * 节奏是「活跃 5 tick、空闲 20 → 40 → 80 → 128」：派单成功或刚抽到东西算活跃，
 * 长时间没收获就退避，避免六面能力查询把服务器 tick 吃掉。调度由 {@link NodeManager}
 * 统一分帧，本类只管一台机器。</p>
 *
 * <p>抽取与插网的动作统一交给 {@link IoExecutor#sweep}：先问网络能收多少，抽出来立刻插网络；
 * 插不回去的塞回机器，机器也不要才记日志丢弃。全程不丢东西。</p>
 */
public final class ProviderReturnSweep {
    private final NodeRuntime node;
    /** 回收的择时住在这里，本类只负责扫。 */
    private final ActivePlanner planner;
    /** 机器侧唯一写入口。 */
    private final IoExecutor executor;

    /**
     * @param node    所属机器节点
     * @param planner 主动规划器，决定什么时候扫
     */
    ProviderReturnSweep(NodeRuntime node, ActivePlanner planner) {
        this.node = node;
        this.planner = planner;
        this.executor = new IoExecutor(node.ports());
    }

    /**
     * 切回活跃节奏；派单成功、抽到东西、机器重新上线时调。
     *
     * @param now 当前游戏时间
     */
    public void activate(long now) {
        this.planner.activate(now);
    }

    /**
     * 这一轮轮到这台机器了吗。
     *
     * @param now 当前游戏时间
     * @return 是否到期
     */
    public boolean isDue(long now) {
        return this.planner.sweepDue(now);
    }

    /**
     * 扫一轮：白名单里的产物能进网络就抽走。
     *
     * @param now 当前游戏时间
     */
    public void sweep(long now) {
        Set<AEKey> products = this.node.products();
        if (products.isEmpty() || !this.node.isAvailable()) {
            this.idle(now);
            return;
        }
        MEStorage network = this.node.networkStorage();
        if (network == null) {
            this.idle(now);
            return;
        }
        boolean harvested = false;
        for (Machine machine : this.node.onlineMachines()) {
            Map<AEKey, MoveResult> results = this.executor.sweep(machine, network, products);
            for (MoveResult result : results.values()) {
                if (result.movedAnything()) {
                    harvested = true;
                }
            }
        }
        if (harvested) {
            this.activate(now);
            return;
        }
        this.idle(now);
    }

    /**
     * 没收获时排下一次扫描：活跃期保持 5 tick，空闲按阶梯退避。
     *
     * @param now 当前游戏时间
     */
    private void idle(long now) {
        this.planner.idle(now);
    }
}
