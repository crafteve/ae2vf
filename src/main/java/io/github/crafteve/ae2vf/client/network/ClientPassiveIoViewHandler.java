package io.github.crafteve.ae2vf.client.network;

import io.github.crafteve.ae2vf.client.screen.terminal.passiveio.PassiveIoScreen;
import io.github.crafteve.ae2vf.network.payload.FactoryPassiveIoViewS2C;

import net.minecraft.client.Minecraft;

/**
 * 被动 IO 视图同步的客户端入口：只认当前打开的被动 IO 屏幕与匹配的容器 id。
 */
public final class ClientPassiveIoViewHandler {
    private ClientPassiveIoViewHandler() {
        // 纯静态处理器
    }

    /**
     * 把服务端确认的视图交给当前屏幕。
     *
     * @param payload 负载
     */
    public static void handle(FactoryPassiveIoViewS2C payload) {
        if (Minecraft.getInstance().screen instanceof PassiveIoScreen screen
                && screen.containerId() == payload.containerId()) {
            screen.acceptView(payload.view());
        }
    }
}
