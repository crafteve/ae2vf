package io.github.crafteve.idlefactory.event;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.integration.ae2.Ae2Binder;
import io.github.crafteve.idlefactory.player.ModAttachments;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 奇点装备交互。
 *
 * <p>潜行 + 主手奇点 + 右键（空处）→ 装备进玩家附件；普通右键不拦截，保留奇点原本用途。
 * 卸下在 {@link SingularityUnequipHandler}（空手右键空气，需要走 C2S 包）。</p>
 */
@EventBusSubscriber(modid = IdleFactory.MODID)
public final class SingularityInteractionHandler {
    private SingularityInteractionHandler() {
        // 纯静态事件处理器
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        // 只处理主手；副手重复触发会重复扣物品
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }

        Player player = event.getEntity();
        // 只在服务端改数据；客户端照常走原版预测
        if (player.level().isClientSide() || !player.isShiftKeyDown()) {
            return;
        }

        ItemStack held = player.getItemInHand(event.getHand());
        if (!Ae2Binder.isSingularity(held) || ModAttachments.isEquipped(player)) {
            return;
        }

        ModAttachments.set(player, held.copyWithCount(1));
        held.shrink(1);
        player.displayClientMessage(Component.translatable("message.idlefactory.singularity_equipped"), true);

        // 吞掉这次右键，避免奇点被原版/AE2 的其它逻辑再处理一遍
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }
}
