package io.github.crafteve.idlefactory.command;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.menu.FactoryGuiOpening;
import io.github.crafteve.idlefactory.menu.FactoryNodeMenu;
import io.github.crafteve.idlefactory.menu.FactoryPassiveIoMenu;

import net.minecraft.server.level.ServerPlayer;

/**
 * 写命令的唯一校验点：种类 → 会话 → 归属 → 目标。
 *
 * <p>校验在边界做一次，内部信任：{@link CommandApply} 不再重复判空。会话步骤只对玩家在终端里
 * 发起的命令做（{@code containerId >= 0}）；世界命令用 {@code -1} 跳过，序号仍参与防重放。</p>
 *
 * <p>占用（同一位置不能同时是两家工厂的机器或仓库）在本阶段仍由绑定杖写入口自查，等
 * {@code claim.BlockOccupancyTable} 接进控制器后统一挪到这里。</p>
 */
public final class CommandGate {
    private CommandGate() {
        // 纯静态校验
    }

    /**
     * 校验一条命令是否允许执行。
     *
     * @param command 命令
     * @param runtime 服务端运行时
     * @param factory 玩家自己的工厂；没有则为 {@code null}
     * @param player  发起玩家；SYSTEM 命令为 {@code null}
     * @return 失败原因；通过时返回 {@code null}
     */
    @Nullable
    public static CommandReason check(FactoryCommand command, FactoryRuntime runtime,
            @Nullable Factory factory, @Nullable ServerPlayer player) {
        if (command.source() == CommandSource.SYSTEM) {
            return command.kind().systemOnly() ? null : CommandReason.SESSION_MISMATCH;
        }
        if (command.kind().systemOnly()) {
            return CommandReason.SESSION_MISMATCH;
        }
        if (player == null) {
            return CommandReason.SESSION_MISMATCH;
        }
        if (factory == null) {
            return CommandReason.FACTORY_MISSING;
        }
        if (command.containerId() >= 0 && !sessionMatches(player, command.containerId())) {
            return CommandReason.SESSION_MISMATCH;
        }
        if (command.kind().scope() == CommandKind.Scope.NODE && command.nodeId() == null) {
            return CommandReason.NODE_MISSING;
        }
        if (command.kind().scope() == CommandKind.Scope.BLOCK && command.target() == null) {
            return CommandReason.TARGET_UNLOADED;
        }
        return null;
    }

    /**
     * 菜单会话是否还是命令发起时那一个。
     *
     * @param player      玩家
     * @param containerId 菜单容器 id
     * @return 匹配为 {@code true}
     */
    private static boolean sessionMatches(ServerPlayer player, int containerId) {
        if (player.containerMenu.containerId != containerId) {
            return false;
        }
        return player.containerMenu instanceof FactoryPassiveIoMenu
                || player.containerMenu instanceof FactoryNodeMenu;
    }

    /**
     * 节点是否属于这家工厂。
     *
     * @param factory 工厂
     * @param nodeId  节点 id
     * @return 属于为 {@code true}
     */
    public static boolean ownsNode(Factory factory, UUID nodeId) {
        return factory.config().nodes().byId(nodeId).isPresent();
    }
}
