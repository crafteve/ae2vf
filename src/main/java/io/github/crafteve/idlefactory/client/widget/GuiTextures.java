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
    /** 仓库悬停格：18×18 固定贴图。 */
    public static final ResourceLocation SLOT_HOVER = id("slot_hover");
    /** 凸起按钮：九宫格，边框 1。 */
    public static final ResourceLocation BUTTON = id("button");
    /** 输入框：九宫格，边框 1。 */
    public static final ResourceLocation FIELD = id("field");
    /** 搜索框纯色底：1×1，拉伸使用。 */
    public static final ResourceLocation SEARCH_BG = id("search_bg");
    /** 搜索框边框：1×1，拉伸使用。 */
    public static final ResourceLocation SEARCH_BORDER = id("search_border");
    /** 搜索框内阴影：1×1，拉伸使用。 */
    public static final ResourceLocation SEARCH_SHADOW = id("search_shadow");
    /** 搜索框内高光：1×1，拉伸使用。 */
    public static final ResourceLocation SEARCH_HIGHLIGHT = id("search_highlight");
    /** 搜索放大镜：固定 9×9。 */
    public static final ResourceLocation SEARCH = id("search");
    /** 搜索清空按钮：固定 7×7。 */
    public static final ResourceLocation SEARCH_CLEAR = id("search_clear");
    /** 细分隔线：1×1，拉伸使用。 */
    public static final ResourceLocation DIVIDER = id("divider");
    /** 粗分隔线：1×1，拉伸使用。 */
    public static final ResourceLocation DIVIDER_DARK = id("divider_dark");
    /** 表头底色：1×1，拉伸使用。 */
    public static final ResourceLocation HEADER_BG = id("header_bg");
    /** 隔行底色：1×1，拉伸使用。 */
    public static final ResourceLocation ROW_ALT = id("row_alt");

    /** 凸起表头：九宫格，边框 1。 */
    public static final ResourceLocation TABLE_HEADER = id("table_header");
    /** 行卡片：九宫格，边框 1。 */
    public static final ResourceLocation ROW_CARD = id("row_card");
    /** 机器库卡片：九宫格，边框 1。 */
    public static final ResourceLocation MACHINE_CARD = id("machine_card");
    /** 表格内圈亮边：1×1。 */
    public static final ResourceLocation TABLE_LIGHT = id("table_light");

    /** IO 展开详情底板：九宫格，边框 3。 */
    public static final ResourceLocation IO_PANEL = id("io_panel");
    /** IO 规则单元格：九宫格，边框 1。 */
    public static final ResourceLocation IO_CELL = id("io_cell");
    /** IO 规则单元格悬停：九宫格，边框 1。 */
    public static final ResourceLocation IO_CELL_HOVER = id("io_cell_hover");
    /** IO 段头科技线：1×1，亮青。 */
    public static final ResourceLocation IO_ACCENT = id("io_accent");
    /** 按钮悬停：九宫格，边框 1。 */
    public static final ResourceLocation BUTTON_HOVER = id("button_hover");
    /** 收起箭头：7×7。 */
    public static final ResourceLocation CHEVRON_RIGHT = id("chevron_right");
    /** 展开箭头：7×7。 */
    public static final ResourceLocation CHEVRON_DOWN = id("chevron_down");

    /** 1px 状态条：运行。 */
    public static final ResourceLocation STATUS_STRIP_RUN = id("status_strip_run");
    /** 1px 状态条：警告。 */
    public static final ResourceLocation STATUS_STRIP_WARN = id("status_strip_warn");
    /** 1px 状态条：错误。 */
    public static final ResourceLocation STATUS_STRIP_ERROR = id("status_strip_error");
    /** 1px 状态条：空闲。 */
    public static final ResourceLocation STATUS_STRIP_IDLE = id("status_strip_idle");

    /** 4×4 状态灯：运行。 */
    public static final ResourceLocation STATUS_LAMP_RUN = id("status_lamp_run");
    /** 4×4 状态灯：警告。 */
    public static final ResourceLocation STATUS_LAMP_WARN = id("status_lamp_warn");
    /** 4×4 状态灯：错误。 */
    public static final ResourceLocation STATUS_LAMP_ERROR = id("status_lamp_error");
    /** 4×4 状态灯：空闲。 */
    public static final ResourceLocation STATUS_LAMP_IDLE = id("status_lamp_idle");

    /** 面板贴图的边长（正方形，生成脚本里写死 10）。 */
    public static final int PANEL_SIZE = 10;
    /** 面板贴图的九宫格边框。 */
    public static final int PANEL_BORDER = 3;
    /** 凹槽贴图的边长（4×4）。 */
    public static final int SLOT_SIZE = 4;
    /** 仓库悬停格边长。 */
    public static final int SLOT_HOVER_SIZE = 18;
    /** 按钮贴图的边长（6×6）。 */
    public static final int BUTTON_SIZE = 6;
    /** 输入框贴图的边长（4×4）。 */
    public static final int FIELD_SIZE = 4;
    /** 设备列表外框/表头/行卡片的边长。 */
    public static final int TABLE_FRAME_SIZE = 6;
    /** 机器库卡片边长。 */
    public static final int MACHINE_CARD_SIZE = 6;
    /** IO 详情底板边长。 */
    public static final int IO_PANEL_SIZE = 10;
    /** IO 详情底板九宫格边框。 */
    public static final int IO_PANEL_BORDER = 3;
    /** IO 单元格/段头/悬停按钮的九宫格边长。 */
    public static final int IO_CELL_SIZE = 6;
    /** 箭头贴图边长。 */
    public static final int CHEVRON_SIZE = 7;
    /** 状态灯边长。 */
    public static final int STATUS_LAMP_SIZE = 4;
    /** 搜索图标边长。 */
    public static final int SEARCH_SIZE = 9;
    /** 搜索清空图标边长。 */
    public static final int SEARCH_CLEAR_SIZE = 7;

    private GuiTextures() {
        // 纯常量持有类
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "textures/gui/" + path + ".png");
    }
}
