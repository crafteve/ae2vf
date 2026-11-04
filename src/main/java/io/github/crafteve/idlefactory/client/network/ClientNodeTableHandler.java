package io.github.crafteve.idlefactory.client.network;

import io.github.crafteve.idlefactory.client.screen.terminal.FactoryNodeScreen;
import io.github.crafteve.idlefactory.network.payload.FactoryNodeTableS2C;

import net.minecraft.client.Minecraft;

/**
 * 节点表的客户端入口。
 *
 * <p>只投给当前打开的节点终端，并按容器 id 过滤过期界面。被动 IO 终端与旧工作台不接触节点表。</p>
 */
public final class ClientNodeTableHandler {
    private ClientNodeTableHandler() {
        // 纯静态工具类
    }

    /**
     * 处理节点表。
     *
     * @param payload 负载
     */
    public static void handle(FactoryNodeTableS2C payload) {
        if (Minecraft.getInstance().screen instanceof FactoryNodeScreen screen
                && screen.containerId() == payload.containerId()) {
            screen.acceptNodeTable(payload);
        }
    }
}
