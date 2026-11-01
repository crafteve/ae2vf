package io.github.crafteve.idlefactory.client.network;

import io.github.crafteve.idlefactory.client.screen.FactoryScreen;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryTerminalDisplay;
import io.github.crafteve.idlefactory.network.payload.FactoryEditFeedbackS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryStorageS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTableS2C;

import net.minecraft.client.Minecraft;

/**
 * 工厂只读数据的客户端入口：机器表与仓库摘要都只交给当前打开的工作台。
 */
public final class ClientFactoryTableHandler {
    private ClientFactoryTableHandler() {
        // 纯静态工具类
    }

    /**
     * 处理机器表。
     *
     * @param payload 负载
     */
    public static void handle(FactoryTableS2C payload) {
        FactoryScreen screen = currentScreen(payload.containerId());
        if (screen != null) {
            screen.acceptTable(payload);
            return;
        }
        FactoryTerminalDisplay display = currentTerminal(payload.containerId());
        if (display != null) {
            display.acceptTable(payload);
        }
    }

    /**
     * 处理仓库摘要。
     *
     * @param payload 负载
     */
    public static void handleStorage(FactoryStorageS2C payload) {
        FactoryScreen screen = currentScreen(payload.containerId());
        if (screen != null) {
            screen.acceptStorage(payload);
        }
    }

    /**
     * 处理编辑动作的反馈。
     *
     * @param payload 负载
     */
    public static void handleFeedback(FactoryEditFeedbackS2C payload) {
        FactoryScreen screen = currentScreen(payload.containerId());
        if (screen != null) {
            screen.acceptEditFeedback(payload);
            return;
        }
        FactoryTerminalDisplay display = currentTerminal(payload.containerId());
        if (display != null) {
            display.acceptEditFeedback(payload);
        }
    }

    /**
     * 当前打开且容器 id 匹配的工厂终端屏幕。
     *
     * @param containerId 容器 id
     * @return 屏幕；不匹配时为 {@code null}
     */
    private static FactoryTerminalDisplay currentTerminal(int containerId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof FactoryTerminalDisplay display
                && display.containerId() == containerId) {
            return display;
        }
        return null;
    }

    private static FactoryScreen currentScreen(int containerId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof FactoryScreen screen)) {
            return null;
        }
        if (screen.getMenu().containerId != containerId) {
            return null;
        }
        return screen;
    }
}
