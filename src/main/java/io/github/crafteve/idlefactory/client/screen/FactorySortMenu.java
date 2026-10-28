package io.github.crafteve.idlefactory.client.screen;

import java.util.ArrayList;
import java.util.List;

import io.github.crafteve.idlefactory.client.theme.GuiTheme;
import io.github.crafteve.idlefactory.client.widget.PopupMenu;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 右区排序按钮与排序菜单的控制器。
 *
 * <p>按钮底由 {@link FactoryScreen} 的静态底板层绘制；本类只提供当前键/方向文案，并负责
 * 菜单的开合、绘制、命中与偏好记忆。仓库与机器各记一套键和方向，存在静态字段里，
 * 关闭界面再打开仍保留；不写存档。</p>
 *
 * <p>菜单分三组：当前范围的排序键、「方向」标题、降序/升序。选中项用
 * {@link PopupMenu.Kind#ACTION_SELECTED} 画强调条；默认顺序下方向行置灰。</p>
 */
final class FactorySortMenu {
    /** 方向行 id：降序。 */
    private static final int DIR_DESC = 100;
    /** 方向行 id：升序。 */
    private static final int DIR_ASC = 101;
    /** 菜单最小宽度。 */
    private static final int MIN_MENU_W = 64;

    /** 排序变化回调。 */
    interface Callbacks {
        /** 键或方向变化后调用；调用方负责重建视图。 */
        void sortChanged();
    }

    /** 仓库排序偏好；跨界面打开保留。 */
    private static FactorySort.StorageKey storageKey = FactorySort.StorageKey.DEFAULT;
    private static boolean storageDescending = true;
    /** 机器库排序偏好；跨界面打开保留。 */
    private static FactorySort.MachineKey machineKey = FactorySort.MachineKey.DEFAULT;
    private static boolean machineDescending = true;

    private final Font font;
    private final GuiTheme theme;
    private final Callbacks callbacks;
    private final PopupMenu menu = new PopupMenu();

    /** 当前菜单对应的范围；打开时决定菜单项，点击后决定改哪一套偏好。 */
    private boolean storageTab;

    /**
     * @param font      字体
     * @param theme     主题
     * @param callbacks 排序变化回调
     */
    FactorySortMenu(Font font, GuiTheme theme, Callbacks callbacks) {
        this.font = font;
        this.theme = theme;
        this.callbacks = callbacks;
    }

    /** 菜单是否打开。 */
    boolean isOpen() {
        return this.menu.isOpen();
    }

    /** 关闭菜单。 */
    void close() {
        this.menu.close();
    }

    /**
     * 打开或收起排序菜单。
     *
     * <p>菜单贴着排序按钮下沿、右对齐按钮右缘；放不下时向上展开，再夹在面板内。</p>
     *
     * @param layout     当前布局
     * @param storageTab {@code true} 表示仓库，{@code false} 表示机器
     */
    void toggle(FactoryLayout layout, boolean storageTab) {
        if (this.menu.isOpen()) {
            this.menu.close();
            return;
        }
        this.storageTab = storageTab;
        List<PopupMenu.Item> items = buildItems(storageTab);
        int width = measureWidth(items);
        int height = PopupMenu.measureHeight(items, FactoryLayout.SORT_ROW_H);

        int x = layout.sortButtonX() + FactoryLayout.SORT_BTN_W - width;
        int minX = layout.panelX + FactoryLayout.PAD;
        int maxX = layout.panelX + layout.panelW - FactoryLayout.PAD - width;
        x = Math.max(minX, Math.min(maxX, x));

        int y = layout.searchY + FactoryLayout.SEARCH_H + 2;
        int maxY = layout.panelY + layout.panelH - FactoryLayout.PAD - height;
        if (y > maxY) {
            y = Math.max(layout.panelY + FactoryLayout.PAD,
                    layout.searchY - height - 2);
        }
        this.menu.open(x, y, width, FactoryLayout.SORT_ROW_H, items);
    }

    /**
     * 处理菜单打开的点击。
     *
     * <p>命中菜单项时应用并关闭；点在菜单矩形内但没命中项（标题/禁用行/边距）只消费；
     * 点在菜单外则关闭并返回 {@code false}，让调用方决定是否继续处理这次点击。</p>
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @param button 鼠标键
     * @return 是否消费
     */
    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.menu.isOpen()) {
            return false;
        }
        Integer id = this.menu.hit(mouseX, mouseY);
        if (id != null) {
            if (button == 0) {
                apply(id);
            }
            return true;
        }
        if (this.menu.contains(mouseX, mouseY)) {
            return true;
        }
        this.menu.close();
        return false;
    }

    /**
     * 画菜单；调用方保证它在右区内容之后。
     *
     * @param graphics 画布
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     */
    void draw(GuiGraphics graphics, int mouseX, int mouseY) {
        this.menu.draw(graphics, this.font, this.theme, mouseX, mouseY);
    }

    /**
     * 在当前范围翻转方向；默认顺序无动作。
     *
     * @param storageTab {@code true} 表示仓库，{@code false} 表示机器
     */
    void flipDirection(boolean storageTab) {
        if (storageTab) {
            if (storageKey == FactorySort.StorageKey.DEFAULT) {
                return;
            }
            storageDescending = !storageDescending;
        } else {
            if (machineKey == FactorySort.MachineKey.DEFAULT) {
                return;
            }
            machineDescending = !machineDescending;
        }
        this.callbacks.sortChanged();
    }

    /** 仓库当前排序键。 */
    FactorySort.StorageKey storageKey() {
        return storageKey;
    }

    /** 仓库当前是否降序。 */
    boolean storageDescending() {
        return storageDescending;
    }

    /** 机器库当前排序键。 */
    FactorySort.MachineKey machineKey() {
        return machineKey;
    }

    /** 机器库当前是否降序。 */
    boolean machineDescending() {
        return machineDescending;
    }

    /**
     * 排序按钮上的单字。
     *
     * @param storageTab 当前 tab
     * @return 当前键的缩写
     */
    String glyph(boolean storageTab) {
        return storageTab ? storageKey.glyph() : machineKey.glyph();
    }

    /**
     * 是否需要在按钮上画方向三角；默认顺序不画。
     *
     * @param storageTab 当前 tab
     * @return 非默认顺序时为 {@code true}
     */
    boolean arrowVisible(boolean storageTab) {
        return storageTab ? storageKey != FactorySort.StorageKey.DEFAULT
                : machineKey != FactorySort.MachineKey.DEFAULT;
    }

    /**
     * 当前方向是否降序。
     *
     * @param storageTab 当前 tab
     * @return 降序为 {@code true}
     */
    boolean descending(boolean storageTab) {
        return storageTab ? storageDescending : machineDescending;
    }

    /**
     * 当前排序键的完整文案。
     *
     * @param storageTab 当前 tab
     * @return 例如「数量」
     */
    String keyLabel(boolean storageTab) {
        return storageTab ? storageKey.label() : machineKey.label();
    }

    /**
     * 当前方向的文案；默认顺序为空串。
     *
     * @param storageTab 当前 tab
     * @return 「降序」/「升序」/空串
     */
    String directionLabel(boolean storageTab) {
        if (!arrowVisible(storageTab)) {
            return "";
        }
        return descending(storageTab) ? "降序" : "升序";
    }

    private List<PopupMenu.Item> buildItems(boolean storageTab) {
        List<PopupMenu.Item> items = new ArrayList<>();
        items.add(new PopupMenu.Item(storageTab ? "仓库排序" : "机器排序",
                PopupMenu.Kind.HEADER, -1));
        if (storageTab) {
            for (FactorySort.StorageKey key : FactorySort.StorageKey.values()) {
                items.add(new PopupMenu.Item(key.label(),
                        key == storageKey ? PopupMenu.Kind.ACTION_SELECTED
                                : PopupMenu.Kind.ACTION,
                        key.ordinal()));
            }
            items.add(new PopupMenu.Item("方向", PopupMenu.Kind.HEADER, -1));
            boolean enabled = storageKey != FactorySort.StorageKey.DEFAULT;
            items.add(directionItem("降序", DIR_DESC, enabled, storageDescending));
            items.add(directionItem("升序", DIR_ASC, enabled, !storageDescending));
        } else {
            for (FactorySort.MachineKey key : FactorySort.MachineKey.values()) {
                items.add(new PopupMenu.Item(key.label(),
                        key == machineKey ? PopupMenu.Kind.ACTION_SELECTED
                                : PopupMenu.Kind.ACTION,
                        key.ordinal()));
            }
            items.add(new PopupMenu.Item("方向", PopupMenu.Kind.HEADER, -1));
            boolean enabled = machineKey != FactorySort.MachineKey.DEFAULT;
            items.add(directionItem("降序", DIR_DESC, enabled, machineDescending));
            items.add(directionItem("升序", DIR_ASC, enabled, !machineDescending));
        }
        return items;
    }

    private PopupMenu.Item directionItem(String label, int id, boolean enabled, boolean selected) {
        PopupMenu.Kind kind;
        if (!enabled) {
            kind = PopupMenu.Kind.DISABLED;
        } else if (selected) {
            kind = PopupMenu.Kind.ACTION_SELECTED;
        } else {
            kind = PopupMenu.Kind.ACTION;
        }
        return new PopupMenu.Item(label, kind, id);
    }

    private int measureWidth(List<PopupMenu.Item> items) {
        int width = MIN_MENU_W;
        for (PopupMenu.Item item : items) {
            width = Math.max(width, this.font.width(item.label()) + 20);
        }
        return width;
    }

    private void apply(int id) {
        if (id == DIR_DESC) {
            setDescending(true);
        } else if (id == DIR_ASC) {
            setDescending(false);
        } else if (this.storageTab) {
            FactorySort.StorageKey[] values = FactorySort.StorageKey.values();
            if (id >= 0 && id < values.length) {
                storageKey = values[id];
                storageDescending = storageKey.naturalDescending();
            }
        } else {
            FactorySort.MachineKey[] values = FactorySort.MachineKey.values();
            if (id >= 0 && id < values.length) {
                machineKey = values[id];
                machineDescending = machineKey.naturalDescending();
            }
        }
        this.menu.close();
        this.callbacks.sortChanged();
    }

    private void setDescending(boolean descending) {
        if (this.storageTab) {
            if (storageKey == FactorySort.StorageKey.DEFAULT) {
                return;
            }
            storageDescending = descending;
        } else {
            if (machineKey == FactorySort.MachineKey.DEFAULT) {
                return;
            }
            machineDescending = descending;
        }
    }
}
