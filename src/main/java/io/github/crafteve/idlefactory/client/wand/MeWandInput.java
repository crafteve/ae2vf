package io.github.crafteve.idlefactory.client.wand;


import io.github.crafteve.idlefactory.client.wand.MachineWandInput;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.command.CommandKind;
import io.github.crafteve.idlefactory.command.CommandSource;
import io.github.crafteve.idlefactory.command.FactoryCommand;
import io.github.crafteve.idlefactory.item.MeBindingWandItem;
import io.github.crafteve.idlefactory.location.BlockKey;
import io.github.crafteve.idlefactory.network.payload.FactoryCommandC2S;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * ME 绑定杖的客户端输入：左键 toggle 仓库绑定。
 *
 * <p>全是「读按键并发命令」，真正的绑定写在服务端；服务端确认后靠链接快照与 HUD 回执同步。
 * 机器绑定杖的输入在 {@code client.wand.MachineWandInput}，两者互不引用。</p>
 */
@EventBusSubscriber(modid = IdleFactory.MODID, value = Dist.CLIENT)
public final class MeWandInput {
    private MeWandInput() {
        // 纯静态事件处理
    }

    /**
     * 左键方块：ME 杖发仓库锚点 toggle；取消挖掘由 {@code FactoryWandEvents} 负责。
     *
     * @param event 左键方块事件
     */
    @SubscribeEvent(receiveCanceled = true)
    static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (!event.getLevel().isClientSide) {
            return;
        }
        if (!(event.getEntity().getMainHandItem().getItem() instanceof MeBindingWandItem)) {
            return;
        }
        BlockKey target = BlockKey.of(event.getLevel().dimension(), event.getPos());
        PacketDistributor.sendToServer(new FactoryCommandC2S(new FactoryCommand(-1,
                CommandKind.SET_ANCHOR, CommandSource.PLAYER, null, target, 0L,
                new FactoryCommand.Empty())));
    }
}
