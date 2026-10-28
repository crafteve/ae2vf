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
 * <p>两根绑定杖左键都不能挖方块，只在主手时取消。右键由物品的 {@code onItemUseFirst}
 * 处理，在方块 GUI 之前就已经消费掉。</p>
 */
@EventBusSubscriber(modid = IdleFactory.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class FactoryWandEvents {
    private FactoryWandEvents() {
        // 纯静态事件处理
    }

    /**
     * 主手绑定杖左键方块：取消挖掘。
     *
     * @param event 左键方块事件
     */
    @SubscribeEvent
    static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
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
