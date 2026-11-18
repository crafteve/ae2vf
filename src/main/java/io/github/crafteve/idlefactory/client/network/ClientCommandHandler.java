package io.github.crafteve.idlefactory.client.network;

import io.github.crafteve.idlefactory.client.screen.FactoryFeedbackSink;
import io.github.crafteve.idlefactory.network.payload.FactoryCommandS2C;

import net.minecraft.client.Minecraft;

/**
 * 写命令回执的客户端入口：只投给容器 id 匹配的当前屏幕。
 */
public final class ClientCommandHandler {
    private ClientCommandHandler() {
        // 纯静态工具类
    }

    /**
     * 处理一次回执。
     *
     * @param payload 负载
     */
    public static void handle(FactoryCommandS2C payload) {
        if (Minecraft.getInstance().screen instanceof FactoryFeedbackSink sink
                && sink.containerId() == payload.containerId()) {
            sink.acceptCommandResult(payload.result());
        }
    }
}
