package io.github.crafteve.ae2vf.network;

/**
 * 线协议上限。
 *
 * <p>不是玩法上限：解码时用 {@code min(有效配置值, HARD_WIRE_ENTRIES)}，防止恶意超大包把服务端
 * 内存打爆。玩法上限在 {@code IdleFactoryConfig}。</p>
 */
public final class NetworkLimit {
    /** 每个包允许的条目硬上限。 */
    public static final int HARD_WIRE_ENTRIES = 32768;

    private NetworkLimit() {
        // 纯常量持有类
    }

    /**
     * 把有效配置上限夹到线协议硬上限。
     *
     * @param effective 有效配置上限
     * @return 解码可用的条目上限
     */
    public static int wireLimit(int effective) {
        return Math.min(effective, HARD_WIRE_ENTRIES);
    }
}
