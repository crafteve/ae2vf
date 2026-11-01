package io.github.crafteve.idlefactory.client.screen.terminal;

/**
 * 工厂终端左面板与右终端共用的尺寸常量。
 *
 * <p>窗口是「按钮带 + 左 195 管理面板 + 右 195 AE 终端」，总宽 414。左面板按 AE 总线
 * 的标记格布局定：一行 9 格，信息只走 tooltip。
 * 所有坐标都从终端原点推算，避免屏幕缩放时两块各算一份。</p>
 */
public final class FactoryTerminalGeometry {
    /** 左管理面板宽度；按旧工作台四列布局定。 */
    public static final int PANEL_WIDTH = 195;
    /** 右侧 AE 终端宽度；必须与 AE2 终端样式图一致。 */
    public static final int TERMINAL_WIDTH = 195;
    /** AE2 竖向按钮栏在窗口左侧占用的宽度。 */
    public static final int TOOLBAR_RESERVE = 24;
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
    /** 输入/输出标识边长；直接用 AE2 的 16×16 方向箭头。 */
    public static final int MARKER_SIZE = 16;
    /** 输入/输出标识左边界（相对内容区左边界）。 */
    public static final int MARKER_LEFT = 2;
    /** 槽位行左边界（相对内容区左边界），要给左侧标识留出 16px 和 4px 间距。 */
    public static final int SLOT_LEFT = 22;
    /** 槽位块边长；含 1px 网格边，相邻两块拼成 2px 网格线。 */
    public static final int SLOT_BLOCK = 18;
    /** 槽位内部边长；物品图标正好 16×16 满格。 */
    public static final int SLOT_INNER = 16;
    /** 槽位列数。 */
    public static final int SLOT_COLS = 9;
    /** 工作区顶部内阴影高度。 */
    public static final int WORK_SHADOW_HEIGHT = 2;
    /** 搜索框左边界（相对面板左上角）；与 AE2 终端样式里的 {@code widgets.search.left} 对齐。 */
    public static final int SEARCH_LEFT = 80;
    /** 搜索框上边界（相对面板左上角）；与 AE2 终端样式里的 {@code widgets.search.top} 对齐。 */
    public static final int SEARCH_TOP = 4;
    /** 搜索框宽度；与 AE2 终端样式里的 {@code widgets.search.width} 一致。 */
    public static final int SEARCH_WIDTH = 89;
    /** 搜索框占位文字颜色；取自 AE2 调色板的 {@code TEXTFIELD_PLACEHOLDER}。 */
    public static final int SEARCH_PLACEHOLDER = 0xFFDEDFE3;
    /** 搜索框输入文字颜色；取自 AE2 调色板的 {@code TEXTFIELD_TEXT}。 */
    public static final int SEARCH_TEXT = 0xFFF2F2F2;
    /** 普通文本颜色；与 AE2 终端的 {@code DEFAULT_TEXT_COLOR} 一致。 */
    public static final int TEXT_COLOR = 0xFF413F54;
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
     * AE2 竖向按钮栏相对终端原点的 x 偏移：把按钮栏放到左面板外侧，并让按钮贴住面板左边框。
     *
     * <p>AE2 的 {@code VerticalButtonBar.updateBeforeRender} 把按钮右边缘放在
     * {@code position.x - 2}，所以 position.x 取 {@code -PANEL_WIDTH + 2} 时按钮右边缘正好
     * 落在面板左边缘上，中间不再留出会透出世界背景的竖缝。</p>
     *
     * @return 负偏移
     */
    public static int toolbarOffsetX() {
        return -PANEL_WIDTH + 2;
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
