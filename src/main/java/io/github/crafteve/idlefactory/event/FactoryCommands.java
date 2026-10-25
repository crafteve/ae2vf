package io.github.crafteve.idlefactory.event;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.FactoryService;

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
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

/**
 * 工厂指令入口。第一版不做 UI，所有调试都走这里。
 *
 * <pre>
 * /idlefactory create
 * /idlefactory delete
 * /idlefactory info
 * /idlefactory connect                 准星选机器，接入机器库
 * /idlefactory disconnect              准星选机器，移出机器库
 * /idlefactory disconnect &lt;id&gt;         按短 id 远程移除
 * /idlefactory storage connect         主手奇点绑定 ME 仓库
 * /idlefactory storage info
 * /idlefactory storage get &lt;item&gt; [count]
 * /idlefactory storage put &lt;item&gt; [count]
 * </pre>
 */
@EventBusSubscriber(modid = IdleFactory.MODID)
public final class FactoryCommands {
    private FactoryCommands() {
        // 纯静态事件处理器
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("idlefactory")
                .then(Commands.literal("create")
                        .executes(context -> FactoryService.create(player(context))))
                .then(Commands.literal("delete")
                        .executes(context -> FactoryService.delete(player(context))))
                .then(Commands.literal("info")
                        .executes(context -> FactoryService.info(player(context))))
                .then(Commands.literal("connect")
                        .executes(context -> FactoryService.connectMachine(player(context))))
                .then(Commands.literal("disconnect")
                        .executes(context -> FactoryService.disconnectMachine(player(context)))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(context -> FactoryService.disconnectMachine(
                                        player(context), StringArgumentType.getString(context, "id")))))
                .then(Commands.literal("storage")
                        .then(Commands.literal("connect")
                                .executes(context -> FactoryService.connectStorage(player(context))))
                        .then(Commands.literal("info")
                                .executes(context -> FactoryService.storageInfo(player(context))))
                        .then(Commands.literal("get")
                                .then(Commands.argument("item", ItemArgument.item(event.getBuildContext()))
                                        .executes(context -> FactoryService.storageGet(
                                                player(context), item(context), 1))
                                        .then(Commands.argument("count",
                                                        IntegerArgumentType.integer(1, 1_000_000))
                                                .executes(context -> FactoryService.storageGet(
                                                        player(context), item(context),
                                                        IntegerArgumentType.getInteger(context, "count"))))))
                        .then(Commands.literal("put")
                                .then(Commands.argument("item", ItemArgument.item(event.getBuildContext()))
                                        .executes(context -> FactoryService.storagePut(
                                                player(context), item(context), 1))
                                        .then(Commands.argument("count",
                                                        IntegerArgumentType.integer(1, 1_000_000))
                                                .executes(context -> FactoryService.storagePut(
                                                        player(context), item(context),
                                                        IntegerArgumentType.getInteger(context, "count"))))))));
    }

    private static ServerPlayer player(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        return context.getSource().getPlayerOrException();
    }

    private static ItemStack item(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        ItemInput input = ItemArgument.getItem(context, "item");
        return input.createItemStack(1, false);
    }
}
