package io.github.crafteve.ae2vf.client.screen.paint;

import net.minecraft.network.chat.Component;

/**
 * 状态的显示文字。
 *
 * <p>两个终端、搜索字段与 tooltip 都从这里取词，保证同一档位在任何地方写法一致；文案走
 * {@code ae2vf.status.*} 语言键，中英各一份。</p>
 */
public final class StatusText {
    private StatusText() {
        // 纯静态查表
    }

    /**
     * 档位对应的短词。
     *
     * @param kind 档位
     * @return 文字
     */
    public static String of(StatusKind kind) {
        return Component.translatable(key(kind)).getString();
    }

    /**
     * 档位对应的语言键。
     *
     * @param kind 档位
     * @return 语言键
     */
    public static String key(StatusKind kind) {
        return switch (kind) {
            case OFFLINE -> "ae2vf.status.offline";
            case PARTIAL -> "ae2vf.status.partial";
            case STALE -> "ae2vf.status.stale";
            case UNPROBED -> "ae2vf.status.unprobed";
            case UNCONFIGURED -> "ae2vf.status.unconfigured";
            case RUNNING -> "ae2vf.status.running";
            case INPUT_OK -> "ae2vf.status.input_ok";
            case WAITING_MATERIAL -> "ae2vf.status.waiting_material";
            case INPUT_BLOCKED -> "ae2vf.status.input_blocked";
            case IDLE -> "ae2vf.status.idle";
            case UNKNOWN -> "ae2vf.status.unknown";
        };
    }
}
