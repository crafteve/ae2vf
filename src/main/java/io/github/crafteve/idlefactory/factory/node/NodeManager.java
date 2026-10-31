package io.github.crafteve.idlefactory.factory.node;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.machine.Machine;

import appeng.api.networking.IGridNode;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 全部虚拟机器节点的对账表：该有的建出来，不该有的拆掉，其余每 tick 推一次。
 *
 * <p>它不认识 {@code Factory}，只吃运行时拼好的 {@link Spec}：这样节点层不反向依赖工厂层。
 * 建节点分帧（每 tick 上限 {@link #MAX_CREATES_PER_TICK}），因为几百个节点同时连进一个锚点
 * 会在一 tick 内触发同样多次网络重算。</p>
 */
public final class NodeManager {
    /** 每 tick 最多建几个节点。 */
    public static final int MAX_CREATES_PER_TICK = 128;

    private final MinecraftServer server;
    /** 存档数据变化回调。 */
    private final Runnable dataChanged;
    /** 节点告警出口。 */
    private final Consumer<Notice> notice;
    private final Map<UUID, MachineNode> nodes = new LinkedHashMap<>();
    /** 待建节点；每次对账整体替换。 */
    private final ArrayDeque<Spec> createQueue = new ArrayDeque<>();
    /** 工厂侧要求提前对账的标记。 */
    private boolean reconcileRequested;

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
     * @param machine     机器记录
     * @param anchor      工厂 ME 锚点节点
     * @param anchorLevel 锚点所在维度
     * @param available   机器方块此刻是否在线
     */
    public record Spec(UUID factoryId, Machine machine, IGridNode anchor, ServerLevel anchorLevel,
            boolean available) {
    }

    /**
     * 一条节点层告警，由运行时转进工厂的事件流。
     *
     * @param factoryId 所属工厂 id
     * @param machineId 机器 id
     * @param message   给玩家看的短句
     */
    public record Notice(UUID factoryId, UUID machineId, String message) {
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
     * 按机器 id 取节点。
     *
     * @param machineId 机器 id
     * @return 节点；没建出来时为 {@code null}
     */
    @Nullable
    public MachineNode node(UUID machineId) {
        return this.nodes.get(machineId);
    }

    /**
     * 对账：拆掉不该有的，排上该建的。
     *
     * <p>锚点节点实例变了就整台机器重建——旧节点还挂在旧网格上，没法就地迁移。</p>
     *
     * @param specs 期望存在的节点
     */
    public void reconcile(List<Spec> specs) {
        this.reconcileRequested = false;
        Map<UUID, Spec> wanted = new LinkedHashMap<>();
        for (Spec spec : specs) {
            wanted.put(spec.machine().id(), spec);
        }

        Iterator<Map.Entry<UUID, MachineNode>> iterator = this.nodes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, MachineNode> entry = iterator.next();
            Spec spec = wanted.get(entry.getKey());
            if (spec == null || !entry.getValue().matches(spec)) {
                entry.getValue().detach();
                iterator.remove();
            }
        }

        this.createQueue.clear();
        for (Spec spec : wanted.values()) {
            if (!this.nodes.containsKey(spec.machine().id())) {
                this.createQueue.add(spec);
            }
        }
        for (Spec spec : wanted.values()) {
            MachineNode node = this.nodes.get(spec.machine().id());
            if (node != null) {
                node.setAvailable(spec.available());
            }
        }
    }

    /** 每 tick 推一次：先补建节点，再让每个节点处理自己的脏数据。 */
    public void tick() {
        long now = this.server.overworld().getGameTime();
        this.createQueued(now);

        boolean changed = false;
        for (MachineNode node : this.nodes.values()) {
            try {
                changed |= node.tick(now);
            } catch (RuntimeException e) {
                // 单个节点炸掉不能影响别的机器，也不能把 tick 抛出去
                node.logThrottled(now, "节点 tick 异常：" + e);
            }
        }
        if (changed) {
            this.dataChanged.run();
        }
    }

    /**
     * 拆掉一台机器的节点；机器删除、删除工厂都走这里。
     *
     * @param machineId 机器 id
     */
    public void removeMachine(UUID machineId) {
        MachineNode node = this.nodes.remove(machineId);
        if (node != null) {
            node.detach();
        }
    }

    /**
     * 拆掉一个工厂的全部节点。
     *
     * @param factoryId 工厂 id
     */
    public void removeFactory(UUID factoryId) {
        Iterator<Map.Entry<UUID, MachineNode>> iterator = this.nodes.entrySet().iterator();
        while (iterator.hasNext()) {
            MachineNode node = iterator.next().getValue();
            if (node.factoryId().equals(factoryId)) {
                node.detach();
                iterator.remove();
            }
        }
    }

    /**
     * 更新一台机器的可用性；机器方块出现 / 消失时由观测迁移驱动。
     *
     * @param machineId 机器 id
     * @param available 现在是否可用
     */
    public void setAvailable(UUID machineId, boolean available) {
        MachineNode node = this.nodes.get(machineId);
        if (node != null) {
            node.setAvailable(available);
        }
    }

    /**
     * 发一条节点告警给运行时。
     *
     * @param factoryId 工厂 id
     * @param machineId 机器 id
     * @param message   文案
     */
    void report(UUID factoryId, UUID machineId, String message) {
        this.notice.accept(new Notice(factoryId, machineId, message));
    }

    /** 停机：拆掉全部节点，不碰存档数据。 */
    public void destroyAll() {
        this.createQueue.clear();
        List<MachineNode> remaining = new ArrayList<>(this.nodes.values());
        this.nodes.clear();
        for (MachineNode node : remaining) {
            try {
                node.shutdown();
            } catch (RuntimeException e) {
                IdleFactory.LOGGER.warn("Idle Factory: 停机拆节点失败 [{}]：{}",
                        node.machine().ref().shortId(), e.toString());
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
            if (this.nodes.containsKey(spec.machine().id())) {
                continue;
            }
            MachineNode node;
            try {
                node = new MachineNode(this.server, spec.factoryId(), spec.machine(),
                        spec.anchor(), spec.anchorLevel(), spec.available(), this.dataChanged);
            } catch (RuntimeException e) {
                IdleFactory.LOGGER.warn("Idle Factory: 构造机器节点失败 [{}]：{}",
                        spec.machine().ref().shortId(), e.toString());
                continue;
            }
            try {
                if (!node.attach(spec.anchor())) {
                    continue;
                }
            } catch (RuntimeException e) {
                node.logThrottled(now, "建节点异常：" + e);
                continue;
            }
            this.nodes.put(spec.machine().id(), node);
        }
    }
}
