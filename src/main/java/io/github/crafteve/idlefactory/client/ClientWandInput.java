package io.github.crafteve.idlefactory.client;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.BindingDraftController;
import io.github.crafteve.idlefactory.item.MachineBindingWandItem;
import io.github.crafteve.idlefactory.item.StorageBindingWandItem;
import io.github.crafteve.idlefactory.network.payload.FactoryLinksRequestC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryWandActionC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryWandActionC2S.Action;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 绑定杖的客户端输入。
 *
 * <p>左/右键方块只负责“读按键并发请求”，真正的连接、断开、绑定都在服务端。Shift+滚轮只在
 * 主手拿存储杖时拦截，用来切换 A/B 模式；服务端确认后才更新缓存。</p>
 *
 * <p>手持绑定杖时每 2 秒请求一次链接快照，兼顾指令改链接后客户端能刷新。</p>
 */
@EventBusSubscriber(modid = IdleFactory.MODID, bus = EventBusSubscriber.Bus.GAME,
        value = Dist.CLIENT)
public final class ClientWandInput {
    /** 请求节流：40 tick = 2 秒。 */
    private static int requestCooldown;

    private ClientWandInput() {
        // 纯静态事件处理
    }

    /**
     * 左键方块：机器/存储杖都发 toggle；机器杖取消挖掘由公共事件处理。
     *
     * @param event 左键方块事件
     */
    @SubscribeEvent(receiveCanceled = true)
    static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (!event.getLevel().isClientSide || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        Action action;
        if (event.getEntity().getMainHandItem().getItem() instanceof MachineBindingWandItem) {
            BindingDraftController.onLeftClickBlock(event.getPos());
            return;
        } else if (event.getEntity().getMainHandItem().getItem()
                instanceof StorageBindingWandItem) {
            action = Action.STORAGE_TOGGLE;
        } else {
            return;
        }
        send(action, event.getPos());
    }

    /**
     * Shift+滚轮切换存储模式；只在主手存储杖时拦截。
     *
     * @param event 滚轮事件
     */
    @SubscribeEvent
    static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.player == null) {
            return;
        }
        boolean machineWand = minecraft.player.getMainHandItem().getItem()
                instanceof MachineBindingWandItem;
        boolean storageWand = minecraft.player.getMainHandItem().getItem()
                instanceof StorageBindingWandItem;
        if (!machineWand && !storageWand) {
            return;
        }
        if (!Screen.hasShiftDown()) {
            return;
        }
        event.setCanceled(true);
        if (machineWand) {
            BindingDraftController.cycleMode(false);
            return;
        }
        PacketDistributor.sendToServer(new FactoryWandActionC2S(Action.MODE_SWITCH, null, false));
        requestCooldown = 10;
    }

    /**
     * 左键空气：蹲下取消绑定草稿。
     *
     * @param event 左键空气事件
     */
    @SubscribeEvent
    static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        if (event.getEntity().getMainHandItem().getItem() instanceof MachineBindingWandItem) {
            BindingDraftController.onLeftClickAir();
        }
    }

    /**
     * 右键空气：蹲下右键提交 / 结束当前机器。
     *
     * @param event 右键空气事件
     */
    @SubscribeEvent
    static void onRightClickEmpty(PlayerInteractEvent.RightClickEmpty event) {
        if (event.getEntity().getMainHandItem().getItem() instanceof MachineBindingWandItem) {
            BindingDraftController.onRightClickAir();
        }
    }

    /**
     * 手持绑定杖时定期请求链接快照。
     *
     * @param event 客户端 tick
     */
    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        BindingDraftController.tick(minecraft);
        if (requestCooldown > 0) {
            requestCooldown--;
            return;
        }
        boolean holding = minecraft.player.getMainHandItem().getItem()
                instanceof MachineBindingWandItem
                || minecraft.player.getMainHandItem().getItem()
                instanceof StorageBindingWandItem;
        if (!holding) {
            return;
        }
        PacketDistributor.sendToServer(new FactoryLinksRequestC2S());
        requestCooldown = 40;
    }

    /**
     * 登录时清缓存，避免看到上一个存档的框。
     *
     * @param event 登录事件
     */
    @SubscribeEvent
    static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        ClientFactoryLinks.clear();
        FactoryLinkRenderer.clearCache();
        BindingDraftController.clearAll();
        requestCooldown = 0;
    }

    /**
     * 登出时清缓存。
     *
     * @param event 登出事件
     */
    @SubscribeEvent
    static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientFactoryLinks.clear();
        FactoryLinkRenderer.clearCache();
        BindingDraftController.clearAll();
    }

    private static void send(Action action, BlockPos pos) {
        PacketDistributor.sendToServer(new FactoryWandActionC2S(
                action, pos, Screen.hasControlDown()));
    }
}
