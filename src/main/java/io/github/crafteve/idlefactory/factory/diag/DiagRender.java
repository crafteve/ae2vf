package io.github.crafteve.idlefactory.factory.diag;

import java.util.UUID;

import io.github.crafteve.idlefactory.factory.FactoryMessages;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/**
 * 诊断结果的聊天栏渲染。
 *
 * <p>只负责把结构化数据变成{@link Component}，不决定「记什么」。颜色和前缀走
 * {@link FactoryMessages} 的规范，保证命令回执和诊断输出是一套风格。</p>
 *
 * <p>不要用空格对齐：MC 默认字体不是等宽，对齐必然歪。</p>
 */
public final class DiagRender {
    private DiagRender() {
        // 纯静态工具类
    }

    /**
     * 相对时间。
     *
     * @param now  当前游戏时间
     * @param tick 事件时间
     * @return {@code -8s} / {@code -3m} / {@code -1h+}
     */
    public static String ago(long now, long tick) {
        long seconds = Math.max(0L, (now - tick) / 20L);
        if (seconds < 60L) {
            return "-" + seconds + "s";
        }
        if (seconds < 3600L) {
            return "-" + (seconds / 60L) + "m";
        }
        return "-" + (seconds / 3600L) + "h+";
    }

    /**
     * 等级前缀。
     *
     * @param level 等级
     * @return {@code [OK]} / {@code [!!]} / {@code [XX]} / {@code [--]}
     */
    public static MutableComponent levelTag(DiagEvent.Level level) {
        return switch (level) {
            case OK -> FactoryMessages.text("[OK]", FactoryMessages.OK);
            case WARN -> FactoryMessages.text("[!!]", FactoryMessages.WARN);
            case ERROR -> FactoryMessages.text("[XX]", FactoryMessages.ERR);
            case IDLE -> FactoryMessages.text("[--]", FactoryMessages.DIM);
        };
    }

    /**
     * 一条事件。
     *
     * @param event 事件
     * @param now   当前游戏时间
     * @return 组件
     */
    public static Component event(DiagEvent event, long now) {
        MutableComponent row = Component.empty()
                .append(FactoryMessages.text(ago(now, event.tick()) + " ", FactoryMessages.DIM))
                .append(levelTag(event.level()))
                .append(FactoryMessages.text(" " + event.category().name() + " ", FactoryMessages.DIM));
        if (event.machineId() != null) {
            row.append(FactoryMessages.hover(shortId(event.machineId()), "机器 " + event.machineId()));
            row.append(FactoryMessages.text(" ", FactoryMessages.INFO));
        } else if (event.lineId() != null) {
            row.append(FactoryMessages.lineLink(shortId(event.lineId())));
            row.append(FactoryMessages.text(" ", FactoryMessages.INFO));
        }
        row.append(FactoryMessages.text(event.text(), FactoryMessages.PLAIN));
        return row;
    }

    /**
     * 一条体检结果。
     *
     * @param check 结果
     * @param now   当前游戏时间（保留参数，方便以后加「卡了多久」）
     * @return 组件
     */
    public static Component check(LineDiagnostics.GroupCheck check, long now) {
        MutableComponent detail = FactoryMessages.text(" " + check.detail(), FactoryMessages.PLAIN);
        if (check.problems().size() > 1) {
            // 一行写不下所有问题，悬停看全量
            detail = detail.withStyle(style -> style.withHoverEvent(
                    new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            Component.literal(String.join("\n", check.problems())))));
        }
        return Component.empty()
                .append(levelTag(check.level()))
                .append(FactoryMessages.text(" ", FactoryMessages.INFO))
                .append(FactoryMessages.hover(shortId(check.machineId()), "机器 " + check.machineId()))
                .append(detail);
    }

    /**
     * 事件的纯文本版本，导出用。
     *
     * @param event 事件
     * @param now   当前游戏时间
     * @return 一行文本
     */
    public static String plain(DiagEvent event, long now) {
        StringBuilder builder = new StringBuilder();
        builder.append(ago(now, event.tick())).append(' ')
                .append(event.level()).append(' ')
                .append(event.category()).append(' ');
        if (event.machineId() != null) {
            builder.append("mach:").append(shortId(event.machineId())).append(' ');
        } else if (event.lineId() != null) {
            builder.append("line:").append(shortId(event.lineId())).append(' ');
        }
        builder.append(event.text());
        return builder.toString();
    }

    /**
     * UUID 短 id（前 8 位）。
     *
     * @param id 完整 id
     * @return 短 id
     */
    public static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
