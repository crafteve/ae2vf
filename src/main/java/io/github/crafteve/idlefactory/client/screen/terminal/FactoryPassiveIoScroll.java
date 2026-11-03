package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import appeng.client.gui.widgets.Scrollbar;

import io.github.crafteve.idlefactory.client.screen.terminal.FactoryPassiveIoLayout.Viewport;
import io.github.crafteve.idlefactory.menu.FactoryPassiveIoView;

import net.minecraft.util.Mth;

/**
 * 内容区滚动状态：像素滚动、上限夹紧与 AE2 滚动条同步。
 *
 * <p>滚动只影响绘制与命中的取值，不重建排版；内容高度变化后由调用方重新
 * {@link #clampTo(FactoryPassiveIoLayout)}。滚动条拖动（{@link #syncFromBar()}）与滚轮
 * （{@link #scrollBy(FactoryPassiveIoLayout, int)}）都写同一个 {@code scroll}。</p>
 */
final class FactoryPassiveIoScroll {
    private final Scrollbar bar;
    private int scroll;

    /**
     * @param bar AE2 右侧滚动条
     */
    FactoryPassiveIoScroll(Scrollbar bar) {
        this.bar = bar;
    }

    /** 当前滚动像素。 */
    int value() {
        return this.scroll;
    }

    /**
     * 把滚动夹到新排版的上限内，并把滚动条范围改成实际内容高度。
     *
     * @param layout 新排版
     */
    void clampTo(FactoryPassiveIoLayout layout) {
        Viewport viewport = layout.viewport();
        this.scroll = Mth.clamp(this.scroll, 0, layout.maxScroll(viewport.height()));
        this.bar.setHeight(viewport.height());
        this.bar.setRange(0, layout.maxScroll(viewport.height()), viewport.height());
        this.bar.setCurrentScroll(this.scroll);
        this.bar.setVisible(true);
    }

    /**
     * 滚轮 / 拖动后同步一次滚动条位置。
     *
     * @param layout 当前排版
     */
    void sync(FactoryPassiveIoLayout layout) {
        this.bar.setCurrentScroll(this.scroll);
    }

    /**
     * 按像素滚动。
     *
     * @param layout 当前排版
     * @param delta  正数向下
     */
    void scrollBy(FactoryPassiveIoLayout layout, int delta) {
        Viewport viewport = layout.viewport();
        this.scroll = Mth.clamp(this.scroll + delta, 0, layout.maxScroll(viewport.height()));
        this.bar.setCurrentScroll(this.scroll);
    }

    /**
     * 读取滚动条当前位置（拖动由 AE2 控件自己改），越界时夹紧。
     *
     * @param layout 当前排版
     */
    void syncFromBar(FactoryPassiveIoLayout layout) {
        int barValue = this.bar.getCurrentScroll();
        if (barValue == this.scroll) {
            return;
        }
        this.scroll = Mth.clamp(barValue, 0, layout.maxScroll(layout.viewport().height()));
    }
}
