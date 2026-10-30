package io.github.crafteve.idlefactory.client.screen;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.menu.FactoryMenu;

import net.minecraft.client.gui.Font;

/**
 * 分区渲染器与几何共用的只读界面视图。
 *
 * <p>渲染器不直接持有 {@link FactoryScreen}，只通过本接口读布局、状态、主题和拖拽快照；
 * 这样渲染层不会反向调用界面生命周期，也不会把输入/网络逻辑带进绘制。</p>
 */
interface FactoryScreenContext {

    /**
     * 客户端状态与派生视图。
     *
     * @return 状态
     */
    FactoryScreenState state();

    /**
     * 几何/命中。
     *
     * @return 几何
     */
    FactoryScreenGeometry geometry();

    /**
     * 菜单（含玩家槽位）。
     *
     * @return 菜单
     */
    FactoryMenu menu();

    /**
     * 面板左上角屏幕 x。
     *
     * @return leftPos
     */
    int leftPos();

    /**
     * 面板左上角屏幕 y。
     *
     * @return topPos
     */
    int topPos();

    /**
     * 当前布局。
     *
     * @return 布局
     */
    FactoryLayout layout();

    /**
     * 当前主题。
     *
     * @return 主题
     */
    FactoryGuiTheme theme();

    /**
     * 当前字体。
     *
     * @return 字体
     */
    Font font();

    /**
     * 拖拽/浮层快照；init 前可能为 {@code null}。
     *
     * @return 交互快照
     */
    @Nullable
    FactoryScreenInteraction interaction();

    /**
     * 搜索框；init 前可能为 {@code null}。
     *
     * @return 搜索框
     */
    @Nullable
    FactorySearchEditBox searchBox();

    /**
     * 排序按钮；init 前可能为 {@code null}。
     *
     * @return 排序按钮
     */
    @Nullable
    FactorySortButtons sortButtons();

    /**
     * 本帧鼠标 x。
     *
     * @return 鼠标 x
     */
    int mouseX();

    /**
     * 本帧鼠标 y。
     *
     * @return 鼠标 y
     */
    int mouseY();
}
