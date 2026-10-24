package io.github.crafteve.idlefactory.client.theme;

/**
 * GUI 主题色板。
 *
 * <p>所有界面颜色都必须从这里取，界面代码里不再出现颜色字面量。默认是深色主题；
 * 后续要切 AE2 亮色风格时，只需调用 {@link #setCurrent(GuiTheme)}（设置界面/命令留到后面做）。</p>
 *
 * <p>除需求列出的 10 个颜色外，额外补了两个界面确实会用到、又无法由已有色推导的颜色：
 * {@link #slotBorderHover()}（格子悬停边框目标色）与 {@link #error()}（网络丢失等错误提示）。</p>
 */
public record GuiTheme(
        /** 面板底色。 */
        int panelBg,
        /** 面板边框。 */
        int panelBorder,
        /** 格子底色。 */
        int slotBg,
        /** 格子上/左暗边。 */
        int slotBorderDark,
        /** 格子下/右亮边。 */
        int slotBorderLight,
        /** 强调色（标题、选中、光标）。 */
        int accent,
        /** 主文字。 */
        int textPrimary,
        /** 次要文字。 */
        int textDim,
        /** 悬停背景。 */
        int hoverBg,
        /** 选中边框。 */
        int selectedBorder,
        /** 选中外发光（带 alpha）。 */
        int selectedGlow,
        /** 格子悬停时的边框目标色。 */
        int slotBorderHover,
        /** 错误/网络丢失提示色。 */
        int error) {

    /** 深色主题（M1 默认）。 */
    public static final GuiTheme DARK = new GuiTheme(
            0xFF1A1A1C, // panelBg
            0xFF2A2A2E, // panelBorder
            0xFF141416, // slotBg
            0xFF0A0A0B, // slotBorderDark
            0xFF2E2E33, // slotBorderLight
            0xFF4DD0E1, // accent
            0xFFE6E6E6, // textPrimary
            0xFF6B6B72, // textDim
            0xFF232328, // hoverBg
            0xFF4DD0E1, // selectedBorder
            0x554DD0E1, // selectedGlow
            0xFF4A4A52, // slotBorderHover
            0xFFE57373  // error
    );

    /** AE2 亮色主题：浅灰面板、深灰格子、深色文字、蓝色强调。 */
    public static final GuiTheme LIGHT = new GuiTheme(
            0xFFC6C6C6, // panelBg
            0xFF8B8B8B, // panelBorder
            0xFF8B8B8B, // slotBg
            0xFF373737, // slotBorderDark
            0xFFFFFFFF, // slotBorderLight
            0xFF2E86C1, // accent
            0xFF1F1F1F, // textPrimary
            0xFF5A5A5A, // textDim
            0xFFAFAFAF, // hoverBg
            0xFF2E86C1, // selectedBorder
            0x552E86C1, // selectedGlow
            0xFFFFFFFF, // slotBorderHover
            0xFFB03A2E  // error
    );

    /** 当前主题；设置界面接入前一直是 {@link #DARK}。 */
    private static volatile GuiTheme current = DARK;

    /**
     * 当前主题。界面在构造/绘制时读取一次即可。
     *
     * @return 当前主题，默认 {@link #DARK}
     */
    public static GuiTheme current() {
        return current;
    }

    /**
     * 切换当前主题（预留给后续设置项或命令）。
     *
     * @param theme 新主题；传 {@code null} 会恢复 {@link #DARK}
     */
    public static void setCurrent(GuiTheme theme) {
        current = theme != null ? theme : DARK;
    }
}
