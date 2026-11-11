package io.github.crafteve.idlefactory.client.screen;

import net.minecraft.resources.ResourceLocation;

import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.factory.NodeStatus;

/**
 * 一台机器 / 一个节点的总状态展示：文字、颜色、状态条贴图和状态灯贴图。
 *
 * <p>把状态映射从界面壳里拆出来，绘制、搜索字段和 tooltip 用同一份结果，避免同一个对象
 * 在不同位置显示不同状态。健康只有在线 / 离线两个值；「未探测」与「健康过期」是独立的布尔，
 * 展示层把它们各归成一档，不塞进健康枚举。</p>
 *
 * @param text  状态文字
 * @param color 状态文字颜色
 * @param strip 1px 状态条贴图
 * @param lamp  状态灯贴图
 */
public record FactoryStatus(String text, int color, ResourceLocation strip, ResourceLocation lamp) {

    /**
     * 状态种类，和主题无关。
     *
     * <p>顺序是有意的：组聚合平票时按枚举顺序取第一个，保证同一份快照每次算出同一个结果。</p>
     */
    enum Kind {
        /** 失联：方块没了、维度没了。 */
        OFFLINE,
        /** 缺机：节点部分机器在线。 */
        PARTIAL,
        /** 健康过期：探过但超过阈值没复探。 */
        STALE,
        /** 未探测：还没探过。 */
        UNPROBED,
        /** 输入堵塞：机器满了或拒收。 */
        INPUT_BLOCKED,
        /** 运行中。 */
        RUNNING,
        /** 可输入但没在干活。 */
        INPUT_OK,
        /** 仓库没料。不算报错，只是等待。 */
        WAITING_MATERIAL,
        /** 空闲。 */
        IDLE,
        /** 没编线或者没规则。 */
        UNCONFIGURED,
        /** 未知。 */
        UNKNOWN
    }

    /**
     * 取一个节点的状态种类。
     *
     * <p>唯一一套节点判断顺序：未探测 / 过期优先，其次失联 / 缺机，最后才看"在干什么"。
     * 两个终端都读服务端下发的 {@link NodeStatus}，这里只做展示映射，不再自己聚合。</p>
     *
     * @param status 节点状态
     * @return 状态种类
     */
    static Kind kindOf(NodeStatus status) {
        if (!status.probed()) {
            return Kind.UNPROBED;
        }
        if (status.stale()) {
            return Kind.STALE;
        }
        return switch (status.health()) {
            case OFFLINE -> Kind.OFFLINE;
            case PARTIAL -> Kind.PARTIAL;
            case ONLINE -> kindOf(status.totalState());
        };
    }

    /**
     * 健康已确定时，把总状态映射成种类。
     *
     * @param state 服务端总状态
     * @return 状态种类
     */
    private static Kind kindOf(FactorySnapshotBuilder.TotalState state) {
        return switch (state) {
            case OFFLINE -> Kind.OFFLINE;
            case UNCONFIGURED -> Kind.UNCONFIGURED;
            case RUNNING -> Kind.RUNNING;
            case IDLE -> Kind.IDLE;
            case WAITING_MATERIAL -> Kind.WAITING_MATERIAL;
            case INPUT_OK -> Kind.INPUT_OK;
            case INPUT_BLOCKED -> Kind.INPUT_BLOCKED;
            case UNKNOWN -> Kind.UNKNOWN;
        };
    }

    /**
     * 把状态种类映射成文字、颜色和贴图。
     *
     * @param kind  状态种类
     * @param theme 当前主题
     * @return 状态展示
     */
    static FactoryStatus of(Kind kind, FactoryGuiTheme theme) {
        return switch (kind) {
            case OFFLINE -> new FactoryStatus("失联", theme.error(),
                    FactoryGuiTextures.STATUS_STRIP_ERROR, FactoryGuiTextures.STATUS_LAMP_ERROR);
            case PARTIAL -> new FactoryStatus("缺机", theme.statusWarn(),
                    FactoryGuiTextures.STATUS_STRIP_WARN, FactoryGuiTextures.STATUS_LAMP_WARN);
            case STALE -> new FactoryStatus("健康过期", theme.error(),
                    FactoryGuiTextures.STATUS_STRIP_ERROR, FactoryGuiTextures.STATUS_LAMP_ERROR);
            case UNPROBED -> new FactoryStatus("未探测", theme.statusWarn(),
                    FactoryGuiTextures.STATUS_STRIP_WARN, FactoryGuiTextures.STATUS_LAMP_WARN);
            case UNCONFIGURED -> new FactoryStatus("无规则",
                    theme.statusIdle(), FactoryGuiTextures.STATUS_STRIP_IDLE,
                    FactoryGuiTextures.STATUS_LAMP_IDLE);
            case RUNNING -> new FactoryStatus("运行", theme.statusRun(),
                    FactoryGuiTextures.STATUS_STRIP_RUN, FactoryGuiTextures.STATUS_LAMP_RUN);
            case INPUT_OK -> new FactoryStatus("可输入", theme.statusRun(),
                    FactoryGuiTextures.STATUS_STRIP_RUN, FactoryGuiTextures.STATUS_LAMP_RUN);
            case WAITING_MATERIAL -> new FactoryStatus("仓库缺料", theme.statusWarn(),
                    FactoryGuiTextures.STATUS_STRIP_WARN, FactoryGuiTextures.STATUS_LAMP_WARN);
            case INPUT_BLOCKED -> new FactoryStatus("输入堵塞", theme.statusWarn(),
                    FactoryGuiTextures.STATUS_STRIP_WARN, FactoryGuiTextures.STATUS_LAMP_WARN);
            case IDLE -> new FactoryStatus("空闲", theme.statusIdle(),
                    FactoryGuiTextures.STATUS_STRIP_IDLE, FactoryGuiTextures.STATUS_LAMP_IDLE);
            case UNKNOWN -> new FactoryStatus("未知", theme.statusIdle(),
                    FactoryGuiTextures.STATUS_STRIP_IDLE, FactoryGuiTextures.STATUS_LAMP_IDLE);
        };
    }

    /**
     * 把节点状态映射成文字、颜色和贴图。
     *
     * @param status 节点状态
     * @param theme  当前主题
     * @return 状态展示
     */
    public static FactoryStatus of(NodeStatus status, FactoryGuiTheme theme) {
        return of(kindOf(status), theme);
    }

}