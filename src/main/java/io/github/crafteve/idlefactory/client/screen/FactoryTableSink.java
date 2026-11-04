package io.github.crafteve.idlefactory.client.screen;

import io.github.crafteve.idlefactory.network.payload.FactoryTableS2C;

/**
 * 只读工厂机器表的客户端接收口。
 *
 * <p>机器表原本只认旧工作台（{@code FactoryScreen}），第三版被动 IO 终端需要同一份数据，所以
 * 在 {@code client.screen} 定一个两屏共用的接口：{@code client.network} 只按接口投递，不再
 * import 具体屏幕类。编辑反馈由父接口 {@link FactoryFeedbackSink} 统一收。</p>
 *
 * <p>实现方必须保证 {@link #containerId()} 与负载里的容器 id 一致才接收；接口不负责过滤，
 * 过滤在 {@code ClientFactoryTableHandler} 一处做。</p>
 */
public interface FactoryTableSink extends FactoryFeedbackSink {
    /**
     * 收到服务端机器表。
     *
     * @param payload 机器表快照
     */
    void acceptTable(FactoryTableS2C payload);
}
