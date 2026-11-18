package io.github.crafteve.idlefactory.command;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryConfig;
import io.github.crafteve.idlefactory.factory.FactoryManager;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;

import net.minecraft.server.level.ServerPlayer;

/**
 * 写命令的唯一入口：取工厂 → 门禁 → 执行 → 结构变化时补推快照。
 *
 * <p>建厂也在这里：{@code FACTORY_CREATE} 是 SYSTEM 命令，幂等、无回执；菜单打开终端时调
 * {@link #ensureFactory}，{@code FactoryLifecycle} 不建厂。</p>
 *
 * <p>本类不碰网络：回执与快照由 {@code CommandPort} 按命令种类决定怎么发。</p>
 */
public final class CommandController {
    private CommandController() {
        // 纯静态入口
    }

    /**
     * 执行一条玩家命令。
     *
     * @param player  发起玩家
     * @param command 命令
     * @return 回执
     */
    public static CommandResult execute(ServerPlayer player, FactoryCommand command) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return CommandResult.of(CommandReason.NOT_STARTED);
        }
        if (command.kind() == CommandKind.FACTORY_CREATE) {
            ensureFactory(player);
            return CommandResult.of(CommandReason.OK);
        }
        Factory factory = runtime.manager().byOwner(player.getUUID()).orElse(null);
        CommandReason denied = CommandGate.check(command, runtime, factory, player);
        if (denied != null) {
            return CommandResult.of(denied);
        }
        CommandContext context = new CommandContext(player, factory, runtime,
                command.containerId(), command.requestSeq(),
                player.serverLevel().getGameTime());
        CommandResult result = CommandApply.apply(context, command);
        if (result.ok() && changesStructure(command.kind())) {
            runtime.markDirtyLinks(factory);
        }
        return result;
    }

    /**
     * 这个种类是否会改变链接快照结构（节点集合、节点名、节点类型）。
     *
     * @param kind 命令种类
     * @return 会改变为 {@code true}
     */
    public static boolean changesStructure(CommandKind kind) {
        return switch (kind) {
            case NODE_DELETE, NODE_RENAME, WAND_COMMIT, WAND_TOGGLE, SET_ANCHOR, CLEAR_ANCHOR,
                    NODE_TYPE_MIGRATE, FACTORY_CREATE -> true;
            case RULE_ADD, RULE_UPDATE, RULE_MOVE, RULE_REMOVE, PROVIDER_BLOCKING,
                    PROVIDER_MULTIPLIER, PROVIDER_DISPATCH -> false;
        };
    }

    /**
     * 保证玩家有一家工厂；已有就什么都不做。
     *
     * <p>id 用「拥有者 + 自增序号」派生，确定且不与现有工厂冲突；建厂后推一次链接快照，
     * 否则绑定杖要等到下一次结构变更才看得到它。</p>
     *
     * @param player 玩家
     */
    public static void ensureFactory(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return;
        }
        FactoryManager manager = runtime.manager();
        if (manager.byOwner(player.getUUID()).isPresent()) {
            return;
        }
        UUID id = nextFactoryId(manager, runtime, player.getUUID());
        Factory factory = new Factory(id, new FactoryConfig(player.getUUID(), null));
        if (!manager.add(factory)) {
            IdleFactory.LOGGER.warn("自动创建工厂失败：player={}, id={}", player.getUUID(), id);
            return;
        }
        runtime.markDirtyLinks(factory);
    }

    /**
     * 生成一个不与现有工厂冲突的确定性 id。
     *
     * @param manager 工厂档案柜
     * @param runtime 运行时（取自增序号）
     * @param owner   拥有者
     * @return 新 id
     */
    @Nullable
    private static UUID nextFactoryId(FactoryManager manager, FactoryRuntime runtime, UUID owner) {
        UUID id;
        do {
            long seq = runtime.takeNextSeq();
            id = UUID.nameUUIDFromBytes((owner + ":" + seq).getBytes(StandardCharsets.UTF_8));
        } while (manager.byId(id).isPresent());
        return id;
    }
}
