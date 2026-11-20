package io.github.crafteve.idlefactory.network;

import io.github.crafteve.idlefactory.command.CommandController;
import io.github.crafteve.idlefactory.command.CommandKind;
import io.github.crafteve.idlefactory.command.CommandResult;
import io.github.crafteve.idlefactory.command.FactoryCommand;
import io.github.crafteve.idlefactory.factory.FactoryLifecycle;
import io.github.crafteve.idlefactory.network.payload.FactoryCommandS2C;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 写命令的网络端口：解码后的命令在这里落地，并决定回执与快照怎么发。
 *
 * <p>控制器只回 {@link CommandResult}，本类负责两件事：系统命令不回执、屏幕命令投
 * {@code FactoryCommandS2C}。写入成功后按工厂刷新视图表，世界命令与屏幕命令走同一条路。</p>
 */
public final class CommandPort {
    private CommandPort() {
        // 纯静态端口
    }

    /**
     * 处理一条客户端命令。
     *
     * @param context 网络上下文
     * @param command 命令
     */
    public static void handle(IPayloadContext context, FactoryCommand command) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        if (command.kind() == CommandKind.FACTORY_CREATE) {
            CommandController.ensureFactory(player);
            return;
        }
        CommandResult result = CommandController.execute(player, command);
        if (command.kind().needsReceipt()) {
            // 世界命令用 containerId = -1：客户端据此把回执投到 HUD 而不是终端。
            PacketDistributor.sendToPlayer(player, new FactoryCommandS2C(command.containerId(),
                    command.kind(), command.requestSeq(), result));
        }
        if (result.ok()) {
            refreshView(player);
        }
    }

    /**
     * 写命令成功后刷新视图表。
     *
     * <p>按<b>工厂</b>刷新而不是按当前菜单类型：节点终端的阻挡 / 倍率 / 派单与被动 IO 终端的
     * 规则动作改的是同一份配置，两个终端读的也是同一张视图表；世界命令（{@code containerId = -1}）
     * 同样会改变表，已打开的终端也该立刻看到。</p>
     *
     * <p>没有打开的终端时 {@link ViewPort#refresh} 直接返回——没有基线就没有差量可算。</p>
     *
     * @param player 发命令的玩家
     */
    private static void refreshView(ServerPlayer player) {
        FactoryLifecycle runtime = FactoryLifecycle.get();
        if (runtime == null) {
            return;
        }
        runtime.manager().byOwner(player.getUUID()).ifPresent(ViewPort::refresh);
    }
}
