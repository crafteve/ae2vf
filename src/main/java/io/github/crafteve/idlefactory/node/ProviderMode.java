package io.github.crafteve.idlefactory.node;

/**
 * 机器的搬运模式：被动常驻 IO 与主动样板派单。
 *
 * <p>被动模式只按 IO 规则在机器与仓库之间搬；主动模式除此之外还把样板广告给 AE2 的 CPU。
 * 两种模式共用同一套展示与存储，所以它是机器配置上的一个字段，而不是另建一种机器。</p>
 */
public enum ProviderMode {
    /** 只做被动 IO，不向 CPU 广告样板。 */
    PASSIVE,
    /** 广告样板并接受 CPU 派单。 */
    ACTIVE;

    /**
     * 从存档字符串读回模式。
     *
     * @param name 存档里的模式名
     * @return 对应模式；缺失或未知时按 {@link #PASSIVE} 处理，旧档因此天然兼容
     */
    public static ProviderMode read(String name) {
        for (ProviderMode mode : values()) {
            if (mode.name().equals(name)) {
                return mode;
            }
        }
        return PASSIVE;
    }
}
