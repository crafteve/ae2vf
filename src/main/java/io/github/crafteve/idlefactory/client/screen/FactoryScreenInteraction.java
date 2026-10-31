package io.github.crafteve.idlefactory.client.screen;

/**
 * 渲染器需要的拖拽/浮层只读快照。
 *
 * <p>渲染层不直接依赖 {@link FactoryEditController}：它只需要知道现在有没有浮层、正在拖
 * 什么。把这几个查询收成接口后，渲染器只读本接口，编辑控制器负责实现，避免渲染与编辑
 * 状态互相持有。</p>
 *
 * <p>P1 删除产线后不再有「拖机器进产线」的交互，机器拖拽相关的查询随之删除，
 * 只剩资源拖拽与浮层。</p>
 */
interface FactoryScreenInteraction {

    /**
     * 是否有确认浮层、资源拖拽等需要优先消费输入的浮层打开。
     *
     * @return 打开为 {@code true}
     */
    boolean isOverlayActive();

    /**
     * 是否正在拖拽仓库资源。
     *
     * @return 拖拽资源为 {@code true}
     */
    boolean isDraggingResource();
}
