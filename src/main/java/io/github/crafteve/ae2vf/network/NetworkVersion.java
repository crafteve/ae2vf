package io.github.crafteve.ae2vf.network;

/**
 * 网络版本。
 *
 * <p>双方不一致时 NeoForge 会拒绝连接：改包结构（增删字段、改字段顺序、改类型）就要把它加一。
 * 首个公开测试版从 1 开始，此前的内部编号（29–42）只留在 ADR 里。</p>
 */
public final class NetworkVersion {
    /** 网络版本号；改包结构时递增。 */
    public static final String PROTOCOL_VERSION = "1";

    private NetworkVersion() {
        // 纯常量持有类
    }
}
