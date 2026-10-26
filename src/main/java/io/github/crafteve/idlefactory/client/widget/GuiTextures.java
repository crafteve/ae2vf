package io.github.crafteve.idlefactory.client.widget;

import net.minecraft.resources.ResourceLocation;

import io.github.crafteve.idlefactory.IdleFactory;

/**
 * 界面贴图的资源 id。
 *
 * <p>全部由 {@code tools/gui/gen_textures.py} 离线生成，落在
 * {@code assets/idlefactory/textures/gui/}。Java 侧只引用，不拼路径字符串。</p>
 *
 * <p>新增贴图时三处一起改：生成脚本的 {@code SPECS}、这里的常量、以及 {@link NineSlice} 里
 * 对应的方法（因为边框宽度要跟着走）。</p>
 */
public final class GuiTextures {
    /** 面板底：九宫格，边框 3。 */
    public static final ResourceLocation PANEL = id("panel");
    /** 凹槽格子：九宫格，边框 1。 */
    public static final ResourceLocation SLOT = id("slot");
    /** 凸起按钮：九宫格，边框 1。 */
    public static final ResourceLocation BUTTON = id("button");
    /** 输入框：九宫格，边框 1。 */
    public static final ResourceLocation FIELD = id("field");
    /** 细分隔线：1×1，拉伸使用。 */
    public static final ResourceLocation DIVIDER = id("divider");
    /** 粗分隔线：1×1，拉伸使用。 */
    public static final ResourceLocation DIVIDER_DARK = id("divider_dark");
    /** 表头底色：1×1，拉伸使用。 */
    public static final ResourceLocation HEADER_BG = id("header_bg");
    /** 隔行底色：1×1，拉伸使用。 */
    public static final ResourceLocation ROW_ALT = id("row_alt");

    /** 面板贴图的边长（正方形，生成脚本里写死 10）。 */
    public static final int PANEL_SIZE = 10;
    /** 面板贴图的九宫格边框。 */
    public static final int PANEL_BORDER = 3;
    /** 凹槽贴图的边长（4×4）。 */
    public static final int SLOT_SIZE = 4;
    /** 按钮贴图的边长（6×6）。 */
    public static final int BUTTON_SIZE = 6;
    /** 输入框贴图的边长（4×4）。 */
    public static final int FIELD_SIZE = 4;

    private GuiTextures() {
        // 纯常量持有类
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "textures/gui/" + path + ".png");
    }
}
