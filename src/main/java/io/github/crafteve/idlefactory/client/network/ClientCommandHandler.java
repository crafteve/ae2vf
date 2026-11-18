package io.github.crafteve.idlefactory.client.network;

import io.github.crafteve.idlefactory.client.screen.FactoryFeedbackSink;
import io.github.crafteve.idlefactory.network.payload.FactoryCommandS2C;

import net.minecraft.client.Minecraft;

/**
 * 写命令回执的客户端入口：只投给容器 id 匹配的当前屏幕。
 */
public final class ClientCommandHandler {
    private ClientCommandHandler() {
        // 纯静态工具类
    }

    /**
     * 处理一次回执。
     *
     * @param payload 负载
     */
    public static void handle(FactoryCommandS2C payload) {
        if (payload.kind() == io.github.crafteve.idlefactory.command.CommandKind.WAND_COMMIT) {
            io.github.crafteve.idlefactory.client.wand.WandCoordinator.handleCommitResult(
                    payload.containerId(), payload.requestSeq(), payload.result());
            return;
        }
        if (payload.kind() == io.github.crafteve.idlefactory.command.CommandKind.WAND_TOGGLE) {
            io.github.crafteve.idlefactory.client.wand.WandCoordinator
                    .handleToggleResult(payload.result());
            return;
        }
        if (payload.containerId() < 0) {
            // 世界命令：没有终端，回执投到动作栏。
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                ClientCommandText.hud(payload.result())), true);
            }
            return;
        }
        if (Minecraft.getInstance().screen instanceof FactoryFeedbackSink sink
                && sink.containerId() == payload.containerId()) {
            sink.acceptCommandResult(payload.result());
        }
    }
}
