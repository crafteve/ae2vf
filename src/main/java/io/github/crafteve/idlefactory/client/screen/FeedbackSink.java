package io.github.crafteve.idlefactory.client.screen;

import io.github.crafteve.idlefactory.command.CommandResult;

/**
 * 写命令回执的最小接收口。
 *
 * <p>被动 IO 终端与节点终端都实现它；{@code client.network} 只按接口投递，不 import 具体屏幕。</p>
 */
public interface FeedbackSink {
    /**
     * @return 本屏幕所属菜单的容器 id
     */
    int containerId();

    /**
     * 收到一次写命令的回执。
     *
     * @param result 回执
     */
    void acceptCommandResult(CommandResult result);
}
