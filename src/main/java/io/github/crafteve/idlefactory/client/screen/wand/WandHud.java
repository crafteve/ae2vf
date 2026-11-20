package io.github.crafteve.idlefactory.client.screen.wand;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.screen.hud.HUDRenderer;
import io.github.crafteve.idlefactory.client.screen.text.WandText;
import io.github.crafteve.idlefactory.client.wand.WandDraftState;
import io.github.crafteve.idlefactory.client.wand.WandFeedback;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 绑定杖 HUD：编辑态常驻条。
 *
 * <p>闪条（成功 / 失败 / 中性）统一交给 {@link HUDRenderer}，本类只画常驻草稿条；
 * 两者用同一套底板与状态点，不再各写一份。同一时刻最多一条闪条，新的覆盖旧的。</p>
 */
@EventBusSubscriber(modid = IdleFactory.MODID, bus = EventBusSubscriber.Bus.GAME,
        value = Dist.CLIENT)
public final class WandHud {
    /** 常驻条距屏幕底部的距离，避开快捷栏。 */
    private static final int BOTTOM_INSET = 58;

    /** 注入给状态机与提交器的实现；HUD 自己仍是静态渲染。 */
    public static final WandFeedback INSTANCE = new WandFeedback() {
        @Override
        public void success(Component text) {
            HUDRenderer.success(text);
        }

        @Override
        public void neutral(Component text) {
            HUDRenderer.neutral(text);
        }

        @Override
        public void failure(Component text) {
            HUDRenderer.failure(text);
        }

        @Override
        public void click() {
            HUDRenderer.click();
        }

        @Override
        public void tick(Minecraft minecraft) {
            HUDRenderer.tick(minecraft);
        }

        @Override
        public void clear() {
            HUDRenderer.clear();
        }

        @Override
        public boolean draftScreenOpen() {
            return Minecraft.getInstance().screen instanceof WandDraftScreen;
        }
    };

    private WandHud() {
        // 纯静态渲染
    }

    /**
     * 每帧画常驻草稿条；只有没开界面时画。
     *
     * @param event HUD 渲染事件
     */
    @SubscribeEvent
    static void onRender(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null || minecraft.options.hideGui) {
            return;
        }
        Component bar = barText();
        if (bar == null) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        int barY = minecraft.getWindow().getGuiScaledHeight() - BOTTOM_INSET;
        HUDRenderer.drawBar(graphics, minecraft.font, bar, barY, HUDRenderer.TEXT_DIM);
    }

    /**
     * 常驻条文案；当前状态不该显示时返回 {@code null}。
     *
     * @return 文案或 {@code null}
     */
    @Nullable
    private static Component barText() {
        return switch (WandDraftState.state()) {
            case IDLE -> null;
            case EDITING -> draftBar();
            case SUBMITTING -> WandText.submitting();
        };
    }

    /** 草稿条：新建报台数，编辑报节点名与加 / 删台数。 */
    private static Component draftBar() {
        var draft = WandDraftState.draft();
        if (draft.nodeId() != null) {
            return WandText.draftEdit(WandText.nodeLabel(draft.nodeId()),
                    draft.added().size(), draft.removed().size());
        }
        return WandText.draftNew(draft.added().size());
    }
}
