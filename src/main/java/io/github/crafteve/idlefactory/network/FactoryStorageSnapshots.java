package io.github.crafteve.idlefactory.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.Factory;
import appeng.api.stacks.AEKey;
import io.github.crafteve.idlefactory.network.payload.FactoryStorageS2C;

import io.netty.buffer.Unpooled;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.connection.ConnectionType;

/**
 * 仓库快照回包的唯一出口。
 *
 * <p>请求、动作完成、背包 Shift 进仓库都从这里发同一份结构，避免三处各自排序、截断。</p>
 *
 * <p>物品 key 带完整组件，单条体积可能很大。这里按 {@link #MAX_PACKET_BYTES} 把快照拆成
 * 多个 {@link FactoryStorageS2C}（{@code part} / {@code last}），客户端按序拼装。
 * 分片前先试编码一次：既量长度，也把「某个模组组件无法序列化」的单条降级为跳过，
 * 而不是让整包编码时抛异常把玩家踢下线。</p>
 */
public final class FactoryStorageSnapshots {
    /** 单次最多回多少条。 */
    private static final int MAX_ENTRIES = 1024;
    /** 单个分片的最大未压缩字节数，与 AE2 库存更新包同一量级。 */
    private static final int MAX_PACKET_BYTES = 512 * 1024;

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
        Map<AEKey, Long> snapshot;
        try {
            snapshot = factory.live().storage().snapshot();
        } catch (RuntimeException e) {
            IdleFactory.LOGGER.warn("IdleFactory storage snapshot failed: factory={}",
                    factory.id(), e);
            PacketDistributor.sendToPlayer(player, FactoryStorageS2C.empty(containerId));
            return;
        }
        List<Map.Entry<AEKey, Long>> sorted = new ArrayList<>(snapshot.entrySet());
        sorted.sort(Map.Entry.<AEKey, Long>comparingByValue().reversed());
        int limit = Math.min(sorted.size(), MAX_ENTRIES);
        boolean truncated = sorted.size() > MAX_ENTRIES;

        RegistryFriendlyByteBuf probe = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                player.level().registryAccess(), ConnectionType.NEOFORGE);
        List<FactoryStorageS2C.Entry> chunk = new ArrayList<>();
        int bytes = 0;
        int part = 0;
        for (int i = 0; i < limit; i++) {
            Map.Entry<AEKey, Long> entry = sorted.get(i);
            int size = encodedSize(probe, entry.getKey(), entry.getValue());
            if (size < 0) {
                truncated = true;
                continue;
            }
            if (!chunk.isEmpty() && bytes + size > MAX_PACKET_BYTES) {
                PacketDistributor.sendToPlayer(player, new FactoryStorageS2C(
                        containerId, true, truncated, part++, false, chunk));
                chunk = new ArrayList<>();
                bytes = 0;
            }
            chunk.add(new FactoryStorageS2C.Entry(entry.getKey(), entry.getValue()));
            bytes += size;
        }
        // 最后一片即使为空也要发：客户端靠 last 决定何时替换视图。
        PacketDistributor.sendToPlayer(player, new FactoryStorageS2C(
                containerId, true, truncated, part, true, chunk));
    }

    /**
     * 估算一条记录编码后的字节数。
     *
     * @return 字节数；该条无法序列化时返回 {@code -1}（调用方跳过）
     */
    private static int encodedSize(RegistryFriendlyByteBuf probe, AEKey key, long count) {
        probe.clear();
        try {
            ResourceKeyCodec.write(probe, key);
            probe.writeLong(count);
            return probe.readableBytes();
        } catch (RuntimeException e) {
            IdleFactory.LOGGER.warn("IdleFactory: 仓库条目无法编码，已跳过 {}", key.getDisplayName().getString(), e);
            return -1;
        }
    }
}
