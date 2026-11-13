package io.github.crafteve.idlefactory.factory.io;

import java.util.Locale;

import org.jetbrains.annotations.Nullable;

/**
 * 节点级派单开关：顺序 / 轮询。
 *
 * <p>派单方式是执行语义，所以类型住在 {@code io}：节点只持久化这个值。两种模式的分配方式
 * 见 {@code ActivePlanner}：顺序是逐台塞满，轮询是多机平均分。新节点默认
 * {@link #ROUND_ROBIN}，避免总是喂第一台。</p>
 */
public enum DispatchSwitch {
    /** 顺序：总是从第一台在线机器开始尝试。 */
    SEQUENTIAL,
    /** 轮询：从上次派单的下一台在线机器开始尝试。 */
    ROUND_ROBIN;

    /**
     * 读存档里的开关名。
     *
     * @param raw 存档字符串；缺失或非法时为空串
     * @return 开关；无法识别时按 {@link #ROUND_ROBIN}，这是新节点的默认值
     */
    public static DispatchSwitch read(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) {
            return ROUND_ROBIN;
        }
        try {
            return valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ROUND_ROBIN;
        }
    }
}
