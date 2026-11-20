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
     * <p>节点终端已改用视图表通道，旧节点表服务端不再主动发；这里保留空实现只为让旧报文的
     * 注册点在第 3c 步一起删除。</p>
     *
     * @param payload 负载
     */
    public static void handle(FactoryNodeTableS2C payload) {
        // 旧读通道已停用，第 3c 步随报文一起删除。
    }
}
