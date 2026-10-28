package io.github.crafteve.idlefactory.client.network;

import io.github.crafteve.idlefactory.client.ClientFactoryLinks;
import io.github.crafteve.idlefactory.network.payload.FactoryLinksS2C;

import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端负载处理：把链接快照写进 {@link ClientFactoryLinks}。
 */
public final class ClientFactoryLinksHandler {
    private ClientFactoryLinksHandler() {
        // 纯静态处理器
    }

    /**
     * 在客户端主线程更新缓存。
     *
     * @param payload 同步包
     * @param context 上下文
     */
    public static void handle(FactoryLinksS2C payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientFactoryLinks.update(payload));
    }
}
