package io.github.crafteve.idlefactory.menu;

import appeng.api.config.Setting;

/**
 * 被动 IO 终端自己的物品设置。
 *
 * <p>沿用 AE2 无线终端的做法：设置由 {@code IConfigManager} 读写，最终落到物品的
 * {@code ae2:exported_settings} 组件里。键名只用一段标识，不放进 AE2 的全局
 * {@code Settings} 注册表，避免和 AE 的设置键冲突。</p>
 */
public final class FactoryPassiveIoSetting {
    /** 列表 / 卡片视图；默认列表。 */
    public static final Setting<FactoryPassiveIoView> VIEW =
            new Setting<>("passive_io_view", FactoryPassiveIoView.class);

    private FactoryPassiveIoSetting() {
        // 纯常量持有类
    }
}
