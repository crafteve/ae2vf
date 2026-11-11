package io.github.crafteve.idlefactory.factory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.node.Node;
import io.github.crafteve.idlefactory.factory.node.NodeManager;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;
import io.github.crafteve.idlefactory.factory.node.NodeHealthResolver;
import io.github.crafteve.idlefactory.factory.probe.NodeHealth;
import io.github.crafteve.idlefactory.factory.storage.MeAnchor;
import io.github.crafteve.idlefactory.factory.storage.StorageMode;
import io.github.crafteve.idlefactory.integration.ae2.Ae2Binder;

import appeng.api.networking.IGridNode;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 虚拟节点的每 tick 推进：对账（该建的建、该拆的拆）与节点 tick。
 *
 * <p>对账只在运行时这一侧拼期望集合：节点层不认识工厂，也不需要知道仓库模式。锚点方块不在、
 * 区块没加载或没有 ME 节点时，这家工厂不贡献任何期望节点，已有节点会被拆掉；锚点回来后
 * 再按预算重建。</p>
 *
 * <p>两处异常都必须在本层吞掉：对账或节点 tick 炸了不能带崩整个服务器 tick，下一轮会把
 * 状态补齐。</p>
 */
final class FactoryNodeRunner {
    /** 虚拟节点对账间隔（tick）。 */
    private static final int NODE_RECONCILE_INTERVAL = 20;

    private final MinecraftServer server;
    private final FactoryManager manager;
    private final ObservationService observations;
    private final NodeManager nodeManager;

    /**
     * @param server       服务端
     * @param manager      工厂档案柜
     * @param observations 观测服务
     * @param nodeManager  虚拟节点管理器
     */
    FactoryNodeRunner(MinecraftServer server, FactoryManager manager,
            ObservationService observations, NodeManager nodeManager) {
        this.server = server;
        this.manager = manager;
        this.observations = observations;
        this.nodeManager = nodeManager;
    }

    /**
     * 推进一轮：到点或收到请求就对账，然后无条件 tick 一次节点。
     *
     * @param tickCounter 当前 tick 计数（从 1 开始）
     */
    void tick(int tickCounter) {
        if ((tickCounter - 1) % NODE_RECONCILE_INTERVAL == 0
                || this.nodeManager.isReconcileRequested()) {
            try {
                this.reconcile();
            } catch (RuntimeException e) {
                // 对账炸了不能让整个服务器 tick 跟着炸；下一轮对账会把状态补齐
                IdleFactory.LOGGER.warn("Idle Factory: 虚拟节点对账失败：{}", e.toString());
            }
        }
        try {
            this.nodeManager.tick();
        } catch (RuntimeException e) {
            IdleFactory.LOGGER.warn("Idle Factory: 虚拟节点 tick 失败：{}", e.toString());
        }
    }

    /**
     * 虚拟节点对账：把「ME 模式 + 锚点可解析」的工厂机器拼成期望集合。
     */
    private void reconcile() {
        List<NodeManager.Spec> specs = new ArrayList<>();
        Map<UUID, Set<UUID>> onlineMachines = new HashMap<>();
        for (Factory factory : this.manager.all()) {
            if (factory.config().storageMode() != StorageMode.ME) {
                continue;
            }
            if (!(factory.config().storageAnchor() instanceof MeAnchor anchor)) {
                continue;
            }
            ServerLevel level = this.server.getLevel(anchor.dimension());
            if (level == null || !level.isLoaded(anchor.pos())) {
                continue;
            }
            IGridNode anchorNode = Ae2Binder.nodeAt(level, anchor.pos());
            if (anchorNode == null) {
                continue;
            }
            long now = this.observations.currentTick();
            long staleTicks = FactoryLimitsConfig.staleHealthTicks();
            for (Node node : factory.config().nodes().all()) {
                // 健康只由 NodeHealthResolver 一处推导：这里不再自己逐方块判 PRESENT。
                NodeHealthResolver.Snapshot snapshot = NodeHealthResolver.resolve(node,
                        this.observations, now, staleTicks);
                boolean available = snapshot.health() != NodeHealth.OFFLINE;
                specs.add(new NodeManager.Spec(factory.id(), node, anchorNode, level, available));
                Set<UUID> online = new HashSet<>();
                for (NodeHealthResolver.MachineState state : snapshot.machines()) {
                    if (state.health().isOnline()) {
                        online.add(state.machine().id());
                    }
                }
                onlineMachines.put(node.id(), online);
            }
        }
        this.nodeManager.reconcile(specs, onlineMachines);
    }
}
