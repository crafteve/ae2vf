package io.github.crafteve.idlefactory.client.network;

import io.github.crafteve.idlefactory.client.screen.FactoryTableSink;
import io.github.crafteve.idlefactory.network.payload.FactoryTableS2C;

import net.minecraft.client.Minecraft;

/**
 * 工厂只读数据的客户端入口：机器表。
 *
 * <p>两者都按接口投递（{@link FactoryTableSink} / {@link FactoryFeedbackSink}），
 * 不列举具体屏幕。</p>
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
        FactoryTableSink sink = currentSink(payload.containerId());
        if (sink != null) {
            sink.acceptTable(payload);
        }
    }

    /**
     * 当前打开且容器 id 匹配的工厂界面。
     *
     * <p>只按接口判断，不在这里列举具体屏幕；新增工厂界面只要实现 {@link FactoryTableSink}
     * 就能收到数据。</p>
     *
     * @param containerId 容器 id
     * @return 接收口；没有匹配的界面时为 {@code null}
     */
    private static FactoryTableSink currentSink(int containerId) {
        if (Minecraft.getInstance().screen instanceof FactoryTableSink sink
                && sink.containerId() == containerId) {
            return sink;
        }
        return null;
    }
}
