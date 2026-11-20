package io.github.crafteve.idlefactory.network;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryLibrary;
import io.github.crafteve.idlefactory.machine.Machine;
import io.github.crafteve.idlefactory.observation.Health;
import io.github.crafteve.idlefactory.observation.ObservationChange;
import io.github.crafteve.idlefactory.observation.ObservationChangeListener;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 失联 / 恢复提示：监听观测变化，按工厂聚合，10 秒内同一工厂最多弹一次 actionbar。
 *
 * <p>聚合状态住在这里（network），view 不再持有一份。一次爆炸拆掉 20 台机器，玩家也只看
 * 到一行；拥有者不在线时计数直接丢弃——通知没有接收者，留着只会越攒越旧。</p>
 */
public final class ObservationNoticeListener implements ObservationChangeListener {
    private final MinecraftServer server;
    private final FactoryLibrary manager;
    /** 本 tick 累计的「失联」与「恢复」数量，按工厂分组；tick 末一次性发出。 */
    private final Map<UUID, Integer> missingNotices = new HashMap<>();
    private final Map<UUID, Integer> restoredNotices = new HashMap<>();

    /**
     * @param server  服务端
     * @param manager 工厂档案柜
     */
    public ObservationNoticeListener(MinecraftServer server, FactoryLibrary manager) {
        this.server = server;
        this.manager = manager;
    }

    @Override
    public void onObservationChanged(ObservationChange change) {
        for (ObservationChange.BlockChange blockChange : change.changes()) {
            for (Factory factory : this.manager.all()) {
                Optional<Machine> machine = factory.config().nodes().machineAt(blockChange.key());
                if (machine.isEmpty()) {
                    continue;
                }
                switch (blockChange.to()) {
                    case MISSING, NO_DIMENSION -> this.missingNotices.merge(factory.id(), 1,
                            Integer::sum);
                    case PRESENT -> {
                        if (blockChange.from() != Health.UNKNOWN) {
                            this.restoredNotices.merge(factory.id(), 1, Integer::sum);
                        }
                    }
                    default -> {
                        // UNLOADED / UNKNOWN 不通知
                    }
                }
            }
        }
    }

    /**
     * 把本 tick 累计的数量弹成 actionbar；节流期间计数保留，攒到下一次。
     */
    public void tick() {
        if (this.missingNotices.isEmpty() && this.restoredNotices.isEmpty()) {
            return;
        }
        Set<UUID> factoryIds = new HashSet<>();
        factoryIds.addAll(this.missingNotices.keySet());
        factoryIds.addAll(this.restoredNotices.keySet());
        for (UUID factoryId : factoryIds) {
            Factory factory = this.manager.byId(factoryId).orElse(null);
            ServerPlayer player = factory == null ? null
                    : this.server.getPlayerList().getPlayer(factory.config().owner());
            int missing = this.missingNotices.getOrDefault(factoryId, 0);
            int restored = this.restoredNotices.getOrDefault(factoryId, 0);
            this.missingNotices.remove(factoryId);
            this.restoredNotices.remove(factoryId);
            if (player == null) {
                // 拥有者不在线：计数直接丢弃，留着只会越攒越旧。
                continue;
            }
            if (missing > 0) {
                // 走模组 HUD 条：和绑定杖、ME 杖、定位提示同一种底板，不用原版动作栏。
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                        new io.github.crafteve.idlefactory.network.payload.HudNoticeS2C(
                                net.minecraft.network.chat.Component.literal(missing + " 台机器失联"),
                                io.github.crafteve.idlefactory.network.payload.HudNoticeS2C.Kind.FAILURE));
            } else if (restored > 0) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                        new io.github.crafteve.idlefactory.network.payload.HudNoticeS2C(
                                net.minecraft.network.chat.Component.literal(restored + " 台机器回来了"),
                                io.github.crafteve.idlefactory.network.payload.HudNoticeS2C.Kind.SUCCESS));
            }
        }
    }

    private static boolean ready(Map<UUID, Long> ticks, UUID factoryId, long now, int interval) {
        Long last = ticks.get(factoryId);
        return last == null || now - last >= interval;
    }

    /** 清空全部计数（服务器停止时）。 */
    public void clear() {
        this.missingNotices.clear();
        this.restoredNotices.clear();

    }
}
