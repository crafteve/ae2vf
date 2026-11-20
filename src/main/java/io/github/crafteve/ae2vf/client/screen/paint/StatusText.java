package io.github.crafteve.ae2vf.client.screen.paint;

import net.minecraft.network.chat.Component;

/**
 * 状态的显示文字。
 *
 * <p>两个终端、搜索字段与 tooltip 都从这里取词，保证同一档位在任何地方写法一致；文案走
 * {@code idlefactory.status.*} 语言键，中英各一份。</p>
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
            case OFFLINE -> "idlefactory.status.offline";
            case PARTIAL -> "idlefactory.status.partial";
            case STALE -> "idlefactory.status.stale";
            case UNPROBED -> "idlefactory.status.unprobed";
            case UNCONFIGURED -> "idlefactory.status.unconfigured";
            case RUNNING -> "idlefactory.status.running";
            case INPUT_OK -> "idlefactory.status.input_ok";
            case WAITING_MATERIAL -> "idlefactory.status.waiting_material";
            case INPUT_BLOCKED -> "idlefactory.status.input_blocked";
            case IDLE -> "idlefactory.status.idle";
            case UNKNOWN -> "idlefactory.status.unknown";
        };
    }
}
