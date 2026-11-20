package io.github.crafteve.idlefactory.client.screen.theme;


/**
 * 界面绘制层级（z）。
 *
 * <p>原版把物品图标画在 150、数量与耐久装饰画在 200；本模组把仓库 / 背包的自定义数量画在
 * 300。浮层必须高于这些内容层，否则会被物品或数量压住；原版 tooltip 固定在 400，所以浮层
 * 和拖拽幽灵都要低于 400。</p>
 *
 * <p>新加图层先在这里加一项再引用，禁止在调用点写魔法数。深度测试按 z 比较、不看绘制顺序，
 * 所以「后画」不能代替「更高层」：OVERLAY 里的弹窗必须整体抬到 ITEM 之上，否则列表里的
 * 物品图标会盖在弹窗上。</p>
 */
public enum GuiLayerSpec {
    /** 面板、文字、槽位、按钮等基础 2D 层。 */
    BASE(0.0F),
    /** 原版物品图标（{@code GuiGraphics.renderItem} 自带 150 偏移）。 */
    ITEM(150.0F),
    /** 原版数量与耐久装饰（{@code renderItemDecorations} 自带 200 偏移）。 */
    ITEM_DECORATION(200.0F),
    /** 仓库 / 背包的自定义数量。 */
    GRID_COUNT(300.0F),
    /** 确认浮层等覆盖内容的浮层。 */
    OVERLAY(360.0F),
    /** 拖拽时跟随鼠标的物品幽灵。 */
    DRAG(370.0F),
    /** 原版 tooltip；浮层打开时由各屏幕自行抑制。 */
    TOOLTIP(400.0F);

    private final float z;

    GuiLayerSpec(float z) {
        this.z = z;
    }

    /**
     * 绘制 z 值。
     *
     * @return z
     */
    public float z() {
        return this.z;
    }
}
