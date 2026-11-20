package io.github.crafteve.idlefactory.menu;

import appeng.api.config.Setting;
import appeng.api.config.SortDir;

/**
 * 两个终端共用的物品设置。
 *
 * <p>沿用 AE2 无线终端的做法：设置由 {@code IConfigManager} 读写，最终落到物品的
 * {@code ae2:exported_settings} 组件里。键名只用一段标识，不放进 AE2 的全局
 * {@code Settings} 注册表，避免和 AE 的设置键冲突。</p>
 *
 * <p>键字符串是落盘契约：改键名等于让玩家终端里的旧设置失效，只能新增、不能改名。</p>
 */
public final class TerminalSetting {
    /** 被动 IO 终端的列表 / 卡片视图；默认列表。键名沿用旧字符串，兼容已有终端。 */
    public static final Setting<PassiveIoViewMode> VIEW =
            new Setting<>("passive_io_view", PassiveIoViewMode.class);
    /** 排序档位；默认按名称。 */
    public static final Setting<TerminalSortMode> SORT_MODE =
            new Setting<>("terminal_sort_mode", TerminalSortMode.class);
    /** 排序方向；默认升序。 */
    public static final Setting<SortDir> SORT_DIRECTION =
            new Setting<>("terminal_sort_direction", SortDir.class);

    private TerminalSetting() {
        // 纯常量持有类
    }
}
