package io.github.crafteve.ae2vf.client.screen.terminal;

/**
 * 两个 AE2 终端屏幕共用的外壳几何。
 *
 * <p>内容视口与工作区描边框的坐标只在这里算一次：左右内边距、滚动条预留、工作区描边宽度、
 * 标题与工作区之间的空档都是常量，两个屏幕不再各写一份。</p>
 *
 * <p>只给坐标与宽高，不持有 {@code Viewport} 类型——两个终端各自有自己的排版记录，
 * 这里保持纯数值接口，避免把排版类型也合并。</p>
 */
public final class TerminalLayout {
    /** 内容区左右内边距。 */
    public static final int CONTENT_PADDING = 4;
    /** 右侧滚动条预留宽度。 */
    public static final int SCROLLBAR_RESERVE = 20;
    /** 工作区描边框宽度。 */
    public static final int WORK_FRAME = 3;
    /** 标题与工作区之间的空档。 */
    public static final int WORK_GAP = 2;

    private TerminalLayout() {
        // 纯静态几何
    }

    /**
     * 内容区左边缘。
     *
     * @param leftPos 面板左边缘
     * @return 内容区左边缘
     */
    public static int contentX(int leftPos) {
        return leftPos + CONTENT_PADDING + WORK_FRAME;
    }

    /**
     * 内容区上边缘。
     *
     * @param topPos       面板上边缘
     * @param headerHeight 终端标题行高度
     * @return 内容区上边缘
     */
    public static int contentY(int topPos, int headerHeight) {
        return topPos + headerHeight + WORK_GAP + WORK_FRAME;
    }

    /**
     * 内容区宽度。
     *
     * @param imageWidth 终端面板宽度
     * @return 内容区宽度
     */
    public static int contentWidth(int imageWidth) {
        return imageWidth - CONTENT_PADDING * 2 - SCROLLBAR_RESERVE - WORK_FRAME * 2;
    }

    /**
     * 内容区高度。
     *
     * @param rows      终端行数
     * @param rowHeight 单行高度
     * @return 内容区高度
     */
    public static int contentHeight(int rows, int rowHeight) {
        return rows * rowHeight - WORK_GAP - WORK_FRAME * 2;
    }

    /**
     * 工作区描边框的左边缘（比内容区外扩 {@link #WORK_FRAME}）。
     *
     * @param contentX 内容区左边缘
     * @return 描边框左边缘
     */
    public static int frameX(int contentX) {
        return contentX - WORK_FRAME;
    }

    /**
     * 工作区描边框的上边缘。
     *
     * @param contentY 内容区上边缘
     * @return 描边框上边缘
     */
    public static int frameY(int contentY) {
        return contentY - WORK_FRAME;
    }

    /**
     * 工作区描边框的宽度。
     *
     * @param contentWidth 内容区宽度
     * @return 描边框宽度
     */
    public static int frameWidth(int contentWidth) {
        return contentWidth + WORK_FRAME * 2;
    }

    /**
     * 工作区描边框的高度。
     *
     * @param contentHeight 内容区高度
     * @return 描边框高度
     */
    public static int frameHeight(int contentHeight) {
        return contentHeight + WORK_FRAME * 2;
    }
}
