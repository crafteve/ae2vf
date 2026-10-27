package io.github.crafteve.idlefactory.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
import io.github.crafteve.idlefactory.network.payload.FactoryStorageS2C;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 仓库快照回包的唯一出口。
 *
 * <p>请求、动作完成、背包 Shift 进仓库都从这里发同一份结构，避免三处各自排序、截断。</p>
 */
public final class FactoryStorageSnapshots {
    /** 单次最多回多少条。 */
    private static final int MAX_ENTRIES = 1024;

    private FactoryStorageSnapshots() {
        // 纯静态工具类
    }

    /**
     * 立即把当前仓库快照发给玩家。
     *
     * @param player      目标玩家
     * @param factory     工厂
     * @param containerId 菜单容器 id
     */
    public static void send(ServerPlayer player, Factory factory, int containerId) {
        Map<ResourceKey, Long> snapshot;
        try {
            snapshot = factory.storage().snapshot();
        } catch (RuntimeException e) {
            IdleFactory.LOGGER.warn("IdleFactory storage snapshot failed: factory={}",
                    factory.id(), e);
            PacketDistributor.sendToPlayer(player, FactoryStorageS2C.empty(containerId));
            return;
        }
        List<Map.Entry<ResourceKey, Long>> sorted = new ArrayList<>(snapshot.entrySet());
        sorted.sort(Map.Entry.<ResourceKey, Long>comparingByValue().reversed());
        int limit = Math.min(sorted.size(), MAX_ENTRIES);
        List<FactoryStorageS2C.Entry> entries = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            Map.Entry<ResourceKey, Long> entry = sorted.get(i);
            entries.add(new FactoryStorageS2C.Entry(entry.getKey(), entry.getValue()));
        }
        PacketDistributor.sendToPlayer(player, new FactoryStorageS2C(
                containerId, true, sorted.size() > MAX_ENTRIES, entries));
    }
}
