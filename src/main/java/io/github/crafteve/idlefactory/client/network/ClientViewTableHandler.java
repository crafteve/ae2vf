package io.github.crafteve.idlefactory.client.network;

import io.github.crafteve.idlefactory.client.screen.ViewTableSink;
import io.github.crafteve.idlefactory.network.payload.ViewTableDiffS2C;
import io.github.crafteve.idlefactory.network.payload.ViewTableS2C;

import net.minecraft.client.Minecraft;

/**
 * 视图表报文的客户端入口：把报文投给当前屏幕，不做业务判断。
 *
 * <p>屏幕不是 {@link ViewTableSink} 或容器 id 对不上（界面已关、报文过期）就丢弃。分页拼装
 * 与差量应用在屏幕持有的缓冲里完成，本类不保存任何状态。</p>
 */
public final class ClientViewTableHandler {
    private ClientViewTableHandler() {
        // 纯静态投递
    }

    /**
     * 处理一页全量。
     *
     * @param payload 负载
     */
    public static void accept(ViewTableS2C payload) {
        if (Minecraft.getInstance().screen instanceof ViewTableSink sink
                && sink.containerId() == payload.containerId()) {
            sink.acceptFullPage(payload.containerId(), payload.revision(), payload.pageIndex(),
                    payload.pageCount(), payload.page());
        }
    }

    /**
     * 处理一份差量。
     *
     * @param payload 负载
     */
    public static void accept(ViewTableDiffS2C payload) {
        if (Minecraft.getInstance().screen instanceof ViewTableSink sink
                && sink.containerId() == payload.containerId()) {
            sink.acceptDiff(payload.containerId(), payload.diff());
        }
    }
}
