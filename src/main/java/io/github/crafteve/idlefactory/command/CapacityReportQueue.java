package io.github.crafteve.idlefactory.command;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import io.github.crafteve.idlefactory.factory.FactoryLifecycle;
import io.github.crafteve.idlefactory.runtime.LiveNode;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 容量诊断的延迟队列：活节点要到下一 tick 才建出来，绑定时立刻读只能读到空。
 *
 * <p>每条请求最多等 {@link #MAX_WAIT_TICKS} tick；节点还没建出来就回一行超时提示，
 * 免得玩家以为诊断没触发。只做诊断，不参与任何业务状态。</p>
 */
public final class CapacityReportQueue {
    /** 最多等多少 tick。 */
    private static final int MAX_WAIT_TICKS = 100;

    /** 一条待打印的请求。 */
    private record Pending(UUID playerId, UUID nodeId, long expireAt) {
    }

    private static final List<Pending> PENDING = new ArrayList<>();

    private CapacityReportQueue() {
        // 纯静态队列
    }

    /**
     * 排一条容量诊断；同一玩家同一节点重复请求会被合并。
     *
     * @param player 接收者
     * @param nodeId 节点 id
     */
    public static void schedule(ServerPlayer player, UUID nodeId) {
        for (Pending pending : PENDING) {
            if (pending.playerId().equals(player.getUUID()) && pending.nodeId().equals(nodeId)) {
                return;
            }
        }
        PENDING.add(new Pending(player.getUUID(), nodeId,
                player.serverLevel().getGameTime() + MAX_WAIT_TICKS));
        io.github.crafteve.idlefactory.IdleFactory.LOGGER.info(
                "容量探测：已排队 node={} player={}", nodeId, player.getName().getString());
    }

    /**
     * 每 tick 收口一次：节点建出来就打印，过期就回提示。
     *
     * @param runtime 服务端运行时
     */
    public static void flush(FactoryLifecycle runtime) {
        if (PENDING.isEmpty()) {
            return;
        }
        MinecraftServer server = runtime.server();
        long now = server.overworld() == null ? 0L : server.overworld().getGameTime();
        Iterator<Pending> it = PENDING.iterator();
        while (it.hasNext()) {
            Pending pending = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(pending.playerId());
            if (player == null) {
                it.remove();
                continue;
            }
            LiveNode live = runtime.nodeManager().node(pending.nodeId());
            if (live != null) {
                String report = live.capacityReport();
                io.github.crafteve.idlefactory.IdleFactory.LOGGER.info("容量探测：输出 node={}\n{}",
                        pending.nodeId(), report);
                player.displayClientMessage(Component.literal(report), false);
                it.remove();
                continue;
            }
            if (now >= pending.expireAt()) {
                io.github.crafteve.idlefactory.IdleFactory.LOGGER.info(
                        "容量探测：超时 node={}", pending.nodeId());
                player.displayClientMessage(
                        Component.literal("容量探测超时：节点还没建出来，稍后再切一次智能档"), false);
                it.remove();
            }
        }
    }

    /** 停服 / 换世界时清空。 */
    public static void clear() {
        PENDING.clear();
    }
}
