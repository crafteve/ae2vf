package io.github.crafteve.idlefactory.client.screen.paint;

/**
 * 状态的显示文字。
 *
 * <p>两个终端、搜索字段与 tooltip 都从这里取词，保证同一档位在任何地方写法一致。</p>
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
        return switch (kind) {
            case OFFLINE -> "失联";
            case PARTIAL -> "缺机";
            case STALE -> "健康过期";
            case UNPROBED -> "未探测";
            case UNCONFIGURED -> "无规则";
            case RUNNING -> "运行";
            case INPUT_OK -> "可输入";
            case WAITING_MATERIAL -> "仓库缺料";
            case INPUT_BLOCKED -> "输入堵塞";
            case IDLE -> "空闲";
            case UNKNOWN -> "未知";
        };
    }
}
