package io.github.crafteve.idlefactory.factory;


import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerLevel;
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

    /**
     * 发一条消息。
     *
     * @param player  接收者
     * @param message 消息
     */
    public static void send(ServerPlayer player, Component message) {
        player.sendSystemMessage(message);
    }

    /**
     * 给玩家发一条成功回执。
     *
     * @param player 接收者
     * @param text   文本
     */
    public static void ok(ServerPlayer player, String text) {
        send(player, ok(text));
    }

    /**
     * 给玩家发一条失败回执。
     *
     * @param player 接收者
     * @param text   文本
     */
    public static void err(ServerPlayer player, String text) {
        send(player, err(text));
    }

    /**
     * 给玩家发一条说明。
     *
     * @param player 接收者
     * @param text   文本
     */
    public static void info(ServerPlayer player, String text) {
        send(player, info(text));
    }

    /**
     * 给玩家发一条警告。
     *
     * @param player 接收者
     * @param text   文本
     */
    public static void warn(ServerPlayer player, String text) {
        send(player, warn(text));
    }

    /**
     * 回执里的「退回样板」尾巴；没有退回时为空白。
     *
     * @param returned 退回的样板数
     * @return 如「，退回 1 块样板」
     */
    public static String patternSuffix(int returned) {
        return returned > 0 ? "，退回 " + returned + " 块样板" : "";
    }

    /**
     * 方块的显示名：短坐标 + 方块名；区块未加载时只有短坐标。
     *
     * @param level 服务端世界
     * @param pos   方块坐标
     * @return 展示文本
     */
    public static String blockLabel(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return pos.toShortString();
        }
        return pos.toShortString() + "（"
                + level.getBlockState(pos).getBlock().getName().getString() + "）";
    }

    /**
     * 机器的显示名；当前与方块显示名一致。
     *
     * @param level 服务端世界
     * @param pos   机器坐标
     * @return 展示文本
     */
    public static String machineLabel(ServerLevel level, BlockPos pos) {
        return blockLabel(level, pos);
    }
}
