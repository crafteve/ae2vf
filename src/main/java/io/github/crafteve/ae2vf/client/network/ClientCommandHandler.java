package io.github.crafteve.ae2vf.client.network;

import io.github.crafteve.ae2vf.client.wand.WandSubmitter;

import io.github.crafteve.ae2vf.client.screen.FeedbackSink;
import io.github.crafteve.ae2vf.network.payload.FactoryCommandS2C;

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
        if (payload.kind() == io.github.crafteve.ae2vf.command.CommandKind.WAND_COMMIT) {
            io.github.crafteve.ae2vf.client.wand.WandSubmitter.handleCommitResult(
                    payload.containerId(), payload.requestSeq(), payload.result());
            return;
        }
        if (payload.kind() == io.github.crafteve.ae2vf.command.CommandKind.WAND_TOGGLE) {
            io.github.crafteve.ae2vf.client.wand.WandSubmitter
                    .handleToggleResult(payload.result());
            return;
        }
        if (payload.containerId() < 0) {
            // 世界命令（ME 绑定杖设锚点 / 解锚、绑定杖 toggle）：没有终端，回执走模组 HUD 条，
            // 不再塞原版动作栏，否则和绑定杖的条不是一套外观。
            var text = net.minecraft.network.chat.Component.literal(
                    io.github.crafteve.ae2vf.client.screen.text.CommandText.hud(payload.result()));
            if (payload.result().ok()) {
                io.github.crafteve.ae2vf.client.screen.hud.HUDRenderer.success(text);
            } else {
                io.github.crafteve.ae2vf.client.screen.hud.HUDRenderer.failure(text);
            }
            return;
        }
        if (Minecraft.getInstance().screen instanceof FeedbackSink sink
                && sink.containerId() == payload.containerId()) {
            sink.acceptCommandResult(payload.result());
        }
    }
}
