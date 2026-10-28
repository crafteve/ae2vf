package io.github.crafteve.idlefactory.event;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.item.MachineBindingWandItem;
import io.github.crafteve.idlefactory.item.StorageBindingWandItem;

import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 绑定杖的公共交互拦截。
 *
 * <p>机器杖左键不能挖方块；两根杖右键方块都取消原交互，避免机器/容器 GUI 抢在动作
 * 之前打开。连接/断开的请求在客户端 {@code ClientWandInput} 里发。</p>
 */
@EventBusSubscriber(modid = IdleFactory.MODID, bus = EventBusSubscriber.Bus.GAME)
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

    /**
     * 主手绑定杖右键方块：取消原方块交互，防止机器/容器打开 GUI。
     *
     * @param event 右键方块事件
     */
    @SubscribeEvent
    static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (event.getEntity().getMainHandItem().getItem() instanceof MachineBindingWandItem
                || event.getEntity().getMainHandItem().getItem()
                instanceof StorageBindingWandItem) {
            event.setCanceled(true);
        }
    }
}
