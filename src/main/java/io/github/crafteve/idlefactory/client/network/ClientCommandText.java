package io.github.crafteve.idlefactory.client.network;

import io.github.crafteve.idlefactory.client.CommandMessages;
import io.github.crafteve.idlefactory.command.CommandResult;

/**
 * 世界命令的动作栏文案。
 *
 * <p>终端回执由屏幕决定怎么显示；世界里没有界面，只能进动作栏。成功也回一句，否则玩家不知道
 * 绑定到底成没成。</p>
 */
public final class ClientCommandText {
    private ClientCommandText() {
        // 纯静态映射
    }

    /**
     * 世界命令的 HUD 文本。
     *
     * @param result 回执
     * @return 中文文案
     */
    public static String hud(CommandResult result) {
        return result.ok() ? "已更新仓库锚点" : CommandMessages.text(result);
    }
}
