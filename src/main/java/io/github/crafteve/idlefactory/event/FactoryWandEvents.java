package io.github.crafteve.idlefactory.event;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.item.MachineBindingWandItem;

import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 绑定杖的公共交互拦截。
 *
 * <p>机器杖左键不能挖方块，只在主手时取消；连接/断开的请求在客户端
 * {@code ClientWandInput} 里发。</p>
 */
@EventBusSubscriber(modid = IdleFactory.MODID)
public final class FactoryWandEvents {
    private FactoryWandEvents() {
        // 纯静态事件处理
    }

    /**
     * 主手机器杖左键方块：取消挖掘。
     *
     * @param event 左键方块事件
     */
    @SubscribeEvent
    static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (event.getEntity().getMainHandItem().getItem() instanceof MachineBindingWandItem) {
            event.setCanceled(true);
        }
    }
}
