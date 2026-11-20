package io.github.crafteve.idlefactory.client.screen.paint;

/**
 * tooltip 的霓虹配色。
 *
 * <p>原版 tooltip 背景是深色，这里只给文字上色和加粗，不替换原版渲染管线。颜色属于 tooltip
 * 这一表现组件，不再集中到主题类。</p>
 */
public final class TooltipPalette {
    /** 标题。 */
    public static final int TITLE = 0xFFFFFFFF;
    /** 标签 / 单位。 */
    public static final int LABEL = 0xFFC9D4E4;
    /** 数值 / 速率。 */
    public static final int VALUE = 0xFF4DE3FF;
    /** 面。 */
    public static final int FACE = 0xFFC792EA;
    /** 序号。 */
    public static final int INDEX = 0xFFFFB86B;
    /** 正常。 */
    public static final int OK = 0xFF5CE66B;
    /** 警告。 */
    public static final int WARN = 0xFFFFB84D;
    /** 错误。 */
    public static final int ERROR = 0xFFFF5A7A;
    /** 空闲。 */
    public static final int IDLE = 0xFF9AA8BF;

    private TooltipPalette() {
        // 纯静态查表
    }
}
