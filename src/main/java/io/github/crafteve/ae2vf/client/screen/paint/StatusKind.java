package io.github.crafteve.ae2vf.client.screen.paint;

import io.github.crafteve.ae2vf.view.ViewNode.Activity;
import io.github.crafteve.ae2vf.view.ViewNodeCore;

/**
 * 节点状态的展示档位：由服务端算好的四个结论字段映射而来，不参与聚合。
 *
 * <p>取值顺序即排序等级的依据：越靠前的档位越"正常"。文字、颜色、贴图分别由
 * {@link StatusText}、{@link StatusColor}、{@link StatusLamp} 查表，三者共用这一个枚举，
 * 所以灯色、文字与排序位置永远一致。</p>
 */
public enum StatusKind {
    /** 运行中。 */
    RUNNING,
    /** 可输入但没在干活。 */
    INPUT_OK,
    /** 空闲。 */
    IDLE,
    /** 没编线或者没规则。 */
    UNCONFIGURED,
    /** 未知。 */
    UNKNOWN,
    /** 仓库没料。不算报错，只是等待。 */
    WAITING_MATERIAL,
    /** 输入堵塞：机器满了或拒收。 */
    INPUT_BLOCKED,
    /** 未探测：还没探过。 */
    UNPROBED,
    /** 缺机：节点部分机器在线。 */
    PARTIAL,
    /** 健康过期：探过但超过阈值没复探。 */
    STALE,
    /** 失联：方块没了、维度没了。 */
    OFFLINE;

    /**
     * 取一个节点的状态档位。
     *
     * <p>唯一一套判断顺序：未探测 / 过期优先，其次失联 / 缺机，最后才看"在干什么"。</p>
     *
     * @param core 节点自身的字段
     * @return 档位
     */
    public static StatusKind of(ViewNodeCore core) {
        if (!core.probed()) {
            return UNPROBED;
        }
        if (core.stale()) {
            return STALE;
        }
        return switch (core.health()) {
            case OFFLINE -> OFFLINE;
            case PARTIAL -> PARTIAL;
            case ONLINE -> of(core.activity());
        };
    }

    private static StatusKind of(Activity activity) {
        return switch (activity) {
            case OFFLINE -> OFFLINE;
            case UNCONFIGURED -> UNCONFIGURED;
            case RUNNING -> RUNNING;
            case IDLE -> IDLE;
            case WAITING_MATERIAL -> WAITING_MATERIAL;
            case INPUT_OK -> INPUT_OK;
            case INPUT_BLOCKED -> INPUT_BLOCKED;
            case UNKNOWN -> UNKNOWN;
        };
    }

    /**
     * 排序等级：0 最好、越大越差。
     *
     * @return 等级，0..10
     */
    public int rank() {
        return ordinal();
    }
}
