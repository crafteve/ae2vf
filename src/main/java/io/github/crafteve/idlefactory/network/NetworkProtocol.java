package io.github.crafteve.idlefactory.network;

/**
 * 网络协议常量。
 *
 * <p>只放「双方必须一致」的数字：协议版本与线协议条目硬上限。负载注册在
 * {@code registry.IdleFactoryPayloads}；收发与会话在 {@code network} 包的 Port / Listener 里。</p>
 */
public final class NetworkProtocol {
    /** 网络协议版本：双方不一致时 NeoForge 会拒绝连接，改包结构时必须递增。 */
    public static final String PROTOCOL_VERSION = "1";

    /**
     * 线协议固定的条目硬上限（每个包）。
     *
     * <p>它不是玩法上限：解码时用 {@code min(有效配置值, HARD_WIRE_ENTRIES)}，防止恶意
     * 超大包把服务端内存打爆。玩法上限在 {@code IdleFactoryConfig}。</p>
     */
    public static final int HARD_WIRE_ENTRIES = 32768;

    private NetworkProtocol() {
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
