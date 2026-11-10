package io.github.crafteve.idlefactory.factory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 失联 / 恢复通知的聚合器。
 *
 * <p>观测迁移逐条进入 {@link FactoryObservationRunner}，这里只负责计数：按工厂累计，
 * 10 秒内同一工厂最多弹一次 actionbar。一次爆炸拆掉 20 台机器，玩家也只应该看到一行。</p>
 *
 * <p>拥有者不在线时计数直接丢弃——通知没有接收者，留着只会越攒越旧。</p>
 */
final class FactoryNoticeHandler {
    /** 同一种失联/恢复通知的最短间隔（tick）：10 秒。 */
    private static final int NOTICE_INTERVAL = 200;

    /** 本 tick 累计的「失联」与「恢复」数量，按工厂分组；actionbar 用。 */
    private final Map<UUID, Integer> missingNotices = new HashMap<>();
    private final Map<UUID, Integer> restoredNotices = new HashMap<>();
    /** 每个工厂上一次弹 actionbar 的时刻，用来节流。 */
    private final Map<UUID, Long> lastNoticeTick = new HashMap<>();

    /**
     * 记一次失联。
     *
     * @param factoryId 工厂 id
     */
    void missing(UUID factoryId) {
        this.missingNotices.merge(factoryId, 1, Integer::sum);
    }

    /**
     * 记一次恢复。
     *
     * @param factoryId 工厂 id
     */
    void restored(UUID factoryId) {
        this.restoredNotices.merge(factoryId, 1, Integer::sum);
    }

    /**
     * 把本 tick 累计的失联/恢复数量弹成 actionbar。
     *
     * <p>按工厂聚合，且 10 秒内只弹一次；节流期间计数保留，攒到下一次。</p>
     *
     * @param server  服务端
     * @param manager 工厂档案柜
     */
    void flush(MinecraftServer server, FactoryManager manager) {
        if (this.missingNotices.isEmpty() && this.restoredNotices.isEmpty()) {
            return;
        }
        long now = server.overworld().getGameTime();
        Set<UUID> factoryIds = new HashSet<>();
        factoryIds.addAll(this.missingNotices.keySet());
        factoryIds.addAll(this.restoredNotices.keySet());
        for (UUID factoryId : factoryIds) {
            Factory factory = manager.byId(factoryId).orElse(null);
            if (factory == null) {
                this.missingNotices.remove(factoryId);
                this.restoredNotices.remove(factoryId);
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(factory.config().owner());
            if (player == null) {
                // 拥有者不在线，通知没有意义，计数直接丢
                this.missingNotices.remove(factoryId);
                this.restoredNotices.remove(factoryId);
                continue;
            }
            Long last = this.lastNoticeTick.get(factoryId);
            if (last != null && now - last < NOTICE_INTERVAL) {
                // 节流：计数留着，攒到下一次
                continue;
            }
            int missing = this.missingNotices.getOrDefault(factoryId, 0);
            int restored = this.restoredNotices.getOrDefault(factoryId, 0);
            if (missing > 0) {
                player.displayClientMessage(FactoryMessages.warn(
                        missing + " 台机器失联"), true);
            } else if (restored > 0) {
                player.displayClientMessage(FactoryMessages.ok(
                        restored + " 台机器回来了 · 规则已恢复"), true);
            }
            this.missingNotices.remove(factoryId);
            this.restoredNotices.remove(factoryId);
            this.lastNoticeTick.put(factoryId, now);
        }
    }

    /** 清空全部计数（服务器停止时）。 */
    void clear() {
        this.missingNotices.clear();
        this.restoredNotices.clear();
        this.lastNoticeTick.clear();
    }
}
