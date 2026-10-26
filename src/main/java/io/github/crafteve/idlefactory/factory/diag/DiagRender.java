package io.github.crafteve.idlefactory.factory.diag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryMessages;
import io.github.crafteve.idlefactory.factory.line.IoGroup;
import io.github.crafteve.idlefactory.factory.line.PassiveLine;
import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
import io.github.crafteve.idlefactory.factory.resource.ResourceKind;
import io.github.crafteve.idlefactory.factory.machine.MachineRef;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;

/**
 * 诊断结果的聊天栏渲染。
 *
 * <p>只负责把结构化数据变成 {@link Component}，不决定「记什么」。颜色和前缀走
 * {@link FactoryMessages} 的规范。</p>
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
    public static Component check(DiagChecks.GroupCheck check, long now) {
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

    // ------------------------------------------------------------------
    // watch
    // ------------------------------------------------------------------

    /**
     * watch 的汇总行（actionbar）。
     *
     * @param factory    工厂
     * @param line       产线
     * @param now        当前游戏时间
     * @param sleepUntil 唤醒时间；0 表示没在睡
     * @param sleepSince 睡眠起点；-1 表示没在睡
     * @return 组件
     */
    public static Component watchSummary(Factory factory, PassiveLine line, long now,
            long sleepUntil, long sleepSince) {
        DiagStats.LineStats stats = factory.stats().line(line.id());
        List<Map.Entry<ResourceKey, Long>> rates = topRates(stats, 2);

        MutableComponent row = Component.empty()
                .append(FactoryMessages.text(line.shortId() + " " + line.name() + " · ", FactoryMessages.INFO))
                .append(FactoryMessages.text(line.running() ? "运行" : "停止",
                        line.running() ? FactoryMessages.OK : FactoryMessages.INFO))
                .append(FactoryMessages.text(" · ", FactoryMessages.INFO));

        if (rates.isEmpty()) {
            row.append(FactoryMessages.text("无搬运", FactoryMessages.DIM));
        } else {
            for (int i = 0; i < rates.size(); i++) {
                if (i > 0) {
                    row.append(FactoryMessages.text(" ", FactoryMessages.INFO));
                }
                row.append(rateText(rates.get(i).getKey(), rates.get(i).getValue()));
            }
            int more = allRates(stats).size() - rates.size();
            if (more > 0) {
                row.append(FactoryMessages.text(" +" + more, FactoryMessages.DIM));
            }
        }

        int blocked = blockedGroups(stats);
        row.append(FactoryMessages.text(" · 卡 " + blocked + "/" + line.groups().size(),
                blocked > 0 ? FactoryMessages.WARN : FactoryMessages.INFO));
        row.append(FactoryMessages.text(" · IO " + formatMs(DiagService.ioCostAvgMs()),
                FactoryMessages.DIM));
        row.append(FactoryMessages.text(" · " + sleepText(now, sleepUntil, sleepSince),
                FactoryMessages.DIM));
        return row;
    }

    /**
     * watch 的详情行（聊天栏，逐行发送）。
     *
     * @param server     服务端
     * @param factory    工厂
     * @param line       产线
     * @param now        当前游戏时间
     * @param sleepUntil 唤醒时间
     * @param sleepSince 睡眠起点
     * @return 若干行
     */
    public static List<Component> watchDetail(MinecraftServer server, Factory factory, PassiveLine line,
            long now, long sleepUntil, long sleepSince) {
        List<Component> rows = new ArrayList<>();
        DiagStats.LineStats stats = factory.stats().line(line.id());

        rows.add(Component.empty()
                .append(FactoryMessages.text("[watch] ", FactoryMessages.DIM))
                .append(FactoryMessages.lineLink(line.shortId()))
                .append(FactoryMessages.text(" " + line.name() + " · "
                        + (line.running() ? "运行" : "停止")
                        + " · " + line.groups().size() + " 组 · "
                        + sleepText(now, sleepUntil, sleepSince), FactoryMessages.INFO)));

        rows.add(FactoryMessages.text("  入 " + ratesText(stats.rateIn())
                + " · 出 " + ratesText(stats.rateOut())
                + " · IO " + formatMs(DiagService.ioCostAvgMs())
                + "ms（最大 " + formatMs(DiagService.ioCostMaxMs()) + "ms）",
                FactoryMessages.PLAIN));

        Map<UUID, String> reasons = new HashMap<>();
        for (DiagChecks.GroupCheck check : DiagChecks.check(server, factory, line)) {
            reasons.put(check.machineId(), check.detail());
        }

        int shown = 0;
        for (IoGroup group : line.groups()) {
            if (shown >= 4) {
                rows.add(FactoryMessages.text("  … 还有 " + (line.groups().size() - shown) + " 台",
                        FactoryMessages.DIM));
                break;
            }
            rows.add(machineRow(factory, group, stats, reasons, now));
            shown++;
        }
        return rows;
    }

    private static Component machineRow(Factory factory, IoGroup group, DiagStats.LineStats stats,
            Map<UUID, String> reasons, long now) {
        MachineRef machine = factory.machines().byId(group.machineId()).orElse(null);
        String id = machine == null ? shortId(group.machineId()) : machine.shortId();
        DiagStats.GroupStats groupStats = stats.group(group.machineId());

        if (groupStats.blockedSince() >= 0L) {
            long blockedSeconds = Math.max(0L, (now - groupStats.blockedSince()) / 20L);
            String reason = reasons.getOrDefault(group.machineId(), "未知原因");
            return FactoryMessages.text("  " + id + " · 卡 " + blockedSeconds + "s · " + reason,
                    FactoryMessages.WARN);
        }
        long sinceMove = groupStats.lastMoveTick() > 0L
                ? Math.max(0L, (now - groupStats.lastMoveTick()) / 20L)
                : -1L;
        return FactoryMessages.text("  " + id + " · 就绪 · 上次 "
                + (sinceMove >= 0L ? sinceMove + "s" : "—"), FactoryMessages.INFO);
    }

    private static String sleepText(long now, long sleepUntil, long sleepSince) {
        if (sleepUntil <= now || sleepSince < 0L) {
            return "清醒";
        }
        long slept = Math.max(0L, (now - sleepSince) / 20L);
        long remain = Math.max(0L, (sleepUntil - now) / 20L);
        return "睡 " + slept + "s（醒于 " + remain + "s）";
    }

    private static int blockedGroups(DiagStats.LineStats stats) {
        int blocked = 0;
        for (DiagStats.GroupStats group : stats.groups().values()) {
            if (group.blockedSince() >= 0L) {
                blocked++;
            }
        }
        return blocked;
    }

    private static List<Map.Entry<ResourceKey, Long>> allRates(DiagStats.LineStats stats) {
        Map<ResourceKey, Long> merged = new HashMap<>();
        for (Map.Entry<ResourceKey, Long> entry : stats.rateIn().entrySet()) {
            merged.merge(entry.getKey(), entry.getValue(), Long::sum);
        }
        for (Map.Entry<ResourceKey, Long> entry : stats.rateOut().entrySet()) {
            merged.merge(entry.getKey(), entry.getValue(), Long::sum);
        }
        List<Map.Entry<ResourceKey, Long>> list = new ArrayList<>(merged.entrySet());
        list.sort(Map.Entry.<ResourceKey, Long>comparingByValue().reversed());
        return list;
    }

    private static List<Map.Entry<ResourceKey, Long>> topRates(DiagStats.LineStats stats, int limit) {
        List<Map.Entry<ResourceKey, Long>> all = allRates(stats);
        return all.size() <= limit ? all : all.subList(0, limit);
    }

    private static Component rateText(ResourceKey key, long rate) {
        String unit = switch (key.kind()) {
            case FLUID -> " mB/s";
            case ENERGY -> " FE/s";
            default -> "/s";
        };
        return FactoryMessages.text(resourceName(key) + " " + formatAmount(rate) + unit,
                FactoryMessages.PLAIN);
    }

    private static String ratesText(Map<ResourceKey, Long> rates) {
        if (rates.isEmpty()) {
            return "空";
        }
        List<Map.Entry<ResourceKey, Long>> list = new ArrayList<>(rates.entrySet());
        list.sort(Map.Entry.<ResourceKey, Long>comparingByValue().reversed());
        StringBuilder builder = new StringBuilder();
        int shown = 0;
        for (Map.Entry<ResourceKey, Long> entry : list) {
            if (shown >= 3) {
                builder.append(" +").append(list.size() - shown);
                break;
            }
            if (builder.length() > 0) {
                builder.append(" · ");
            }
            builder.append(resourceName(entry.getKey())).append(' ')
                    .append(formatAmount(entry.getValue())).append(unitSuffix(entry.getKey()));
            shown++;
        }
        return builder.toString();
    }

    private static String unitSuffix(ResourceKey key) {
        return switch (key.kind()) {
            case FLUID -> "mB/s";
            case ENERGY -> "FE/s";
            default -> "/s";
        };
    }

    private static String resourceName(ResourceKey key) {
        if (key.kind() == ResourceKind.ENERGY) {
            return "电";
        }
        String id = key.id().toString();
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    private static String formatAmount(long amount) {
        if (amount >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.1fM", amount / 1_000_000.0D);
        }
        if (amount >= 1_000L) {
            return String.format(Locale.ROOT, "%.1fk", amount / 1_000.0D);
        }
        return Long.toString(amount);
    }

    private static String formatMs(double millis) {
        if (millis >= 10.0D) {
            return String.format(Locale.ROOT, "%.0f", millis);
        }
        return String.format(Locale.ROOT, "%.2f", millis);
    }
}
