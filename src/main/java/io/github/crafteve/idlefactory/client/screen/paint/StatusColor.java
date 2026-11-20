package io.github.crafteve.idlefactory.client.screen.paint;

/**
 * 状态颜色：运行绿、警告橙、失联红、空闲灰。
 *
 * <p>只用四种状态色，和贴图（状态灯 / 状态条）一一对应；颜色写在这里，不再集中到一个主题类。</p>
 */
public final class StatusColor {
    /** 运行 / 可输入。 */
    public static final int RUN = 0xFF2E9E4F;
    /** 缺料 / 堵塞 / 未探测 / 缺机。 */
    public static final int WARN = 0xFFD18A00;
    /** 失联 / 过期。 */
    public static final int ERROR = 0xFFD63A3A;
    /** 空闲 / 未知 / 无规则。 */
    public static final int IDLE = 0xFF6B6B6B;

    private StatusColor() {
        // 纯静态查表
    }

    /**
     * 档位对应的颜色。
     *
     * @param kind 档位
     * @return ARGB 颜色
     */
    public static int of(StatusKind kind) {
        return switch (kind) {
            case RUNNING, INPUT_OK -> RUN;
            case IDLE, UNKNOWN, UNCONFIGURED -> IDLE;
            case WAITING_MATERIAL, INPUT_BLOCKED, UNPROBED, PARTIAL -> WARN;
            case OFFLINE, STALE -> ERROR;
        };
    }
}
