package io.github.crafteve.idlefactory.client.theme;

/**
 * GUI 主题色板。
 *
 * <p>默认是 AE2 浅色（{@link #AE2}），和 AE2 终端摆在一起不割裂；{@link #DARK} 保留给以后做切换。</p>
 *
 * <p><b>静态外观已经搬到贴图里</b>（{@code tools/gui/gen_textures.py} 生成，色值和这里一一对应）。
 * 这里留下的是**文字色与状态色**——它们要随主题变、又不能进贴图，所以留在代码里。</p>
 *
 * <p>{@code textValue} 与 {@code Tooltip} 是纯文字色（数字、速率、面、深色 tooltip），
 * 没有对应贴图，不需要改生成脚本；其余色值仍然保持「Java 常量 ↔ 生成脚本调色板」两边一致。</p>
 *
 * <p>改色必须两边一起改：这里的常量 + 生成脚本里的调色板。否则贴图和文字会脱节，而且这种脱节
 * 在截图里看不出来。</p>
 */
public record GuiTheme(
        /** 面板底色。 */
        int panelBg,
        /** 面板最外圈深色。 */
        int panelBorder,
        /** 深色内圈的亮边。 */
        int panelBorderLight,
        /** 内凹阴影（上/左）。 */
        int bevelDark,
        /** 内凹高光（下/右）。 */
        int bevelLight,
        /** 格子底色。 */
        int slotBg,
        /** 格子上/左暗边。 */
        int slotBorderDark,
        /** 格子下/右亮边。 */
        int slotBorderLight,
        /** 强调色（标题、选中）。 */
        int accent,
        /** 主文字。 */
        int textPrimary,
        /** 次要文字。 */
        int textDim,
        /** 数值/速率/数量/面等需要强调的值；只用于文字，没有对应贴图。 */
        int textValue,
        /** 深色 tooltip 的霓虹配色。 */
        Tooltip tooltip,
        /** 悬停底色。 */
        int hoverBg,
        /** 错误。 */
        int error,
        /** 状态：运行 / 就绪。 */
        int statusRun,
        /** 状态：缺料 / 警告。 */
        int statusWarn,
        /** 状态：空闲 / 未加载 / 未配置。 */
        int statusIdle) {

    /**
     * 深色 tooltip 的霓虹配色。
     *
     * <p>原版 tooltip 背景是深紫色，浅色面板的 {@code textPrimary} 放进去几乎看不见，
     * 所以单独给一套高对比色。只用于 tooltip 文字，没有贴图对应。</p>
     *
     * @param title 标题
     * @param label 标签/单位
     * @param value 数值/速率
     * @param face  面
     * @param ok    正常
     * @param warn  警告
     * @param error 错误
     * @param idle  空闲
     */
    public record Tooltip(int title, int label, int value, int face,
            int ok, int warn, int error, int idle) {
    }

    /**
     * AE2 浅色（默认）。
     *
     * <p>色值取自 AE2 1.21（19.2.10）的终端贴图与 {@code assets/ae2/screens/common/palette.json}。
     * {@code accent} 与 {@code textPrimary} 刻意同色——AE2 浅色主题里「强调」就是最深的那个靛色，
     * 不要再自创蓝色。</p>
     */
    public static final GuiTheme AE2 = new GuiTheme(
            0xFFCBCCD4, // panelBg
            0xFF413F54, // panelBorder
            0xFFF2F2F2, // panelBorderLight
            0xFFBFC0C9, // bevelDark
            0xFFD8D9DF, // bevelLight
            0xFFADB0C4, // slotBg
            0xFF878FA5, // slotBorderDark
            0xFFF2F2F2, // slotBorderLight
            0xFF33445C, // accent
            0xFF33445C, // textPrimary
            0xFF7C8BA3, // textDim
            0xFF0E8FA8, // textValue（亮青，浅色面板上更像科技终端）
            new Tooltip(0xFFFFFFFF, 0xFFA9B8CC, 0xFF4DE3FF, 0xFFFF6BD6,
                    0xFF5CE66B, 0xFFFFB84D, 0xFFFF5A7A, 0xFF93A5BC),
            0xFFDEDFE3, // hoverBg
            0xFFCE2401, // error
            0xFF3F7F8C, // statusRun
            0xFFB07822, // statusWarn
            0xFF878FA5  // statusIdle
    );

    /** 深色主题（保留，暂不作为默认；贴图目前只生成了浅色一套）。 */
    public static final GuiTheme DARK = new GuiTheme(
            0xFF1A1A1C,
            0xFF2A2A2E,
            0xFF2E2E33,
            0xFF111113,
            0xFF232328,
            0xFF141416,
            0xFF0A0A0B,
            0xFF2E2E33,
            0xFF4DD0E1,
            0xFFE6E6E6,
            0xFF6B6B72,
            0xFF4DD0E1,
            new Tooltip(0xFFFFFFFF, 0xFFA9B8CC, 0xFF4DE3FF, 0xFFFF6BD6,
                    0xFF5CE66B, 0xFFFFB84D, 0xFFFF5A7A, 0xFF93A5BC),
            0xFF2A2A30,
            0xFFE57373,
            0xFF4DD0E1,
            0xFFE0A93A,
            0xFF6B6B72
    );

    /** 当前主题；默认 {@link #AE2}。 */
    private static volatile GuiTheme current = AE2;

    /**
     * 当前主题。
     *
     * @return 当前主题，默认 {@link #AE2}
     */
    public static GuiTheme current() {
        return current;
    }

    /**
     * 切换当前主题（预留给后续设置项或命令）。
     *
     * @param theme 新主题；传 {@code null} 会恢复 {@link #AE2}
     */
    public static void setCurrent(GuiTheme theme) {
        current = theme != null ? theme : AE2;
    }
}
