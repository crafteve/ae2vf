package io.github.crafteve.idlefactory.factory.node;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;

import appeng.api.networking.IGridNode;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 全部虚拟节点节点的对账表：该有的建出来，不该有的拆掉，其余每 tick 推一次。
 *
 * <p>键是节点 id：一个节点一个 AE2 节点 / PatternContainer，成员机器只是节点里的方块集合。
 * 它不认识 {@code Factory}，只吃运行时拼好的 {@link Spec}：这样节点层不反向依赖工厂层。
 * 建节点分帧（每 tick 上限 {@link #MAX_CREATES_PER_TICK}）。</p>
 */
public final class NodeManager {
    /** 每 tick 最多建几个节点。 */
    public static final int MAX_CREATES_PER_TICK = 128;
    /** 每 tick 最多做几次产物回收扫描；超出的节点顺延到下一 tick。 */
    public static final int MAX_SWEEPS_PER_TICK = 64;

    private final MinecraftServer server;
    /** 存档数据变化回调。 */
    private final Runnable dataChanged;
    /** 节点告警出口。 */
    private final Consumer<Notice> notice;
    private final Map<UUID, NodeRuntime> nodes = new LinkedHashMap<>();
    /** 待建节点；每次对账整体替换。 */
    private final ArrayDeque<Spec> createQueue = new ArrayDeque<>();
    /** 工厂侧要求提前对账的标记。 */
    private boolean reconcileRequested;
    /** 本轮到账的逐机器在线集合，建节点时用。 */
    private final Map<UUID, Set<UUID>> onlineByNode = new HashMap<>();

    /**
     * @param server      服务端实例
     * @param dataChanged 存档数据变化回调
     * @param notice      节点告警出口
     */
    public NodeManager(MinecraftServer server, Runnable dataChanged, Consumer<Notice> notice) {
        this.server = server;
        this.dataChanged = dataChanged;
        this.notice = notice;
    }

    /**
     * 建节点需要的外部输入。
     *
     * <p>由运行时按「工厂是 ME 模式、锚点节点能解析出来」筛好后交进来，节点层不自己找锚点。</p>
     *
     * @param factoryId   所属工厂 id
     * @param node        节点记录
     * @param anchor      工厂 ME 锚点节点
     * @param anchorLevel 锚点所在维度
     * @param available   节点此刻是否有可用的在线机器
     */
    public record Spec(UUID factoryId, Node node, IGridNode anchor, ServerLevel anchorLevel,
            boolean available) {
    }

    /**
     * 一条节点层告警，由运行时转进工厂的事件流。
     *
     * @param factoryId 所属工厂 id
     * @param nodeId    节点 id
     * @param message   给玩家看的短句
     */
    public record Notice(UUID factoryId, UUID nodeId, String message) {
    }

    /** 请求下一 tick 提前对账一次。 */
    public void requestReconcile() {
        this.reconcileRequested = true;
    }

    /** 是否有人请求过提前对账。 */
    public boolean isReconcileRequested() {
        return this.reconcileRequested;
    }

    /** 当前节点数。 */
    public int size() {
        return this.nodes.size();
    }

    /**
     * 按节点 id 取运行时节点。
     *
     * @param nodeId 节点 id
     * @return 节点；没建出来时为 {@code null}
     */
    @Nullable
    public NodeRuntime node(UUID nodeId) {
        return this.nodes.get(nodeId);
    }

    /**
     * 对账：拆掉不该有的，排上该建的。
     *
     * <p>锚点节点实例变了、结构版本变了就整个重建——旧节点还挂在旧网格上，没法就地迁移。</p>
     *
     * @param specs          期望存在的节点
     * @param onlineMachines 节点 id → 在线机器 id 集合
     */
    public void reconcile(List<Spec> specs, Map<UUID, Set<UUID>> onlineMachines) {
        this.reconcileRequested = false;
        this.onlineByNode.clear();
        this.onlineByNode.putAll(onlineMachines);
        Map<UUID, Spec> wanted = new LinkedHashMap<>();
        for (Spec spec : specs) {
            wanted.put(spec.node().id(), spec);
        }

        Iterator<Map.Entry<UUID, NodeRuntime>> iterator = this.nodes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, NodeRuntime> entry = iterator.next();
            Spec spec = wanted.get(entry.getKey());
            if (spec == null || !entry.getValue().matches(spec)) {
                try {
                    entry.getValue().detach();
                } catch (RuntimeException e) {
                    entry.getValue().logThrottled(this.server.overworld().getGameTime(),
                            "拆节点异常：" + e);
                }
                iterator.remove();
            }
        }

        this.createQueue.clear();
        for (Spec spec : wanted.values()) {
            if (!this.nodes.containsKey(spec.node().id())) {
                this.createQueue.add(spec);
            }
        }
        for (Spec spec : wanted.values()) {
            NodeRuntime node = this.nodes.get(spec.node().id());
            if (node != null) {
                try {
                    // 名字 / 规则等非结构变化不重建节点，但要刷新到运行时。
                    node.updateNode(spec.node());
                    node.setAvailable(spec.available());
                    node.setOnlineMachines(this.onlineByNode.getOrDefault(spec.node().id(),
                            Set.of()));
                } catch (RuntimeException e) {
                    node.logThrottled(this.server.overworld().getGameTime(),
                            "更新节点异常：" + e);
                }
            }
        }
    }

    /** 每 tick 推一次：先补建节点，再让每个节点处理自己的脏数据与产物回收。 */
    public void tick() {
        long now = this.server.overworld().getGameTime();
        this.createQueued(now);

        boolean changed = false;
        int sweepBudget = MAX_SWEEPS_PER_TICK;
        for (NodeRuntime node : this.nodes.values()) {
            try {
                changed |= node.tick();
                if (sweepBudget > 0 && node.returnSweep().isDue(now)) {
                    node.returnSweep().sweep(now);
                    sweepBudget--;
                }
            } catch (RuntimeException e) {
                // 单个节点炸掉不能影响别的节点，也不能把 tick 抛出去。
                node.logThrottled(now, "节点 tick 异常：" + e);
            }
        }
        if (changed) {
            this.dataChanged.run();
        }
    }

    /**
     * 拆掉一个节点的运行时，并把待写入队列还回网络；节点删除走这里。
     *
     * @param nodeId 节点 id
     */
    public void removeNode(UUID nodeId) {
        NodeRuntime node = this.nodes.remove(nodeId);
        if (node != null) {
            try {
                node.detachReturning();
            } catch (RuntimeException e) {
                node.logThrottled(this.server.overworld().getGameTime(), "拆节点异常：" + e);
            }
        }
    }

    /**
     * 作废一个节点的样板缓存：下一 tick 重新解码并按当前倍率重新广告。
     *
     * <p>倍率变化必须走这里。只让 CPU 重读（{@code requestPatternUpdate}）读到的是旧缓存，
     * 放大后的样板是在重建时才生成的。</p>
     *
     * @param nodeId 节点 id
     */
    public void markPatternsDirty(UUID nodeId) {
        NodeRuntime node = this.nodes.get(nodeId);
        if (node != null) {
            node.markPatternsDirty();
        }
    }

    /**
     * 改名后刷新样板终端分组名。
     *
     * @param nodeId 节点 id
     */
    public void refreshName(UUID nodeId) {
        NodeRuntime node = this.nodes.get(nodeId);
        if (node != null) {
            node.refreshName();
        }
    }

    /**
     * 更新一个节点的可用性；节点内机器出现 / 消失时由观测迁移驱动。
     *
     * @param nodeId    节点 id
     * @param available 现在是否可用
     */
    public void setAvailable(UUID nodeId, boolean available) {
        NodeRuntime node = this.nodes.get(nodeId);
        if (node != null) {
            node.setAvailable(available);
        }
    }

    /**
     * 发一条节点告警给运行时。
     *
     * @param factoryId 工厂 id
     * @param nodeId    节点 id
     * @param message   文案
     */
    void report(UUID factoryId, UUID nodeId, String message) {
        this.notice.accept(new Notice(factoryId, nodeId, message));
    }

    /** 停机：拆掉全部节点，不碰存档数据。 */
    public void destroyAll() {
        this.createQueue.clear();
        List<NodeRuntime> remaining = new ArrayList<>(this.nodes.values());
        this.nodes.clear();
        for (NodeRuntime node : remaining) {
            try {
                node.shutdown();
            } catch (RuntimeException e) {
                IdleFactory.LOGGER.warn("Idle Factory: 停机拆节点失败 [{}]：{}",
                        node.node().id(), e.toString());
            }
        }
    }

    /**
     * 按预算建队列里的节点。
     *
     * @param now 当前游戏时间，告警节流用
     */
    private void createQueued(long now) {
        int budget = MAX_CREATES_PER_TICK;
        while (budget > 0 && !this.createQueue.isEmpty()) {
            budget--;
            Spec spec = this.createQueue.poll();
            if (this.nodes.containsKey(spec.node().id())) {
                continue;
            }
            NodeRuntime node;
            try {
                node = new NodeRuntime(this.server, spec.factoryId(), spec.node(),
                        spec.anchor(), spec.anchorLevel(), spec.available(), this.notice);
            } catch (RuntimeException e) {
                IdleFactory.LOGGER.warn("Idle Factory: 构造节点失败 [{}]：{}",
                        spec.node().id(), e.toString());
                continue;
            }
            node.setOnlineMachines(this.onlineByNode.getOrDefault(spec.node().id(), Set.of()));
            try {
                if (!node.attach(spec.anchor())) {
                    continue;
                }
            } catch (RuntimeException e) {
                node.logThrottled(now, "建节点异常：" + e);
                continue;
            }
            this.nodes.put(spec.node().id(), node);
        }
    }
}
