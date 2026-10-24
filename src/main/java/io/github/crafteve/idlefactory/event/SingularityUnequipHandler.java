package io.github.crafteve.idlefactory.event;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.network.payload.UnequipSingularityC2S;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 奇点卸下交互（仅客户端）。
 *
 * <p>装备后手里是空的，卸下动作就是「潜行 + 空手右键空气」。该事件服务端收不到，所以这里
 * 发 {@link UnequipSingularityC2S} 让服务端处理；服务端会自己校验附件里到底有没有奇点。</p>
 */
@EventBusSubscriber(modid = IdleFactory.MODID, value = Dist.CLIENT)
public final class SingularityUnequipHandler {
    private SingularityUnequipHandler() {
        // 纯静态事件处理器
    }

    @SubscribeEvent
    public static void onRightClickEmpty(PlayerInteractEvent.RightClickEmpty event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }

        Player player = event.getEntity();
        if (!player.isShiftKeyDown()) {
            return;
        }

        PacketDistributor.sendToServer(new UnequipSingularityC2S());
    }
}
