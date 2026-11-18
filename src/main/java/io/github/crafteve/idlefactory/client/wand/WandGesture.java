package io.github.crafteve.idlefactory.client.wand;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.location.BlockKey;
import io.github.crafteve.idlefactory.item.MachineBindingWandItem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 机器绑定杖的客户端手势：方块与空气完全等价，方块不参与判断，也不自动加入草稿。
 *
 * <p>手势按 {@link WandSession.State} 分派：IDLE 普通右键发 1:1 toggle、蹲下右键进编辑态；
 * EDITING 普通右键改草稿、Ctrl + 右键连锁、蹲下右键开面板；SUBMITTING 全部忽略。左键完全不
 * 监听，挖掘由原版处理。事件只在客户端处理并取消，服务端不会收到方块交互包。</p>
 *
 * <p>登录 / 换维度 / 登出也在这里清缓存并请求一次链接快照，不再轮询。</p>
 */
@EventBusSubscriber(modid = IdleFactory.MODID, bus = EventBusSubscriber.Bus.GAME,
        value = Dist.CLIENT)
public final class WandGesture {
    private WandGesture() {
        // 纯静态事件处理
    }

    /**
     * 右键方块：按状态分派。
     *
     * @param event 右键方块事件
     */
    @SubscribeEvent
    static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!holdingWand(minecraft)) {
            return;
        }
        BlockKey block = BlockKey.of(event.getLevel().dimension(), event.getPos());
        if (handle(minecraft, block, minecraft.player.isShiftKeyDown(), Screen.hasControlDown())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    /**
     * 右键空气：只在没有瞄准方块时处理，避免与方块事件重复。
     *
     * @param event 右键物品事件
     */
    @SubscribeEvent
    static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!event.getLevel().isClientSide || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!holdingWand(minecraft)) {
            return;
        }
        // 瞄着超距方块时 hitResult 仍是 BlockHitResult（Type.MISS），那也算空气，否则蹲下右键
        // 在远处方块上会静默失效。
        if (minecraft.hitResult instanceof BlockHitResult hit
                && hit.getType() != HitResult.Type.MISS) {
            return;
        }
        if (handle(minecraft, null, minecraft.player.isShiftKeyDown(), Screen.hasControlDown())) {
            event.setCanceled(true);
        }
    }

    /**
     * 按状态执行手势。
     *
     * @param minecraft 客户端实例
     * @param block     目标方块；空气为 {@code null}
     * @param sneaking  是否蹲下
     * @param ctrl      是否按住 Ctrl
     * @return 是否消费了这次右键
     */
    private static boolean handle(Minecraft minecraft, @Nullable BlockKey block, boolean sneaking,
            boolean ctrl) {
        return switch (WandSession.state()) {
            case SUBMITTING -> {
                WandHud.neutral(WandMessages.waiting());
                yield true;
            }
            case IDLE -> {
                if (sneaking) {
                    WandSession.startNew(minecraft);
                    yield true;
                }
                if (block == null) {
                    yield false;
                }
                // 空闲态 Ctrl + 右键没有动作（B3），但仍拦住方块交互，别让机器 GUI 弹出来
                if (!ctrl) {
                    WandCoordinator.toggle(block);
                }
                yield true;
            }
            case EDITING -> {
                if (sneaking) {
                    WandCoordinator.openDraft();
                    yield true;
                }
                if (block == null) {
                    yield false;
                }
                if (ctrl) {
                    WandCoordinator.chain(block);
                } else {
                    WandSession.toggle(minecraft, block);
                }
                yield true;
            }
        };
    }

    /**
     * 主手是不是机器绑定杖。
     *
     * @param minecraft 客户端实例
     * @return 是时为 {@code true}
     */
    private static boolean holdingWand(Minecraft minecraft) {
        return minecraft.player != null
                && minecraft.player.getMainHandItem().getItem()
                instanceof MachineBindingWandItem;
    }

    /**
     * 每 tick 维护草稿生命周期、提交超时与 HUD 到期。
     *
     * @param event 客户端 tick 事件
     */
    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        WandSession.tick(minecraft);
        WandCoordinator.tick(minecraft);
        WandHud.tick(minecraft);
    }

    /**
     * 登录：清本地缓存，请求一次链接快照。
     *
     * @param event 登录事件
     */
    @SubscribeEvent
    static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        clearLocalState();
        WandCoordinator.reset();
        WandLinks.request();
    }

    /**
     * 登出：连提交序号与渲染缓存一起清。
     *
     * @param event 登出事件
     */
    @SubscribeEvent
    static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clearLocalState();
        WandCoordinator.reset();
    }

    /**
     * 换维度 / 重生：草稿与快照都作废，重新请求一次。
     *
     * @param event 克隆事件
     */
    @SubscribeEvent
    static void onClone(ClientPlayerNetworkEvent.Clone event) {
        clearLocalState();
        WandLinks.request();
    }

    /** 清草稿、快照与渲染缓存。 */
    private static void clearLocalState() {
        WandSession.clearAll();
        WandLinks.clear();
        WandLinkRenderer.clearCache();
        WandHud.clear();
    }
}
