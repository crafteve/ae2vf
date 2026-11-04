package io.github.crafteve.idlefactory.client.screen;

import io.github.crafteve.idlefactory.network.payload.FactoryEditFeedbackS2C;

/**
 * 服务端编辑反馈的最小接收口。
 *
 * <p>被动 IO 终端、节点终端与旧工作台都要收 {@link FactoryEditFeedbackS2C}，但它们的表格
 * 负载不同。把反馈拆成这个父接口，{@code client.network} 只按它投递，不 import 具体屏幕。</p>
 */
public interface FactoryFeedbackSink {
    /**
     * @return 本屏幕所属菜单的容器 id
     */
    int containerId();

    /**
     * 收到编辑动作的服务端反馈。
     *
     * @param payload 反馈
     */
    void acceptEditFeedback(FactoryEditFeedbackS2C payload);
}
