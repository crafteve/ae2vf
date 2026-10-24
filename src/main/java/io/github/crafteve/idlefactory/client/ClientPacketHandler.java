package io.github.crafteve.idlefactory.client;

import io.github.crafteve.idlefactory.client.screen.FactoryScreen;
import io.github.crafteve.idlefactory.network.payload.StorageSnapshotS2C;

import net.minecraft.client.Minecraft;

/**
 * 客户端包分发。
 *
 * <p>只在物理客户端加载。保留一份最新的库存快照，给「界面还没创建时包就到了」这种时序兜底：
 * 界面在 {@code init()} 里先读缓存，随后到达的包再直接推给屏幕。</p>
 */
public final class ClientPacketHandler {
    private static StorageSnapshotS2C snapshot = StorageSnapshotS2C.empty();

    private ClientPacketHandler() {
        // 纯静态工具类
    }

    /** 收到服务端库存快照。 */
    public static void handleStorageSnapshot(StorageSnapshotS2C payload) {
        snapshot = payload;
        if (Minecraft.getInstance().screen instanceof FactoryScreen screen) {
            screen.applySnapshot(payload);
        }
    }

    /** 当前缓存的最新快照。 */
    public static StorageSnapshotS2C snapshot() {
        return snapshot;
    }
}
