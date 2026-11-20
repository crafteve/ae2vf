package io.github.crafteve.ae2vf.runtime;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.crafteve.ae2vf.runtime.LiveNodeLibrary.Spec;

/**
 * 活节点的每 tick 推进：先对账（建拆排队），再执行建拆，最后让所有节点收口。
 *
 * <p>对账只在「到点、锚点实例变化、结构版本变化」时做，其余 tick 只跑建拆与节点 tick。
 * 判定所需的事实（锚点实例、结构版本、在线机器）由工厂层拼成 {@link Spec} 传进来，这里
 * 不反向依赖工厂层。</p>
 */
public final class LiveNodeRunner {
    private final LiveNodeApply apply;
    private final LiveNodeLibrary library;

    /**
     * @param apply   建拆执行器
     * @param library 活节点索引
     */
    public LiveNodeRunner(LiveNodeApply apply, LiveNodeLibrary library) {
        this.apply = apply;
        this.library = library;
    }

    /**
     * 做一轮对账：算出差异并排队。
     *
     * @param specs          期望存在的节点
     * @param onlineMachines 节点 id → 在线机器 id 集合
     */
    public void reconcile(List<Spec> specs, Map<UUID, Set<UUID>> onlineMachines) {
        this.apply.reconcile(specs, onlineMachines);
    }

    /** 推进一 tick：建拆按预算分帧，然后所有节点收口。 */
    public void tick() {
        this.apply.tick();
        this.library.tickNodes();
    }

    /** 当前节点数。 */
    public int size() {
        return this.library.size();
    }
}
