package io.github.crafteve.idlefactory.factory;

/**
 * 绑定杖的草稿模式。
 *
 * <p>SINGLE 是「一次接一个方块」的 1:1 语义；STACK 是「一次接多台单方块机器」。模式由客户端
 * 草稿持有，服务端只按提交包里的模式重新校验。</p>
 *
 * <p>多方块删除后不再有 MULTIBLOCK / STACKED_MULTIBLOCK：机器与方块已经统一，多台机器由
 * 节点承载，不需要「一台机器占多个方块」的模式。</p>
 */
public enum BindingMode {
    /** 单方块单机器。 */
    SINGLE,
    /** 堆叠：多台单方块机器。 */
    STACK;

    /**
     * 读存档 / 网络里的模式名。
     *
     * @param raw 模式名
     * @return 模式；无法识别时按 {@link #SINGLE}
     */
    public static BindingMode read(String raw) {
        for (BindingMode mode : values()) {
            if (mode.name().equals(raw)) {
                return mode;
            }
        }
        return SINGLE;
    }
}
