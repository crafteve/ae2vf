package io.github.crafteve.idlefactory.client.screen;

import io.github.crafteve.idlefactory.factory.machine.MachineRef;

/**
 * 渲染器需要的拖拽/浮层只读快照。
 *
 * <p>渲染层不直接依赖 {@link FactoryEditController}：它只需要知道现在有没有浮层、正在拖
 * 什么、某个机器是不是拖拽源。把这几个查询收成接口后，渲染器只读本接口，编辑控制器
 * 负责实现，避免渲染与编辑状态互相持有。</p>
 */
interface FactoryScreenInteraction {

    /**
     * 是否有命名/确认/产线菜单等浮层打开。
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

    /**
     * 是否正在拖拽机器。
     *
     * @return 拖拽机器为 {@code true}
     */
    boolean isDraggingMachine();

    /**
     * 拖拽中的机器名；没有拖机器时返回空串。
     *
     * @return 机器名
     */
    String draggedMachineName();

    /**
     * 某台机器是否是当前拖拽源。
     *
     * @param ref 机器引用
     * @return 是拖拽源为 {@code true}
     */
    boolean isDragSource(MachineRef ref);
}
