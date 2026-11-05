package io.github.crafteve.idlefactory.factory.node;

import java.util.Locale;

import org.jetbrains.annotations.Nullable;

/**
 * 节点级派单模式：顺序、轮询。
 *
 * <p>一次派单只选一台在线机器，整份货给它；派单模式只决定从哪一台开始挑，
 * 不决定拆分。新节点默认 {@link #ROUND_ROBIN}，避免总是喂第一台。</p>
 */
public enum DispatchMode {
    /** 顺序：总是从第一台在线机器开始尝试。 */
    SEQUENTIAL,
    /** 轮询：从上次派单的下一台在线机器开始尝试。 */
    ROUND_ROBIN;

    /**
     * 读存档里的模式名。
     *
     * @param raw 存档字符串；缺失或非法时为空串
     * @return 模式；无法识别时按 {@link #ROUND_ROBIN}，这是新节点的默认值
     */
    public static DispatchMode read(@Nullable String raw) {
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
