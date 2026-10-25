package io.github.crafteve.idlefactory.event;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.FactoryService;

import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 工厂指令入口。
 *
 * <p>第一版：创建 / 删除 / 查看自己的工厂。绑定仓库、接入机器等指令等工厂主体接上
 * {@code WarehouseResolver} 后再加。</p>
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
                        .requires(source -> source.getEntity() instanceof ServerPlayer)
                        .executes(context -> FactoryService.create(
                                context.getSource().getPlayerOrException())))
                .then(Commands.literal("delete")
                        .requires(source -> source.getEntity() instanceof ServerPlayer)
                        .executes(context -> FactoryService.delete(
                                context.getSource().getPlayerOrException())))
                .then(Commands.literal("info")
                        .requires(source -> source.getEntity() instanceof ServerPlayer)
                        .executes(context -> FactoryService.info(
                                context.getSource().getPlayerOrException()))));
    }
}
