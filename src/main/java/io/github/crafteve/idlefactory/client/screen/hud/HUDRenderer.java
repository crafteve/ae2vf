package io.github.crafteve.idlefactory.client.screen.hud;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
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
 * 模组统一的 HUD 条渲染：深底、1px 边框、左侧状态点、正文。
 *
 * <p>绑定杖、ME 绑定杖、定位提示与命令回执都用这一套，不再往原版动作栏塞白字。
 * 闪条同一时刻最多一条，新的覆盖旧的；{@link #drawBar} 给常驻条复用。</p>
 */
@EventBusSubscriber(modid = IdleFactory.MODID, bus = EventBusSubscriber.Bus.GAME,
        value = Dist.CLIENT)
public final class HUDRenderer {
    /** 成功色。 */
    public static final int RUN = 0xFF2E9E4F;
    /** 警告 / 中性色。 */
    public static final int WARN = 0xFFD18A00;
    /** 失败色。 */
    public static final int ERROR = 0xFFD63A3A;
    /** 次要文字色（常驻条）。 */
    public static final int TEXT_DIM = 0xFF5F5F5F;
    /** 深底。 */
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
    /** 闪条距屏幕底部的距离，避开快捷栏。 */
    private static final int BOTTOM_INSET = 58;
    /** 闪条停留时间（tick）。 */
    private static final int FLASH_TICKS = 60;

    @Nullable
    private static Component flashText;
    private static int flashColor = 0xFFFFFFFF;
    private static long flashExpireTick;

    private HUDRenderer() {
        // 纯静态渲染
    }

    /**
     * 绿色闪条：成功。
     *
     * @param text 文案
     */
    public static void success(Component text) {
        flash(text, RUN);
        play(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F);
    }

    /**
     * 红色闪条：失败。
     *
     * @param text 文案
     */
    public static void failure(Component text) {
        flash(text, ERROR);
        play(SoundEvents.VILLAGER_NO, 1.0F);
    }

    /**
     * 黄色闪条：中性提示。
     *
     * @param text 文案
     */
    public static void neutral(Component text) {
        flash(text, WARN);
        play(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F);
    }

    /** 轻提示音。 */
    public static void click() {
        play(SoundEvents.UI_BUTTON_CLICK.value(), 1.4F);
    }

    /** 清掉闪条。 */
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

    private static void play(SoundEvent event, float pitch) {
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.playSound(event, 0.35F, pitch);
        }
    }

    /**
     * 每帧画闪条；界面开着也照画，提示不该被界面吞掉。
     *
     * @param event HUD 渲染事件
     */
    @SubscribeEvent
    static void onRender(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.options.hideGui || flashText == null) {
            return;
        }
        int barY = minecraft.getWindow().getGuiScaledHeight() - BOTTOM_INSET;
        drawBar(event.getGuiGraphics(), minecraft.font, flashText, barY, flashColor);
    }

    /**
     * 画一条 HUD：深底、1px 边框、左侧状态点、正文，水平居中。
     *
     * @param graphics 绘制上下文
     * @param font     字体
     * @param text     文案
     * @param y        顶边
     * @param dotColor 状态点颜色
     */
    public static void drawBar(GuiGraphics graphics, Font font, Component text, int y,
            int dotColor) {
        int textWidth = font.width(text);
        int width = textWidth + PADDING_X * 2 + DOT_SIZE + 3;
        int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int x = (screenWidth - width) / 2;
        graphics.fill(x, y, x + width, y + BAR_HEIGHT, BACKGROUND);
        graphics.fill(x, y, x + width, y + 1, BORDER);
        graphics.fill(x, y + BAR_HEIGHT - 1, x + width, y + BAR_HEIGHT, BORDER);
        graphics.fill(x, y, x + 1, y + BAR_HEIGHT, BORDER);
        graphics.fill(x + width - 1, y, x + width, y + BAR_HEIGHT, BORDER);
        graphics.fill(x + PADDING_X, y + (BAR_HEIGHT - DOT_SIZE) / 2,
                x + PADDING_X + DOT_SIZE, y + (BAR_HEIGHT - DOT_SIZE) / 2 + DOT_SIZE, dotColor);
        graphics.drawString(font, text, x + PADDING_X + DOT_SIZE + 3, y + 3, TEXT_COLOR, false);
    }
}
