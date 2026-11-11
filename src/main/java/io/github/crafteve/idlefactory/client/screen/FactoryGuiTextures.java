package io.github.crafteve.idlefactory.client.screen;

import net.minecraft.resources.ResourceLocation;

import io.github.crafteve.idlefactory.IdleFactory;

/**
 * 界面贴图的资源 id。
 *
 * <p>全部由 {@code tools/gui/gen_textures.py} 离线生成，落在
 * {@code assets/idlefactory/textures/gui/}。Java 侧只引用，不拼路径字符串。</p>
 *
 * <p>第一版工作台删除后，只剩被动 IO 终端与共享渲染组件真正用到的贴图：面板、凹槽、按钮、
 * 粗分隔线与四种状态灯 / 状态条。旧工作台的表格、IO 详情、搜索框、排序图标等贴图连同常量
 * 一起删除，生成脚本里的对应条目也同步移除。</p>
 */
public final class FactoryGuiTextures {
    /** 面板底：九宫格，边框 3。 */
    public static final ResourceLocation PANEL = id("panel");
    /** 凹槽格子：九宫格，边框 1。 */
    public static final ResourceLocation SLOT = id("slot");
    /** 凸起按钮：九宫格，边框 1。 */
    public static final ResourceLocation BUTTON = id("button");
    /** 按钮悬停：九宫格，边框 1。 */
    public static final ResourceLocation BUTTON_HOVER = id("button_hover");
    /** 粗分隔线：1×1，拉伸使用。 */
    public static final ResourceLocation DIVIDER_DARK = id("divider_dark");

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

    /** 排序图标：状态（三根阶梯条）。 */
    public static final ResourceLocation SORT_STATUS = id("sort_status");
    /** 排序图标：IO 规则数（三条规则行）。 */
    public static final ResourceLocation SORT_RULE_COUNT = id("sort_rule_count");
    /** 排序图标：距离（立柱 + 双向箭头）。 */
    public static final ResourceLocation SORT_DISTANCE = id("sort_distance");
    /** 排序图标边长（16×16，与 AE2 工具栏图标同规格）。 */
    public static final int SORT_ICON_SIZE = 16;

    /**
     * AE2 搜索框：128×128，纵向三段各 12 高（普通 / 不可编辑 / 聚焦）。
     *
     * <p>直接复用 AE2 的 {@code guis/text_field.png}，切法与 AE2 的 {@code AETextField}
     * 完全相同，左右搜索框才会长得一样。</p>
     */
    public static final ResourceLocation AE2_TEXT_FIELD =
            ResourceLocation.fromNamespaceAndPath("ae2", "textures/guis/text_field.png");

    /** 面板贴图的边长（正方形，生成脚本里写死 10）。 */
    public static final int PANEL_SIZE = 10;
    /** 面板贴图的九宫格边框。 */
    public static final int PANEL_BORDER = 3;
    /** 凹槽贴图的边长（4×4）。 */
    public static final int SLOT_SIZE = 4;
    /** 按钮贴图的边长（6×6）。 */
    public static final int BUTTON_SIZE = 6;
    /** 悬停按钮 / 九宫格通用边长。 */
    public static final int IO_CELL_SIZE = 6;
    /** AE2 搜索框贴图边长（正方形）。 */
    public static final int AE2_TEXT_FIELD_SIZE = 128;
    /** AE2 搜索框纵向每段高度。 */
    public static final int AE2_TEXT_FIELD_SEGMENT = 12;

    private FactoryGuiTextures() {
        // 纯常量持有类
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "textures/gui/" + path + ".png");
    }
}
