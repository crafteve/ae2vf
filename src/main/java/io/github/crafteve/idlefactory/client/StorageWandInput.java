package io.github.crafteve.idlefactory.client;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.item.StorageBindingWandItem;
import io.github.crafteve.idlefactory.network.payload.FactoryWandActionC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryWandActionC2S.Action;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 存储绑定杖的客户端输入：左键 toggle、Shift + 滚轮切换 ME / 容器模式。
 *
 * <p>全是「读按键并发请求」，真正的绑定与模式写在服务端；服务端确认后靠链接快照回同步。
 * 机器绑定杖的输入在 {@code client.wand.WandGesture}，两者互不引用。</p>
 */
@EventBusSubscriber(modid = IdleFactory.MODID, bus = EventBusSubscriber.Bus.GAME,
        value = Dist.CLIENT)
public final class StorageWandInput {
    private StorageWandInput() {
        // 纯静态事件处理
    }

    /**
     * 左键方块：存储杖发仓库 toggle；取消挖掘由 {@code FactoryWandEvents} 负责。
     *
     * @param event 左键方块事件
     */
    @SubscribeEvent(receiveCanceled = true)
    static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (!event.getLevel().isClientSide) {
            return;
        }
        if (!(event.getEntity().getMainHandItem().getItem() instanceof StorageBindingWandItem)) {
            return;
        }
        PacketDistributor.sendToServer(new FactoryWandActionC2S(Action.STORAGE_TOGGLE,
                event.getPos(), Screen.hasControlDown()));
    }

    /**
     * Shift + 滚轮：切换仓库模式；只在主手存储杖且没有界面时拦截。
     *
     * @param event 滚轮事件
     */
    @SubscribeEvent
    static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.player == null) {
            return;
        }
        if (!(minecraft.player.getMainHandItem().getItem() instanceof StorageBindingWandItem)) {
            return;
        }
        if (!Screen.hasShiftDown()) {
            return;
        }
        event.setCanceled(true);
        PacketDistributor.sendToServer(new FactoryWandActionC2S(Action.MODE_SWITCH, null, false));
    }
}
