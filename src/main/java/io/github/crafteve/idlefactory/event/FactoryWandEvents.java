package io.github.crafteve.idlefactory.event;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.item.MeBindingWandItem;

import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 存储绑定杖的公共交互拦截。
 *
 * <p>存储杖左键不能挖方块，只在主手时取消。机器绑定杖左键完全不拦（B5）：它要靠正常挖掘
 * 清理现场，左键手势也不存在。</p>
 */
@EventBusSubscriber(modid = IdleFactory.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class FactoryWandEvents {
    private FactoryWandEvents() {
        // 纯静态事件处理
    }

    /**
     * 主手存储杖左键方块：取消挖掘。
     *
     * @param event 左键方块事件
     */
    @SubscribeEvent
    static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (event.getEntity().getMainHandItem().getItem() instanceof MeBindingWandItem) {
            event.setCanceled(true);
        }
    }
}
