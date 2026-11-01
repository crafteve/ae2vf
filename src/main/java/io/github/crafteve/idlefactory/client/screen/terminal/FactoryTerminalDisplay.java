package io.github.crafteve.idlefactory.client.screen.terminal;

import io.github.crafteve.idlefactory.network.payload.FactoryEditFeedbackS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTableS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTerminalTargetS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTerminalTargetsS2C;

/**
 * 工厂终端两个视图共用的接收面。
 *
 * <p>客户端处理器只认这个接口，不认具体屏幕类：合成视图与样板视图各自实现它，
 * 左面板因此可以按视图各自维护状态，而网络层不需要知道当前是哪一个。</p>
 */
public interface FactoryTerminalDisplay {
    /** 当前菜单容器 id。 */
    int containerId();

    /**
     * 收机器表。
     *
     * @param payload 机器表
     */
    void acceptTable(FactoryTableS2C payload);

    /**
     * 收编辑反馈。
     *
     * @param payload 反馈
     */
    void acceptEditFeedback(FactoryEditFeedbackS2C payload);

    /**
     * 收主动目标列表。
     *
     * @param payload 目标列表
     */
    void acceptTargets(FactoryTerminalTargetsS2C payload);

    /**
     * 收单个主动目标详情。
     *
     * @param payload 目标详情
     */
    void acceptTargetDetail(FactoryTerminalTargetS2C payload);
}
