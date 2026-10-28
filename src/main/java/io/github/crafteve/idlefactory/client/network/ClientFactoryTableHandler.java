package io.github.crafteve.idlefactory.client.network;

import io.github.crafteve.idlefactory.client.screen.FactoryScreen;
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
        }
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
