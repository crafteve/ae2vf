package io.github.crafteve.ae2vf.node;

import java.util.Locale;

import org.jetbrains.annotations.Nullable;

/**
 * 节点级倍率模式：关、固定倍率、智能倍率。
 *
 * <p>倍率决定 AE2 看到的样板份数：关与固定都直接给出倍数；智能按「一台机器能装几份」
 * 在派单时探测。三种模式共用同一份样板与同一个 PatternContainer，所以它是节点上的一个字段，
 * 而不是另建一种节点。</p>
 */
public enum MultiplierMode {
    /** 关：按样板原始数量派单。 */
    OFF,
    /** 固定倍率：始终使用节点上配置的倍数。 */
    FIXED,
    /** 智能倍率：按目标机器容量探测出倍数，失败回退 ×1。 */
    SMART;

    /**
     * 读存档里的模式名。
     *
     * @param raw 存档字符串；缺失或非法时为空串
     * @return 模式；无法识别时按 {@link #OFF}，旧档缺字段即为不倍率
     */
    public static MultiplierMode read(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) {
            return OFF;
        }
        try {
            return valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return OFF;
        }
    }
}
