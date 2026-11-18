package io.github.crafteve.idlefactory.network;

import io.github.crafteve.idlefactory.command.CommandController;
import io.github.crafteve.idlefactory.command.CommandKind;
import io.github.crafteve.idlefactory.command.CommandResult;
import io.github.crafteve.idlefactory.command.FactoryCommand;
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
        if (command.kind().needsReceipt() && command.containerId() >= 0) {
            PacketDistributor.sendToPlayer(player,
                    new FactoryCommandS2C(command.containerId(), command.kind(), result));
        }
        sendSnapshot(player, command);
    }

    /**
     * 按命令种类推一份新快照；屏幕命令才推。
     *
     * @param player  玩家
     * @param command 命令
     */
    private static void sendSnapshot(ServerPlayer player, FactoryCommand command) {
        if (command.containerId() < 0) {
            return;
        }
        switch (command.kind()) {
            case NODE_RENAME, NODE_DELETE, NODE_TYPE_MIGRATE ->
                    FactoryNodeSnapshots.send(player, command.containerId());
            case RULE_ADD, RULE_UPDATE, RULE_MOVE, RULE_REMOVE, PROVIDER_BLOCKING,
                    PROVIDER_MULTIPLIER, PROVIDER_DISPATCH ->
                    FactoryTableSnapshots.send(player, command.containerId());
            case FACTORY_CREATE, WAND_TOGGLE, WAND_COMMIT, SET_ANCHOR, CLEAR_ANCHOR -> {
                // 屏幕命令之外的种类不需要终端表快照
            }
        }
    }
}
