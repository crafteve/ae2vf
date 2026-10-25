package io.github.crafteve.idlefactory.factory;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;

/**
 * 工厂状态对玩家的表述层：聊天栏消息的唯一出口。
 *
 * <p>它和 {@link FactorySnapshotBuilder} 是一对：一个出摘要文本，一个出带样式的消息组件。
 * 颜色和点击行为是工厂的视觉规范，不做成通用工具——通用工具最后都会变成杂物间。</p>
 *
 * <p>聊天栏一行就是一条消息，不要拼 {@code \n}；多行请分多次 {@link #send}。</p>
 */
public final class FactoryMessages {
    /** 成功。 */
    public static final int OK = 0x55FF55;
    /** 警告。 */
    public static final int WARN = 0xFFAA00;
    /** 错误。 */
    public static final int ERR = 0xFF5555;
    /** 说明文字。 */
    public static final int INFO = 0x9E9E9E;
    /** 正文。 */
    public static final int PLAIN = 0xE0E0E0;
    /** 可点的 id。 */
    public static final int ID = 0x55FFFF;
    /** 更暗的辅助文字。 */
    public static final int DIM = 0x7F7F7F;

    private FactoryMessages() {
        // 纯静态工具类
    }

    /**
     * 一段带颜色的文本。
     *
     * @param text  文本
     * @param color 颜色
     * @return 组件
     */
    public static MutableComponent text(String text, int color) {
        return Component.literal(text).withStyle(style -> style.withColor(TextColor.fromRgb(color)));
    }

    /** 成功消息：绿色 {@code [OK]} 前缀。 */
    public static Component ok(String body) {
        return Component.empty().append(text("[OK] ", OK)).append(text(body, PLAIN));
    }

    /** 警告消息：黄色 {@code [!!]} 前缀。 */
    public static Component warn(String body) {
        return Component.empty().append(text("[!!] ", WARN)).append(text(body, PLAIN));
    }

    /** 错误消息：红色 {@code [XX]} 前缀。 */
    public static Component err(String body) {
        return Component.empty().append(text("[XX] ", ERR)).append(text(body, PLAIN));
    }

    /** 普通说明，无前缀。 */
    public static Component info(String body) {
        return text(body, INFO);
    }

    /** 正文，无前缀。 */
    public static Component plain(String body) {
        return text(body, PLAIN);
    }

    /**
     * 可点击的链接文本。
     *
     * @param label   显示文字
     * @param command 点击后执行的指令（带前导斜杠）
     * @param hover   悬停说明
     * @return 组件
     */
    public static MutableComponent link(String label, String command, String hover) {
        return Component.literal(label).withStyle(style -> style
                .withColor(TextColor.fromRgb(ID))
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
    }

    /**
     * 只有悬停、不点击的 id 文本。
     *
     * @param label 显示文字
     * @param hover 悬停说明
     * @return 组件
     */
    public static MutableComponent hover(String label, String hover) {
        return Component.literal(label).withStyle(style -> style
                .withColor(TextColor.fromRgb(ID))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
    }

    /**
     * 产线短 id，点击查看这条产线。
     *
     * @param shortId 产线短 id
     * @return 组件
     */
    public static MutableComponent lineLink(String shortId) {
        return link(shortId, "/idf line " + shortId, "查看产线 " + shortId);
    }

    /**
     * 发一条消息。
     *
     * @param player  接收者
     * @param message 消息
     */
    public static void send(ServerPlayer player, Component message) {
        player.sendSystemMessage(message);
    }
}
