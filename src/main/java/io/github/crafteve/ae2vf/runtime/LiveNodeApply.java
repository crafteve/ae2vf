package io.github.crafteve.ae2vf.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.ae2vf.VoidFactory;
import io.github.crafteve.ae2vf.io.PortExecutor;
import io.github.crafteve.ae2vf.runtime.LiveNodeLibrary.Spec;

import net.minecraft.server.MinecraftServer;

/**
 * 活节点的建与拆：每 tick 各最多 {@link #MAX_APPLIES_PER_TICK} 个，分帧进行。
 *
 * <p>对账判定在 {@link LiveNodeRunner}，索引在 {@link LiveNodeLibrary}；这里只执行差异：
 * 拆掉不该有的、建出该有还没有的、把已有节点的非结构变化刷进去。</p>
 */
public final class LiveNodeApply {
    /** 每 tick 最多建几个、拆几个。 */
    public static final int MAX_APPLIES_PER_TICK = 128;

    private final MinecraftServer server;
    /** 工厂共享的端口缓存；所有节点共用同一份。 */
    private final PortExecutor ports;
    private final LiveNodeLibrary library;

    /** 待建节点；每次对账整体替换。 */
    private final ArrayDeque<Spec> createQueue = new ArrayDeque<>();
    /** 待拆节点 id；每次对账追加。 */
    private final List<UUID> destroyQueue = new ArrayList<>();
    /** 本轮到账的逐机器在线集合，建节点时用。 */
    private final Map<UUID, Set<UUID>> onlineByNode = new HashMap<>();

    /**
     * @param server  服务端实例
     * @param ports   工厂共享的端口缓存
     * @param library 活节点索引
     */
    public LiveNodeApply(MinecraftServer server, PortExecutor ports, LiveNodeLibrary library) {
        this.server = server;
        this.ports = ports;
        this.library = library;
    }

    /** 请求下一 tick 提前对账一次。 */
    public void requestReconcile() {
        this.library.requestReconcile();
    }

    /** 是否有人请求过提前对账。 */
    public boolean isReconcileRequested() {
        return this.library.isReconcileRequested();
    }

    /** 当前节点数。 */
    public int size() {
        return this.library.size();
    }

    /**
     * 按节点 id 取运行时节点。
     *
     * @param nodeId 节点 id
     * @return 节点；没建出来时为 {@code null}
     */
    @Nullable
    public LiveNode node(UUID nodeId) {
        return this.library.node(nodeId);
    }

    /**
     * 对账：算出差异并排队。
     *
     * @param specs          期望存在的节点
     * @param onlineMachines 节点 id → 在线机器 id 集合
     */
    public void reconcile(List<Spec> specs, Map<UUID, Set<UUID>> onlineMachines) {
        this.library.clearReconcileRequest();
        this.onlineByNode.clear();
        this.onlineByNode.putAll(onlineMachines);
        Map<UUID, Spec> wanted = new LinkedHashMap<>();
        for (Spec spec : specs) {
            wanted.put(spec.node().id(), spec);
        }

        this.destroyQueue.clear();
        for (LiveNode node : this.library.all()) {
            Spec spec = wanted.get(node.node().id());
            if (spec == null || !node.matches(spec)) {
                this.destroyQueue.add(node.node().id());
            }
        }

        this.createQueue.clear();
        for (Spec spec : wanted.values()) {
            if (!this.library.contains(spec.node().id())) {
                this.createQueue.add(spec);
            }
        }
        for (Spec spec : wanted.values()) {
            LiveNode node = this.library.node(spec.node().id());
            if (node != null) {
                try {
                    // 名字 / 规则等非结构变化不重建节点，但要刷新到运行时。
                    node.updateNode(spec.node());
                    node.setAvailable(spec.available());
                    node.setOnlineMachines(this.onlineByNode.getOrDefault(spec.node().id(),
                            Set.of()));
                } catch (RuntimeException e) {
                    node.logThrottled(this.server.overworld().getGameTime(), "更新节点异常：" + e);
                }
            }
        }
    }

    /** 每 tick 推进：先拆、再建，各按预算。 */
    public void tick() {
        long now = this.server.overworld().getGameTime();
        this.destroyQueued(now);
        this.createQueued(now);
    }

    /**
     * 拆掉一个节点的运行时，并把待投递队列还回网络；节点删除走这里。
     *
     * @param nodeId 节点 id
     */
    public void removeNode(UUID nodeId) {
        LiveNode node = this.library.removeRaw(nodeId);
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
     * @param nodeId 节点 id
     */
    public void markPatternsDirty(UUID nodeId) {
        LiveNode node = this.library.node(nodeId);
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
        LiveNode node = this.library.node(nodeId);
        if (node != null) {
            node.refreshName();
        }
    }

    /**
     * 更新一个节点的可用性。
     *
     * @param nodeId    节点 id
     * @param available 现在是否可用
     */
    public void setAvailable(UUID nodeId, boolean available) {
        LiveNode node = this.library.node(nodeId);
        if (node != null) {
            node.setAvailable(available);
        }
    }

    /** 停机：拆掉全部节点，不碰存档数据。 */
    public void destroyAll() {
        this.createQueue.clear();
        this.destroyQueue.clear();
        for (LiveNode node : this.library.drain()) {
            try {
                node.shutdown();
            } catch (RuntimeException e) {
                VoidFactory.LOGGER.warn("AE2 Void Factory: 停机拆节点失败 [{}]：{}",
                        node.node().id(), e.toString());
            }
        }
    }

    /**
     * 节点告警出口：节点的 Consumer 只有 message，这里补上工厂与节点 id。
     *
     * @return 告警消费者
     */
    private java.util.function.Consumer<io.github.crafteve.ae2vf.runtime.LiveNodeLibrary.Notice> noticeSink() {
        return notice -> this.library.report(notice.factoryId(), notice.nodeId(),
                notice.message());
    }

    private void destroyQueued(long now) {
        int budget = MAX_APPLIES_PER_TICK;
        while (budget > 0 && !this.destroyQueue.isEmpty()) {
            budget--;
            UUID nodeId = this.destroyQueue.remove(0);
            LiveNode node = this.library.removeRaw(nodeId);
            if (node == null) {
                continue;
            }
            try {
                node.detach();
            } catch (RuntimeException e) {
                node.logThrottled(now, "拆节点异常：" + e);
            }
        }
    }

    private void createQueued(long now) {
        int budget = MAX_APPLIES_PER_TICK;
        while (budget > 0 && !this.createQueue.isEmpty()) {
            budget--;
            Spec spec = this.createQueue.poll();
            if (this.library.contains(spec.node().id())) {
                continue;
            }
            LiveNode node;
            try {
                node = new LiveNode(this.server, spec.factoryId(), spec.node(), spec.anchor(),
                        spec.anchorLevel(), spec.available(), this.ports, this.noticeSink());
            } catch (RuntimeException e) {
                VoidFactory.LOGGER.warn("AE2 Void Factory: 构造节点失败 [{}]：{}",
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
            this.library.add(node);
        }
    }

}
