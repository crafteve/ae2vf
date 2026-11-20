package io.github.crafteve.ae2vf.command;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.ae2vf.factory.Factory;
import io.github.crafteve.ae2vf.factory.FactoryLifecycle;

import net.minecraft.server.level.ServerPlayer;

/**
 * 一次命令的上下文：谁发的、作用在哪家工厂、哪条会话。
 *
 * @param player      发起玩家；SYSTEM 命令为 {@code null}
 * @param factory     目标工厂
 * @param runtime     服务端运行时
 * @param containerId 菜单容器 id；世界命令为 {@code -1}
 * @param requestSeq  请求序号
 * @param now         当前游戏时间（tick）
 */
public record CommandContext(@Nullable ServerPlayer player, Factory factory,
        FactoryLifecycle runtime, int containerId, long requestSeq, long now) {
}
