package io.github.crafteve.ae2vf.client.screen.paint;

import io.github.crafteve.ae2vf.view.ViewNodeCore;

import net.minecraft.resources.ResourceLocation;

/**
 * 一个节点状态档位的完整展示：文字、颜色、状态条与状态灯。
 *
 * <p>是 {@link StatusKind} 三个查表的组合结果，本身不再判断状态、也不再吃主题——颜色属于组件，
 * 文案与贴图按档位固定。绘制、搜索字段与 tooltip 共用这一份结果。</p>
 *
 * @param kind  档位
 * @param text  状态文字
 * @param color 状态文字颜色
 * @param lamp  状态灯贴图
 */
public record StatusView(StatusKind kind, String text, int color, ResourceLocation lamp) {

    /**
     * 把节点行映射成展示。
     *
     * @param core 节点自身的字段
     * @return 状态展示
     */
    public static StatusView of(ViewNodeCore core) {
        return of(StatusKind.of(core));
    }

    /**
     * 把档位映射成展示。
     *
     * @param kind 档位
     * @return 状态展示
     */
    public static StatusView of(StatusKind kind) {
        return new StatusView(kind, StatusText.of(kind), StatusColor.of(kind),
                StatusLamp.lamp(kind));
    }

    /**
     * 状态档的排序等级：0 最好、越大越差。
     *
     * @param core 节点自身的字段
     * @return 等级，0..10
     */
    public static int sortRank(ViewNodeCore core) {
        return StatusKind.of(core).rank();
    }
}
