package io.github.crafteve.idlefactory.client.screen;

import java.util.List;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.UiConfig;
import io.github.crafteve.idlefactory.client.widget.GuiTextures;
import io.github.crafteve.idlefactory.factory.resource.ResourceKind;

import net.minecraft.resources.ResourceLocation;

/**
 * 排序按钮的状态与循环。
 *
 * <p>三个按钮固定在搜索行右侧：排序键、方向、范围。左键正向循环、右键反向；偏好写入客户端
 * CLIENT 配置（{@code config/idlefactory-client.toml}），关闭界面、重启客户端都会保留，
 * 不写存档。本类只回答「当前图标 / 是否可用 / 点击后改什么 / tooltip 写什么」，绘制与命中的
 * 几何在 {@link FactoryScreen} 和 {@link FactoryLayout}。</p>
 */
final class FactorySortButtons {
    /** 排序键按钮。 */
    static final int KEY = 0;
    /** 方向按钮。 */
    static final int DIRECTION = 1;
    /** 范围按钮。 */
    static final int SCOPE = 2;

    /** 排序变化回调。 */
    interface Callbacks {
        /** 键、方向或范围变化后调用；调用方负责重建视图。 */
        void sortChanged();
    }

    /**
     * 仓库显示范围。
     *
     * <p>FE 视为流体；化学品是目前占位的非物品资源，也归入「仅流体」，
     * 这样「仅物品」只显示真正的物品。</p>
     */
    enum Scope {
        /** 全部资源。 */
        ALL("全部"),
        /** 只显示物品。 */
        ITEMS("仅物品"),
        /** 只显示非物品：流体、FE、化学品。 */
        FLUIDS("仅流体");

        private final String label;

        Scope(String label) {
            this.label = label;
        }

        /** 菜单与 tooltip 文案。 */
        String label() {
            return this.label;
        }

        /**
         * 这类资源是否属于当前范围。
         *
         * @param kind 资源类型
         * @return 属于时为 {@code true}
         */
        boolean accepts(ResourceKind kind) {
            return switch (this) {
                case ALL -> true;
                case ITEMS -> kind == ResourceKind.ITEM;
                case FLUIDS -> kind != ResourceKind.ITEM;
            };
        }
    }

    /** 仓库排序偏好；跨界面打开保留。 */
    private static FactorySort.StorageKey storageKey = FactorySort.StorageKey.DEFAULT;
    private static boolean storageDescending = true;
    /** 仓库显示范围；与排序键独立记忆。 */
    private static Scope storageScope = Scope.ALL;
    /** 机器库排序偏好；跨界面打开保留。 */
    private static FactorySort.MachineKey machineKey = FactorySort.MachineKey.DEFAULT;
    private static boolean machineDescending = true;

    private final Callbacks callbacks;

    /**
     * @param callbacks 排序变化回调
     */
    FactorySortButtons(Callbacks callbacks) {
        this.callbacks = callbacks;
        loadFromConfig();
    }

    /**
     * 这个按钮当前是否可点。
     *
     * <p>默认顺序没有方向；范围只对仓库有效。不可点时按钮仍占位，只画压暗态，
     * 避免切 tab 时搜索框宽度跳动。</p>
     *
     * @param index      按钮下标
     * @param storageTab 当前 tab
     * @return 可点时为 {@code true}
     */
    boolean usable(int index, boolean storageTab) {
        return switch (index) {
            case KEY -> true;
            case DIRECTION -> storageTab
                    ? storageKey != FactorySort.StorageKey.DEFAULT
                    : machineKey != FactorySort.MachineKey.DEFAULT;
            case SCOPE -> storageTab;
            default -> false;
        };
    }

    /**
     * 点击按钮：左键正向循环，右键反向。
     *
     * @param index      按钮下标
     * @param storageTab 当前 tab
     * @param reverse    {@code true} 表示右键反向
     */
    void click(int index, boolean storageTab, boolean reverse) {
        if (!usable(index, storageTab)) {
            return;
        }
        switch (index) {
            case KEY -> cycleKey(storageTab, reverse);
            case DIRECTION -> toggleDirection(storageTab);
            case SCOPE -> cycleScope(reverse);
            default -> {
                return;
            }
        }
        persist();
        this.callbacks.sortChanged();
    }

    /**
     * 当前按钮图标。
     *
     * @param index      按钮下标
     * @param storageTab 当前 tab
     * @return 贴图
     */
    ResourceLocation icon(int index, boolean storageTab) {
        return switch (index) {
            case KEY -> GuiTextures.SORT_KEY;
            case DIRECTION -> (storageTab ? storageDescending : machineDescending)
                    ? GuiTextures.SORT_DESC : GuiTextures.SORT_ASC;
            case SCOPE -> switch (storageScope) {
                case ALL -> GuiTextures.SCOPE_ALL;
                case ITEMS -> GuiTextures.SCOPE_ITEMS;
                case FLUIDS -> GuiTextures.SCOPE_FLUIDS;
            };
            default -> GuiTextures.SORT_KEY;
        };
    }

