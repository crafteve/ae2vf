package io.github.crafteve.idlefactory.client.network;

import io.github.crafteve.idlefactory.client.wand.WandLinks;
import io.github.crafteve.idlefactory.network.payload.FactoryLinksS2C;

import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端负载处理：把链接快照整体写进 {@link WandLinks}。
 */
public final class ClientFactoryLinksHandler {
    private ClientFactoryLinksHandler() {
        // 纯静态处理器
    }

    /**
     * 在客户端主线程整体替换缓存。
     *
     * @param payload 同步包
     * @param context 上下文
     */
    public static void handle(FactoryLinksS2C payload, IPayloadContext context) {
        context.enqueueWork(() -> WandLinks.update(payload));
    }
}
