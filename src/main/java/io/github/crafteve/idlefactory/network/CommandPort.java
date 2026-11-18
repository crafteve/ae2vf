package io.github.crafteve.idlefactory.network;

import io.github.crafteve.idlefactory.command.CommandController;
import io.github.crafteve.idlefactory.command.CommandKind;
import io.github.crafteve.idlefactory.command.CommandResult;
import io.github.crafteve.idlefactory.command.FactoryCommand;
import io.github.crafteve.idlefactory.menu.FactoryNodeMenu;
import io.github.crafteve.idlefactory.menu.FactoryPassiveIoMenu;
import io.github.crafteve.idlefactory.network.payload.FactoryCommandS2C;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 写命令的网络端口：解码后的命令在这里落地，并决定回执与快照怎么发。
 *
 * <p>控制器只回 {@link CommandResult}，本类负责三件事：系统命令不回执、屏幕命令投
 * {@code FactoryCommandS2C}、按种类推一份新快照。世界命令（{@code containerId = -1}）
 * 不推屏幕快照，由调用方自己的通道同步。</p>
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
        sendSnapshot(player, command);
    }

    /**
     * 动作完成后立即推一份新快照。
     *
     * <p>推哪种表由**当前打开的菜单**决定，而不是命令种类：节点终端的阻挡 / 倍率 / 派单
     * 与被动 IO 终端的规则动作都改同一份配置，但要看的表不同。按种类写死会把节点终端的
     * 设置动作推成机器表，界面只能等下一次 20 tick 轮询，表现为「节点设置慢半拍」。</p>
     *
     * @param player  玩家
     * @param command 命令
     */
    private static void sendSnapshot(ServerPlayer player, FactoryCommand command) {
        if (command.containerId() < 0
                || player.containerMenu.containerId != command.containerId()) {
            // 世界命令没有终端；界面已经关掉时也不必再推。
            return;
        }
        if (player.containerMenu instanceof FactoryNodeMenu) {
            FactoryNodeSnapshots.send(player, command.containerId());
        } else if (player.containerMenu instanceof FactoryPassiveIoMenu) {
            FactoryTableSnapshots.send(player, command.containerId());
        }
    }
}