    /**
     * 按钮悬停提示。
     *
     * @param index      按钮下标
     * @param storageTab 当前 tab
     * @return 逐行文案；空列表表示不提示
     */
    List<String> tooltipLines(int index, boolean storageTab) {
        return switch (index) {
            case KEY -> List.of(
                    "排序：" + (storageTab ? storageKey.label() : machineKey.label()),
                    "左键下一个 · 右键上一个");
            case DIRECTION -> {
                if (!usable(index, storageTab)) {
                    yield List.of("默认顺序没有方向");
                }
                boolean descending = storageTab ? storageDescending : machineDescending;
                yield List.of("方向：" + (descending ? "降序" : "升序"), "左键切换");
            }
            case SCOPE -> storageTab
                    ? List.of("范围：" + storageScope.label(), "左键下一个 · 右键上一个")
                    : List.of("范围只对仓库有效");
            default -> List.of();
        };
    }

    /** 仓库当前排序键。 */
    FactorySort.StorageKey storageKey() {
        return storageKey;
    }

    /** 仓库当前是否降序。 */
    boolean storageDescending() {
        return storageDescending;
    }

    /** 仓库当前显示范围。 */
    Scope storageScope() {
        return storageScope;
    }

    /** 机器库当前排序键。 */
    FactorySort.MachineKey machineKey() {
        return machineKey;
    }

    /** 机器库当前是否降序。 */
    boolean machineDescending() {
        return machineDescending;
    }

    private void cycleKey(boolean storageTab, boolean reverse) {
        if (storageTab) {
            FactorySort.StorageKey[] values = FactorySort.StorageKey.values();
            storageKey = values[Math.floorMod(
                    storageKey.ordinal() + (reverse ? -1 : 1), values.length)];
            storageDescending = storageKey.naturalDescending();
        } else {
            FactorySort.MachineKey[] values = FactorySort.MachineKey.values();
            machineKey = values[Math.floorMod(
                    machineKey.ordinal() + (reverse ? -1 : 1), values.length)];
            machineDescending = machineKey.naturalDescending();
        }
    }

    private void toggleDirection(boolean storageTab) {
        if (storageTab) {
            storageDescending = !storageDescending;
        } else {
            machineDescending = !machineDescending;
        }
    }

    private void cycleScope(boolean reverse) {
        Scope[] values = Scope.values();
        storageScope = values[Math.floorMod(
                storageScope.ordinal() + (reverse ? -1 : 1), values.length)];
    }

    /**
     * 从客户端配置读取排序偏好。
     *
     * <p>每次打开工作台都会新建本类实例，所以配置界面改过之后，下次打开界面就能读到新值。
     * 配置尚未加载或值损坏时保留默认值，不阻塞界面。</p>
     */
    private static void loadFromConfig() {
        try {
            storageKey = parseEnum(FactorySort.StorageKey.class,
                    UiConfig.STORAGE_KEY.get(), FactorySort.StorageKey.DEFAULT);
            storageDescending = UiConfig.STORAGE_DESCENDING.get();
            storageScope = parseEnum(Scope.class, UiConfig.STORAGE_SCOPE.get(), Scope.ALL);
            machineKey = parseEnum(FactorySort.MachineKey.class,
                    UiConfig.MACHINE_KEY.get(), FactorySort.MachineKey.DEFAULT);
            machineDescending = UiConfig.MACHINE_DESCENDING.get();
        } catch (RuntimeException exception) {
            IdleFactory.LOGGER.warn("读取排序偏好失败，使用默认值", exception);
        }
    }

    /**
     * 把当前排序偏好写回客户端配置。
     *
     * <p>点一次按钮就落盘，避免关闭界面或客户端崩溃时丢失；写失败只记日志，
     * 界面继续使用内存值。</p>
     */
    private static void persist() {
        try {
            UiConfig.STORAGE_KEY.set(storageKey.name());
            UiConfig.STORAGE_DESCENDING.set(storageDescending);
            UiConfig.STORAGE_SCOPE.set(storageScope.name());
            UiConfig.MACHINE_KEY.set(machineKey.name());
            UiConfig.MACHINE_DESCENDING.set(machineDescending);
            UiConfig.SPEC.save();
        } catch (RuntimeException exception) {
            IdleFactory.LOGGER.warn("保存排序偏好失败", exception);
        }
    }

    /**
     * 解析配置里的枚举名；空值或未知名字回默认。
     *
     * @param type     枚举类型
     * @param raw      配置字符串
     * @param fallback 解析失败时的默认值
     * @param <E>      枚举类型
     * @return 解析结果
     */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E fallback) {
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return fallback;
        }
    }
}
