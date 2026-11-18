package io.github.crafteve.idlefactory.client.wand;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.screen.FactoryGuiTheme;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 绑定杖 HUD：编辑态常驻条 + 一条闪条。
 *
 * <p>状态只由事件写：手势改草稿、回执设闪条、客户端 tick 清到期；渲染每帧只读，不判断业务。
 * 同一时刻最多一条闪条，新的覆盖旧的，不排队。只有当前没有界面时才画。</p>
 *
 * <p>这是叠加层不是终端，所以底板用固定的 AE2 深色，状态点取
 * {@link FactoryGuiTheme} 的绿 / 黄 / 红三色。</p>
 */
@EventBusSubscriber(modid = IdleFactory.MODID, bus = EventBusSubscriber.Bus.GAME,
        value = Dist.CLIENT)
public final class WandHud {
    /** 深底；终端底色是浅色，HUD 用深色才不糊在天空上。 */
    private static final int BACKGROUND = 0xC0101418;
    /** 1px 边框。 */
    private static final int BORDER = 0xFF4A5560;
    /** 正文色。 */
    private static final int TEXT_COLOR = 0xFFE6E6E6;
    /** 文本左右内边距。 */
    private static final int PADDING_X = 6;
    /** 条高。 */
    private static final int BAR_HEIGHT = 13;
    /** 状态点尺寸。 */
    private static final int DOT_SIZE = 3;
    /** 常驻条距屏幕底部的距离，避开快捷栏。 */
    private static final int BOTTOM_INSET = 58;
    /** 闪条与常驻条的间距。 */
    private static final int FLASH_GAP = 4;
    /** HUD 闪条停留时间（tick）。 */
    private static final int FLASH_TICKS = 60;

    @Nullable
    private static Component flashText;
    private static int flashColor = 0xFFFFFFFF;
    private static long flashExpireTick;

    private WandHud() {
        // 纯静态渲染
    }

    /**
     * 绿色闪条：成功。
     *
     * @param text 文案
     */
    public static void success(Component text) {
        flash(text, FactoryGuiTheme.current().statusRun());
        play(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F);
    }

    /**
     * 红色闪条：失败。
     *
     * @param text 文案
     */
    public static void failure(Component text) {
        flash(text, FactoryGuiTheme.current().error());
        play(SoundEvents.VILLAGER_NO, 1.0F);
    }

    /**
     * 黄色闪条：无效操作。
     *
     * @param text 文案
     */
    public static void neutral(Component text) {
        flash(text, FactoryGuiTheme.current().statusWarn());
        play(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F);
    }

    /** 草稿加 / 删 / 进入编辑的轻提示音。 */
    public static void click() {
        play(SoundEvents.UI_BUTTON_CLICK.value(), 1.4F);
    }

    /**
     * 本地播放一个原版音效；只在客户端主线程调。
     *
     * @param event 音效
     * @param pitch 音高
     */
    private static void play(SoundEvent event, float pitch) {
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.playSound(event, 0.35F, pitch);
        }
    }

    /** 立即清掉闪条；登录 / 登出 / 换维度时调。 */
    public static void clear() {
        flashText = null;
    }

    /**
     * 清到期闪条；客户端 tick 调。
     *
     * @param minecraft 客户端实例
     */
    public static void tick(Minecraft minecraft) {
        if (flashText == null) {
            return;
        }
        if (minecraft.level == null || minecraft.level.getGameTime() >= flashExpireTick) {
            flashText = null;
        }
    }

    private static void flash(Component text, int color) {
        Minecraft minecraft = Minecraft.getInstance();
        flashText = text;
        flashColor = color;
        flashExpireTick = minecraft.level == null
                ? 0L : minecraft.level.getGameTime() + FLASH_TICKS;
    }

    /**
     * 每帧画一次：编辑态常驻条在下，闪条在上。
     *
     * @param event HUD 渲染事件
     */
    @SubscribeEvent
    static void onRender(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null || minecraft.options.hideGui) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        int barY = minecraft.getWindow().getGuiScaledHeight() - BOTTOM_INSET;
        Component bar = barText();
        if (bar != null) {
            draw(graphics, minecraft, bar, barY, FactoryGuiTheme.current().textDim());
        }
        if (flashText != null) {
            draw(graphics, minecraft, flashText, barY - BAR_HEIGHT - FLASH_GAP, flashColor);
        }
    }

    /**
     * 常驻条文案；当前状态不该显示时返回 {@code null}。
     *
     * @return 文案或 {@code null}
     */
    @Nullable
    private static Component barText() {
        return switch (WandSession.state()) {
            case IDLE -> null;
            case EDITING -> draftBar();
            case SUBMITTING -> WandMessages.submitting();
        };
    }

    /** 草稿条：新建报台数，编辑报节点名与加 / 删台数。 */
    private static Component draftBar() {
        var draft = WandSession.draft();
        if (draft.nodeId() != null) {
            return WandMessages.draftEdit(WandMessages.nodeLabel(draft.nodeId()),
                    draft.added().size(), draft.removed().size());
        }
        return WandMessages.draftNew(draft.added().size());
    }

    /**
     * 画一条 HUD：深底、1px 边框、左侧状态点、正文。
     *
     * @param graphics 绘制上下文
     * @param minecraft 客户端实例
     * @param text     文案
     * @param y        顶边
     * @param dotColor 状态点颜色
     */
    private static void draw(GuiGraphics graphics, Minecraft minecraft, Component text, int y,
            int dotColor) {
        int textWidth = minecraft.font.width(text);
        int width = textWidth + PADDING_X * 2 + DOT_SIZE + 3;
        int x = (minecraft.getWindow().getGuiScaledWidth() - width) / 2;
        graphics.fill(x, y, x + width, y + BAR_HEIGHT, BACKGROUND);
        graphics.fill(x, y, x + width, y + 1, BORDER);
        graphics.fill(x, y + BAR_HEIGHT - 1, x + width, y + BAR_HEIGHT, BORDER);
        graphics.fill(x, y, x + 1, y + BAR_HEIGHT, BORDER);
        graphics.fill(x + width - 1, y, x + width, y + BAR_HEIGHT, BORDER);
        graphics.fill(x + PADDING_X, y + (BAR_HEIGHT - DOT_SIZE) / 2,
                x + PADDING_X + DOT_SIZE, y + (BAR_HEIGHT - DOT_SIZE) / 2 + DOT_SIZE, dotColor);
        graphics.drawString(minecraft.font, text, x + PADDING_X + DOT_SIZE + 3, y + 3,
                TEXT_COLOR, false);
    }
}
