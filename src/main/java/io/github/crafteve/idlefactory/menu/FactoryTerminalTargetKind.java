package io.github.crafteve.idlefactory.menu;

/**
 * 主动合成目标的两类来源：工厂自己的虚拟机器与 ME 网络上 AE2 原生的样板容器。
 *
 * <p>两类目标在列表里并排展示，但槽位由不同的库存实现提供：机器走
 * {@code MachinePatternInventory}，AE2 设备走它自己的 {@code PatternContainer}。用一个
 * 枚举区分，避免在界面里靠 {@code instanceof} 猜来源。</p>
 */
public enum FactoryTerminalTargetKind {
    /** 工厂机器库里的虚拟样板供应器。 */
    MACHINE,
    /** 同一 ME 网络上 AE2 的样板供应器 / 接口等原生样板容器。 */
    AE2_PROVIDER;

    /**
     * 从网络序号读回类型。
     *
     * @param ordinal 序号
     * @return 类型
     * @throws IllegalArgumentException 序号越界
     */
    public static FactoryTerminalTargetKind read(int ordinal) {
        for (FactoryTerminalTargetKind kind : values()) {
            if (kind.ordinal() == ordinal) {
                return kind;
            }
        }
        throw new IllegalArgumentException("未知的终端目标类型：" + ordinal);
    }
}
