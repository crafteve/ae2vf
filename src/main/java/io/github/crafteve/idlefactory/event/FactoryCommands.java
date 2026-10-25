package io.github.crafteve.idlefactory.event;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.FactoryService;
import io.github.crafteve.idlefactory.factory.line.LineService;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;

/**
 * 工厂指令入口。第一版不做 UI，所有调试都走这里。
 *
 * <pre>
 * /idf create
 * /idf delete
 * /idf factory connect
 * /idf factory disconnect [id]
 * /idf factory info
 * /idf line create &lt;name&gt;
 * /idf line delete &lt;line&gt;
 * /idf line add &lt;line&gt; &lt;machine&gt;
 * /idf line remove &lt;line&gt; &lt;machine&gt;
 * /idf line start &lt;line&gt;
 * /idf line end &lt;line&gt;
 * /idf line info &lt;line&gt;
 * /idf line manage &lt;line&gt; &lt;machine&gt; info
 * /idf line manage &lt;line&gt; &lt;machine&gt; input  add &lt;item&gt; [face]
 * /idf line manage &lt;line&gt; &lt;machine&gt; input  remove &lt;item&gt;
 * /idf line manage &lt;line&gt; &lt;machine&gt; output add &lt;item&gt; [face]
 * /idf line manage &lt;line&gt; &lt;machine&gt; output remove &lt;item&gt;
 * /idf storage connect
 * /idf storage info
 * /idf storage get &lt;item&gt; [count]
 * /idf storage put &lt;item&gt; [count]
 * </pre>
 */
@EventBusSubscriber(modid = IdleFactory.MODID)
public final class FactoryCommands {
    private FactoryCommands() {
        // 纯静态事件处理器
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralCommandNode<CommandSourceStack> root = event.getDispatcher().register(idleFactory(event));
        // 短别名：/idf ...
        event.getDispatcher().register(Commands.literal("idf").redirect(root));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> idleFactory(RegisterCommandsEvent event) {
        return Commands.literal("idlefactory")
                .then(Commands.literal("create")
                        .executes(context -> FactoryService.create(player(context))))
                .then(Commands.literal("delete")
                        .executes(context -> FactoryService.delete(player(context))))
                .then(factory())
                .then(line(event))
                .then(storage(event));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> factory() {
        return Commands.literal("factory")
                .then(Commands.literal("connect")
                        .executes(context -> FactoryService.connectMachine(player(context))))
                .then(Commands.literal("disconnect")
                        .executes(context -> FactoryService.disconnectMachine(player(context)))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(context -> FactoryService.disconnectMachine(
                                        player(context), StringArgumentType.getString(context, "id")))))
                .then(Commands.literal("info")
                        .executes(context -> FactoryService.info(player(context))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> line(RegisterCommandsEvent event) {
        return Commands.literal("line")
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> LineService.create(player(context),
                                        StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("delete")
                        .then(Commands.argument("line", StringArgumentType.word())
                                .executes(context -> LineService.delete(player(context), lineRef(context)))))
                .then(Commands.literal("add")
                        .then(Commands.argument("line", StringArgumentType.word())
                                .then(Commands.argument("machine", StringArgumentType.word())
                                        .executes(context -> LineService.add(player(context),
                                                lineRef(context), machineRef(context))))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("line", StringArgumentType.word())
                                .then(Commands.argument("machine", StringArgumentType.word())
                                        .executes(context -> LineService.remove(player(context),
                                                lineRef(context), machineRef(context))))))
                .then(Commands.literal("start")
                        .then(Commands.argument("line", StringArgumentType.word())
                                .executes(context -> LineService.start(player(context), lineRef(context)))))
                .then(Commands.literal("end")
                        .then(Commands.argument("line", StringArgumentType.word())
                                .executes(context -> LineService.end(player(context), lineRef(context)))))
                .then(Commands.literal("info")
                        .then(Commands.argument("line", StringArgumentType.word())
                                .executes(context -> LineService.info(player(context), lineRef(context)))))
                .then(Commands.literal("manage")
                        .then(Commands.argument("line", StringArgumentType.word())
                                .then(Commands.argument("machine", StringArgumentType.word())
                                        .then(Commands.literal("info")
                                                .executes(context -> LineService.manageInfo(
                                                        player(context), lineRef(context), machineRef(context))))
                                        .then(manageSide(event, true))
                                        .then(manageSide(event, false)))));
    }

    /**
     * 构建 manage 的 input / output 子树。
     *
     * @param event 注册事件（取 build context）
     * @param input 是输入侧还是输出侧
     * @return 子树
     */
    private static LiteralArgumentBuilder<CommandSourceStack> manageSide(RegisterCommandsEvent event,
            boolean input) {
        String side = input ? "input" : "output";
        return Commands.literal(side)
                .then(Commands.literal("add")
                        .then(Commands.argument("item", ItemArgument.item(event.getBuildContext()))
                                .executes(context -> addSpec(context, input, null))
                                .then(Commands.argument("face", StringArgumentType.word())
                                        .executes(context -> addSpec(context, input,
                                                StringArgumentType.getString(context, "face"))))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("item", ItemArgument.item(event.getBuildContext()))
                                .executes(context -> removeSpec(context, input))));
    }

    private static int addSpec(CommandContext<CommandSourceStack> context, boolean input,
            String face) throws CommandSyntaxException {
        ServerPlayer player = player(context);
        ItemStack item = item(context);
        return input
                ? LineService.manageInputAdd(player, lineRef(context), machineRef(context), item, face)
                : LineService.manageOutputAdd(player, lineRef(context), machineRef(context), item, face);
    }

    private static int removeSpec(CommandContext<CommandSourceStack> context, boolean input)
            throws CommandSyntaxException {
        ServerPlayer player = player(context);
        ItemStack item = item(context);
        return input
                ? LineService.manageInputRemove(player, lineRef(context), machineRef(context), item)
                : LineService.manageOutputRemove(player, lineRef(context), machineRef(context), item);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> storage(RegisterCommandsEvent event) {
        return Commands.literal("storage")
                .then(Commands.literal("connect")
                        .executes(context -> FactoryService.connectStorage(player(context))))
                .then(Commands.literal("info")
                        .executes(context -> FactoryService.storageInfo(player(context))))
                .then(Commands.literal("get")
                        .then(Commands.argument("item", ItemArgument.item(event.getBuildContext()))
                                .executes(context -> FactoryService.storageGet(
                                        player(context), item(context), 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 1_000_000))
                                        .executes(context -> FactoryService.storageGet(
                                                player(context), item(context),
                                                IntegerArgumentType.getInteger(context, "count"))))))
                .then(Commands.literal("put")
                        .then(Commands.argument("item", ItemArgument.item(event.getBuildContext()))
                                .executes(context -> FactoryService.storagePut(
                                        player(context), item(context), 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 1_000_000))
                                        .executes(context -> FactoryService.storagePut(
                                                player(context), item(context),
                                                IntegerArgumentType.getInteger(context, "count"))))));
    }

    private static ServerPlayer player(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        return context.getSource().getPlayerOrException();
    }

    private static String lineRef(CommandContext<CommandSourceStack> context) {
        return StringArgumentType.getString(context, "line");
    }

    private static String machineRef(CommandContext<CommandSourceStack> context) {
        return StringArgumentType.getString(context, "machine");
    }

    private static ItemStack item(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        ItemInput input = ItemArgument.getItem(context, "item");
        return input.createItemStack(1, false);
    }
}
