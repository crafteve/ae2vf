package io.github.crafteve.idlefactory.client.screen;

import io.github.crafteve.idlefactory.network.payload.FactoryEditFeedbackS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTableS2C;

/**
 * 只读工厂数据的客户端接收口。
 *
 * <p>机器表与编辑反馈原本只认旧工作台（{@code FactoryScreen}），第三版被动 IO 终端需要同一份
 * 数据，所以在 {@code client.screen} 定一个两屏共用的接口：{@code client.network} 只按接口
 * 投递，不再 import 具体屏幕类。仓库快照只有旧工作台用，仍直接投给 {@code FactoryScreen}。</p>
 *
 * <p>实现方必须保证 {@link #containerId()} 与负载里的容器 id 一致才接收；接口不负责过滤，
 * 过滤在 {@code ClientFactoryTableHandler} 一处做。</p>
 */
public interface FactoryTableSink {
    /**
     * @return 本屏幕所属菜单的容器 id
     */
    int containerId();

    /**
     * 收到服务端机器表。
     *
     * @param payload 机器表快照
     */
    void acceptTable(FactoryTableS2C payload);

    /**
     * 收到编辑动作的服务端反馈。
     *
     * @param payload 反馈
     */
    void acceptEditFeedback(FactoryEditFeedbackS2C payload);
}
