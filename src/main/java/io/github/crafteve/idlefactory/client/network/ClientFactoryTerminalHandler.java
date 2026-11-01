package io.github.crafteve.idlefactory.client.network;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.client.screen.terminal.FactoryTerminalDisplay;
import io.github.crafteve.idlefactory.network.payload.FactoryTerminalTargetS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTerminalTargetsS2C;

import net.minecraft.client.Minecraft;

/**
 * 主动样板负载的客户端入口：只交给当前打开的终端屏幕。
 *
 * <p>容器 id 对不上或当前界面不是终端时直接丢弃，避免过期包改写新界面的状态。</p>
 */
public final class ClientFactoryTerminalHandler {
    private ClientFactoryTerminalHandler() {
        // 纯静态工具类
    }

    /**
     * 处理目标列表。
     *
     * @param payload 负载
     */
    public static void handleTargets(FactoryTerminalTargetsS2C payload) {
        FactoryTerminalDisplay display = display(payload.containerId());
        if (display != null) {
            display.acceptTargets(payload);
        }
    }

    /**
     * 处理单个目标详情。
     *
     * @param payload 负载
     */
    public static void handleTarget(FactoryTerminalTargetS2C payload) {
        FactoryTerminalDisplay display = display(payload.containerId());
        if (display != null) {
            display.acceptTargetDetail(payload);
        }
    }

    /**
     * 找到当前打开且容器 id 匹配的终端屏幕。
     *
     * @param containerId 容器 id
     * @return 屏幕；不匹配时为 {@code null}
     */
    @Nullable
    private static FactoryTerminalDisplay display(int containerId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof FactoryTerminalDisplay display
                && display.containerId() == containerId) {
            return display;
        }
        return null;
    }
}
