package io.github.crafteve.idlefactory.factory.io;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

/**
 * 一条诊断事件：工厂里发生过的、值得回看的一件事。
 *
 * <p>只存结构和纯文本，<b>不存 {@code Component}</b>——颜色、点击目标都是渲染时决定的，
 * 存进去就把展示方式写死了，以后换出口（聊天栏 / 文件 / 界面）要全部重来。</p>
 *
 * <p>{@code resourceId} 现在恒为 {@code null}，等仓库支持流体 / 能量 / 化学品时再填，
 * 这样事件模型不用改第二次。</p>
 *
 * @param tick       发生的游戏时间
 * @param level      等级
 * @param category   类别
 * @param lineId     关联产线，可为 {@code null}
 * @param machineId  关联机器，可为 {@code null}
 * @param resourceId 关联资源，可为 {@code null}
 * @param text       纯文本描述
 */
public record DiagEvent(long tick, Level level, Category category,
        @Nullable UUID lineId, @Nullable UUID machineId,
        @Nullable String resourceId, String text) {

    /** 等级。{@link #IDLE} 专门表示「闲着，不是坏了」。 */
    public enum Level {
        /** 正常。 */
        OK,
        /** 需要注意。 */
        WARN,
        /** 配置错了或东西没了。 */
        ERROR,
        /** 空闲，不是故障。 */
        IDLE
    }

    /** 类别，聊天栏里占四个字母。 */
    public enum Category {
        /** 玩家执行了会改状态的命令。 */
        CMD,
        /** 产线状态迁移。 */
        LINE,
        /** 机器库变化。 */
        MACH,
        /** 仓库解析。 */
        STOR,
        /** 搬运。 */
        IO
    }

    /**
     * 造一条事件。
     *
     * @param tick       发生时间
     * @param level      等级
     * @param category   类别
     * @param lineId     产线
     * @param machineId  机器
     * @param resourceId 资源
     * @param text       描述
     * @return 事件
     */
    public static DiagEvent of(long tick, Level level, Category category,
            @Nullable UUID lineId, @Nullable UUID machineId,
            @Nullable String resourceId, String text) {
        return new DiagEvent(tick, level, category, lineId, machineId, resourceId, text);
    }
}
