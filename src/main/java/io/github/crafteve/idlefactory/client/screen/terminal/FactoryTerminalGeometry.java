package io.github.crafteve.idlefactory.client.screen.terminal;

/**
 * 工厂终端左面板与右终端共用的尺寸常量。
 *
 * <p>窗口是「按钮带 + 左 420 管理面板 + 右 195 AE 终端」，总宽 639。左面板按旧工作台
 * 的表格宽度定：一行一台机器，机器 / 状态 / 输入 / 输出四列加展开区，195 放不下。
 * 所有坐标都从终端原点推算，避免屏幕缩放时两块各算一份。</p>
 */
public final class FactoryTerminalGeometry {
    /** 左管理面板宽度；按旧工作台四列布局定。 */
    public static final int PANEL_WIDTH = 420;
    /** 右侧 AE 终端宽度；必须与 AE2 终端样式图一致。 */
    public static final int TERMINAL_WIDTH = 195;
    /** AE2 竖向按钮栏在窗口左侧占用的宽度。 */
    public static final int TOOLBAR_RESERVE = 24;
    /** 工具栏按钮边长；AE2 的按钮底图是 16×16。 */
    private static final int TOOLBAR_BUTTON = 16;
    /** 按钮栏到窗口左边缘的留白。 */
    private static final int TOOLBAR_INSET = 2;
    /** 面板内容区左右内边距。 */
    public static final int CONTENT_PADDING = 4;
    /** 面板头部高度。 */
    public static final int HEADER_HEIGHT = 16;
    /** 列表行高。 */
    public static final int ROW_HEIGHT = 14;
    /** 小号图标边长。 */
    public static final int ICON_SIZE = 12;
    /** 搜索框高度。 */
    public static final int SEARCH_HEIGHT = 12;
    /** 普通文本颜色；与 AE2 终端浅色底一致。 */
    public static final int TEXT_COLOR = 0xFF404040;
    /** 次要文本颜色。 */
    public static final int TEXT_MUTED = 0xFF707070;
    /** 选中行底色。 */
    public static final int ROW_SELECTED = 0x8049A6D9;
    /** 悬停行底色。 */
    public static final int ROW_HOVER = 0x2849A6D9;

    private FactoryTerminalGeometry() {
        // 纯常量持有类
    }

    /**
     * 左面板左边界。
     *
     * @param terminalLeft AE 终端左边界
     * @return 面板左边界
     */
    public static int panelLeft(int terminalLeft) {
        return terminalLeft - PANEL_WIDTH;
    }

    /**
     * 面板内容区左边界。
     *
     * @param terminalLeft AE 终端左边界
     * @return 内容左边界
     */
    public static int contentLeft(int terminalLeft) {
        return panelLeft(terminalLeft) + CONTENT_PADDING;
    }

    /**
     * 面板内容区宽度。
     *
     * @return 宽度
     */
    public static int contentWidth() {
        return PANEL_WIDTH - CONTENT_PADDING * 2;
    }

    /**
     * 面板内容区右边界。
     *
     * @param terminalLeft AE 终端左边界
     * @return 内容右边界
     */
    public static int contentRight(int terminalLeft) {
        return contentLeft(terminalLeft) + contentWidth();
    }

    /**
     * 居中用的临时宽度：让 AE2 的终端原点右移，使「按钮带 + 面板 + 终端」整体居中。
     *
     * <p>{@code leftPos = (screenWidth - imageWidth) / 2}，把 imageWidth 设成负的
     * 「按钮带 + 面板 - 终端」就能让原点落在整个窗口的正确位置。</p>
     *
     * @return 负的临时宽度
     */
    public static int centeringWidth() {
        return -(TOOLBAR_RESERVE + PANEL_WIDTH - TERMINAL_WIDTH);
    }

    /**
     * AE2 竖向按钮栏相对终端原点的 x 偏移：把按钮栏放到左面板外侧的窗口最左一条。
     *
     * @return 负偏移
     */
    public static int toolbarOffsetX() {
        return -(PANEL_WIDTH + TOOLBAR_RESERVE) + TOOLBAR_INSET + 2 + TOOLBAR_BUTTON;
    }

    /**
     * 判断鼠标是否落在面板内。
     *
     * @param terminalLeft 终端左边界
     * @param top          终端上边界
     * @param height       终端高度
     * @param mouseX       鼠标 x
     * @param mouseY       鼠标 y
     * @return 命中时为 {@code true}
     */
    public static boolean isInsidePanel(int terminalLeft, int top, int height,
            double mouseX, double mouseY) {
        return mouseX >= panelLeft(terminalLeft) && mouseX < terminalLeft
                && mouseY >= top && mouseY < top + height;
    }
}
