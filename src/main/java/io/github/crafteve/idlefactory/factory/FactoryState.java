package io.github.crafteve.idlefactory.factory;

/**
 * 工厂的运行状态。
 *
 * <p>注意：这里没有「网络断开」这个状态。仓库连不上时工厂不会进错误态，它只是表现为
 * {@link #PAUSED}——没料可吃、没处可放。网络状态是给界面看的诊断信息，不是工厂的开工门禁。</p>
 */
public enum FactoryState {
    /** 已创建，还没绑仓库。 */
    CREATED,
    /** 正在搬运。 */
    RUNNING,
    /** 业务性停摆：缺料、满仓、没有可搬的机器。 */
    PAUSED,
    /** 玩家手动停止。 */
    STOPPED
}
