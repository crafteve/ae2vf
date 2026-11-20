package io.github.crafteve.ae2vf.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.ae2vf.node.Node;

import appeng.api.networking.IGridNode;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 活节点的索引：节点 id → 运行时节点。
 *
 * <p>只存、只查、只 tick；建与拆在 {@link LiveNodeApply}，对账判定在 {@link LiveNodeRunner}。
 * 它不认识 {@code Factory}，只吃 {@link Spec}，节点层不反向依赖工厂层。</p>
 */
public final class LiveNodeLibrary {
    private final MinecraftServer server;
    /** 存档数据变化回调：节点 tick 改动了运行时数据时触发。 */
    private final Runnable dataChanged;
    /** 节点告警出口。 */
    private final Consumer<Notice> notice;
    private final Map<UUID, LiveNode> nodes = new LinkedHashMap<>();
    /** 工厂侧要求提前对账的标记。 */
    private boolean reconcileRequested;

    /**
     * @param server      服务端实例
     * @param dataChanged 存档数据变化回调
     * @param notice      节点告警出口
     */
    public LiveNodeLibrary(MinecraftServer server, Runnable dataChanged, Consumer<Notice> notice) {
        this.server = server;
        this.dataChanged = dataChanged;
        this.notice = notice;
    }

    /**
     * 建节点需要的外部输入。
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

    /** 清掉对账请求。 */
    void clearReconcileRequest() {
        this.reconcileRequested = false;
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
    public LiveNode node(UUID nodeId) {
        return this.nodes.get(nodeId);
    }

    /** 是否已有该节点的运行时。 */
    boolean contains(UUID nodeId) {
        return this.nodes.containsKey(nodeId);
    }

    /** 全部节点的只读快照。 */
    Collection<LiveNode> all() {
        return List.copyOf(this.nodes.values());
    }

    /** 登记一个刚建好的节点。 */
    void add(LiveNode node) {
        this.nodes.put(node.node().id(), node);
    }

    /**
     * 摘掉一个节点，不拆。
     *
     * @param nodeId 节点 id
     * @return 被摘掉的节点；不存在时为 {@code null}
     */
    @Nullable
    LiveNode removeRaw(UUID nodeId) {
        return this.nodes.remove(nodeId);
    }

    /** 停机：摘掉全部节点并返回，由调用方统一 shutdown。 */
    List<LiveNode> drain() {
        List<LiveNode> remaining = new ArrayList<>(this.nodes.values());
        this.nodes.clear();
        return remaining;
    }

    /**
     * 每 tick 让所有节点收口一次：投递与回收在执行层，这里只推进节点自身。
     */
    public void tickNodes() {
        long now = this.server.overworld().getGameTime();
        boolean changed = false;
        for (Iterator<LiveNode> it = this.nodes.values().iterator(); it.hasNext();) {
            LiveNode node = it.next();
            try {
                changed |= node.tick();
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
     * 发一条节点告警给运行时。
     *
     * @param factoryId 工厂 id
     * @param nodeId    节点 id
     * @param message   文案
     */
    void report(UUID factoryId, UUID nodeId, String message) {
        this.notice.accept(new Notice(factoryId, nodeId, message));
    }
}
