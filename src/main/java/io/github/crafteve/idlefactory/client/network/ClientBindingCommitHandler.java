package io.github.crafteve.idlefactory.client.network;

import io.github.crafteve.idlefactory.network.payload.FactoryBindingCommitS2C;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 绑定提交结果的客户端处理。
 *
 * <p>客户端只信服务端结果：成功才由 {@code BindingDraftController} 清草稿，失败保留草稿并提示。
 * P7 先落最小处理（动作栏提示 + 缓存最近结果），P8 再把手势草稿接进来。</p>
 */
public final class ClientBindingCommitHandler {
    /** 最近一次提交结果；界面可读它显示状态。 */
    private static FactoryBindingCommitS2C last;

    private ClientBindingCommitHandler() {
        // 纯静态处理器
    }

    /**
     * 处理一次提交结果。
     *
     * @param payload 结果
     */
    public static void handle(FactoryBindingCommitS2C payload) {
        last = payload;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        minecraft.player.displayClientMessage(Component.literal(payload.message()), true);
    }

    /** 最近一次提交结果；没有时 {@code null}。 */
    public static FactoryBindingCommitS2C last() {
        return last;
    }
}
