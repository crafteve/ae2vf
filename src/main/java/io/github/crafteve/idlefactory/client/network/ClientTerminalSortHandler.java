package io.github.crafteve.idlefactory.client.network;

import io.github.crafteve.idlefactory.client.screen.terminal.node.NodeScreen;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryPassiveIoScreen;
import io.github.crafteve.idlefactory.network.payload.FactoryTerminalSortS2C;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * 终端排序同步的客户端入口：只认当前打开的那个终端与匹配的容器 id。
 *
 * <p>两个终端共用一套排序设置包，所以这里按屏幕类型分发；容器 id 不匹配就丢弃，避免旧界面的
 * 迟到回执改掉新界面的排序。</p>
 */
public final class ClientTerminalSortHandler {
    private ClientTerminalSortHandler() {
        // 纯静态处理器
    }

    /**
     * 把服务端确认的排序交回当前屏幕。
     *
     * @param payload 负载
     */
    public static void handle(FactoryTerminalSortS2C payload) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen instanceof NodeScreen node
                && node.containerId() == payload.containerId()) {
            node.acceptSort(payload.mode(), payload.direction());
        } else if (screen instanceof FactoryPassiveIoScreen passive
                && passive.containerId() == payload.containerId()) {
            passive.acceptSort(payload.mode(), payload.direction());
        }
    }
}
