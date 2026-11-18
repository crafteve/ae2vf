package io.github.crafteve.idlefactory.io;

import java.util.Locale;

/**
 * 机器级阻挡开关：开时，机器的样板输入还在机器里就整单拒绝派单。
 *
 * <p>语义照 AE2 的样板供应器：判据只看样板的输入 key，升级插件这类不在输入集合里的东西
 * 不算阻挡物；粒度是整台机器，不是单个面。默认 {@link #OFF}。</p>
 *
 * <p>类型住在 {@code io}：节点只持久化这个值，判定跟着「这一单要投给哪台机器」走，由
 * {@code ActivePlanner} 在投递时执行。</p>
 */
public enum BlockingMode {
    /** 不阻挡：CPU 能推多少推多少，塞不下的部分进待投递队列。 */
    OFF,
    /** 阻挡：机器里还有样板输入时，这份样板不推。 */
    ON;

    /**
     * 读存档里的开关名。
     *
     * @param raw 存档字符串；缺失或非法时为空串
     * @return 开关；无法识别时按 {@link #OFF}，旧档缺字段即为不阻挡
     */
    public static BlockingMode read(String raw) {
        if (raw == null || raw.isEmpty()) {
            return OFF;
        }
        try {
            return valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return OFF;
        }
    }

    /** 是否开启阻挡。 */
    public boolean isOn() {
        return this == ON;
    }
}
