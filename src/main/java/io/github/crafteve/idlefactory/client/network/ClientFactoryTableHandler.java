package io.github.crafteve.idlefactory.client.network;

import io.github.crafteve.idlefactory.client.BindingDraftController;
import io.github.crafteve.idlefactory.client.screen.FactoryFeedbackSink;
import io.github.crafteve.idlefactory.client.screen.FactoryTableSink;
import io.github.crafteve.idlefactory.network.payload.FactoryEditFeedbackS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTableS2C;

import net.minecraft.client.Minecraft;

/**
 * 工厂只读数据的客户端入口：机器表与编辑反馈。
 *
 * <p>两者都按接口投递（{@link FactoryTableSink} / {@link FactoryFeedbackSink}），
 * 不列举具体屏幕；仓库摘要曾只服务旧工作台，随工作台一起删除。</p>
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
        // 即使界面没开也记住方块 → 节点，供世界里的蹲下右键编辑已有节点用。
        for (var machine : payload.machines()) {
            BindingDraftController.rememberNode(machine.primaryBlock(), machine.nodeId());
        }
        FactoryTableSink sink = currentSink(payload.containerId());
        if (sink != null) {
            sink.acceptTable(payload);
        }
    }

    /**
     * 处理编辑动作的反馈。
     *
     * @param payload 负载
     */
    public static void handleFeedback(FactoryEditFeedbackS2C payload) {
        FactoryFeedbackSink sink = currentFeedbackSink(payload.containerId());
        if (sink != null) {
            sink.acceptEditFeedback(payload);
        }
    }

    /**
     * 当前打开且容器 id 匹配的反馈接收口。
     *
     * <p>被动 IO 终端、节点终端与旧工作台都实现 {@link FactoryFeedbackSink}；这里不列举具体屏幕。</p>
     *
     * @param containerId 容器 id
     * @return 接收口；没有匹配的界面时为 {@code null}
     */
    private static FactoryFeedbackSink currentFeedbackSink(int containerId) {
        if (Minecraft.getInstance().screen instanceof FactoryFeedbackSink sink
                && sink.containerId() == containerId) {
            return sink;
        }
        return null;
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
