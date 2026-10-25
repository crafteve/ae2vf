package io.github.crafteve.idlefactory.factory.diag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryMessages;
import io.github.crafteve.idlefactory.factory.FactoryRefs;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.factory.line.PassiveLine;
import io.github.crafteve.idlefactory.factory.target.MachineTarget;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

/**
 * 诊断指令的入口：{@code status} / {@code why} / {@code tail} / {@code watch} / {@code export}。
 *
 * <p>输出全部走聊天栏，每条消息一行。这是刻意的取舍：独立界面要把 UI 再写一遍，而聊天栏的
 * 点击、悬停、颜色都是白送的。</p>
 */
public final class DiagService {
    /** 最多给一个玩家同时监控几条线。 */
    private static final int MAX_WATCHES = 4;
    /** status 最多列几条产线。 */
    private static final int STATUS_LINES = 8;
    /** tail 上限。 */
    private static final int TAIL_MAX = 20;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 正在监控的玩家 → 监控目标。 */
    private static final Map<UUID, Watch> WATCHES = new HashMap<>();

    private DiagService() {
        // 纯静态工具类
    }

    // ------------------------------------------------------------------
    // 指令
    // ------------------------------------------------------------------

    /**
     * 健康摘要：工厂一行，每条产线一行，最后一条最近事件。
     *
     * @param player 玩家
     * @return 命令结果
     */
    public static int status(ServerPlayer player) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        FactoryRuntime runtime = FactoryRuntime.get();
        long now = now(runtime.server());
        List<PassiveLine> lines = factory.lines().all();

        FactoryMessages.send(player, FactoryMessages.info("── 工厂 " + DiagRender.shortId(factory.id())
                + " · 仓库" + (factory.storageLink().online() ? "在线" : "离线")
                + " · 机器 " + factory.machines().size()
                + " · 产线 " + lines.size()));

        int shown = Math.min(lines.size(), STATUS_LINES);
        for (int i = 0; i < shown; i++) {
            FactoryMessages.send(player, statusRow(factory, lines.get(i), now, runtime));
        }
        if (lines.size() > shown) {
            FactoryMessages.send(player, FactoryMessages.info(
                    "… 还有 " + (lines.size() - shown) + " 条，用 /idf line 查看"));
        }

        List<DiagEvent> recent = factory.diagnostics().recent(1);
        if (!recent.isEmpty()) {
            FactoryMessages.send(player, DiagRender.event(recent.get(0), now));
        }
        return 1;
    }

    private static Component statusRow(Factory factory, PassiveLine line, long now,
            FactoryRuntime runtime) {
        Diagnostics.LineStats stats = factory.diagnostics().line(line.id());
        MutableComponent row = Component.empty()
                .append(DiagRender.levelTag(line.running()
                        ? DiagEvent.Level.OK : DiagEvent.Level.IDLE))
                .append(FactoryMessages.text(" ", FactoryMessages.INFO))
                .append(FactoryMessages.lineLink(line.shortId()))
                .append(FactoryMessages.text(" " + line.name() + " · ", FactoryMessages.INFO))
                .append(FactoryMessages.text(line.running() ? "运行" : "停止",
                        line.running() ? FactoryMessages.OK : FactoryMessages.INFO))
                .append(FactoryMessages.text(" · " + line.groups().size() + " 组 · "
                        + stats.totalRate() + "/s", FactoryMessages.INFO));
        long sleepUntil = runtime.lineRunner().sleepUntil(line.id());
        if (sleepUntil > now) {
            row.append(FactoryMessages.text(" · 睡眠 " + seconds(sleepUntil - now) + "s",
                    FactoryMessages.DIM));
        }
        return row;
    }

    /**
     * 体检一条产线。
     *
     * @param player  玩家
     * @param lineRef 产线引用
     * @return 命令结果
     */
    public static int why(ServerPlayer player, String lineRef) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        PassiveLine line = requireLine(player, factory, lineRef);
        if (line == null) {
            return 0;
        }
        FactoryRuntime runtime = FactoryRuntime.get();
        long now = now(runtime.server());
        long sleepUntil = runtime.lineRunner().sleepUntil(line.id());

        MutableComponent header = Component.empty()
                .append(FactoryMessages.text("── ", FactoryMessages.DIM))
                .append(FactoryMessages.lineLink(line.shortId()))
                .append(FactoryMessages.text(" " + line.name() + " · ", FactoryMessages.INFO))
                .append(FactoryMessages.text(line.running() ? "运行" : "停止",
                        line.running() ? FactoryMessages.OK : FactoryMessages.INFO))
                .append(FactoryMessages.text(" · " + line.groups().size() + " 组", FactoryMessages.INFO))
                .append(FactoryMessages.text(sleepUntil > now
                        ? " · 睡眠剩余 " + seconds(sleepUntil - now) + "s"
                        : " · 清醒", FactoryMessages.DIM));
        FactoryMessages.send(player, header);

        if (line.groups().isEmpty()) {
            FactoryMessages.send(player, FactoryMessages.info(
                    "还没有机器。用 /idf line " + line.shortId() + " add <机器> 加入"));
            return 1;
        }

        for (LineDiagnostics.GroupCheck check : LineDiagnostics.check(runtime.server(), factory, line)) {
            FactoryMessages.send(player, DiagRender.check(check, now));
        }
        return 1;
    }

    /**
     * 体检一条产线里的一台机器。
     *
     * @param player      玩家
     * @param lineRef     产线引用
     * @param machineRef  机器引用
     * @return 命令结果
     */
    public static int whyMachine(ServerPlayer player, String lineRef, String machineRef) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        PassiveLine line = requireLine(player, factory, lineRef);
        if (line == null) {
            return 0;
        }
        UUID machineId;
        Optional<MachineTarget> machine = FactoryRefs.machine(factory, machineRef);
        if (machine.isEmpty()) {
            FactoryMessages.send(player, FactoryMessages.err("找不到机器 " + machineRef));
            FactoryMessages.send(player, FactoryMessages.info("用 /idf factory info 查看机器库"));
            return 0;
        }
        if (line.groupByMachine(machine.get().id()).isEmpty()) {
            FactoryMessages.send(player, FactoryMessages.err(
                    "机器 " + machineRef + " 不在产线 " + line.name() + " 里"));
            return 0;
        }
        machineId = machine.get().id();
        FactoryRuntime runtime = FactoryRuntime.get();
        long now = now(runtime.server());
        for (LineDiagnostics.GroupCheck check : LineDiagnostics.check(runtime.server(), factory, line)) {
            if (check.machineId().equals(machineId)) {
                FactoryMessages.send(player, DiagRender.check(check, now));
                return 1;
            }
        }
        return 0;
    }

    /**
     * 最近的事件。
     *
     * @param player 玩家
     * @param count  条数
     * @return 命令结果
     */
    public static int tail(ServerPlayer player, int count) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        int wanted = Math.min(Math.max(count, 1), TAIL_MAX);
        long now = now(FactoryRuntime.get().server());
        List<DiagEvent> events = factory.diagnostics().recent(wanted);
        if (events.isEmpty()) {
            FactoryMessages.send(player, FactoryMessages.info("还没有事件"));
            return 1;
        }
        FactoryMessages.send(player, FactoryMessages.info("── 最近 " + events.size()
                + " 条 / 共 " + factory.diagnostics().size() + " ──"));
        for (DiagEvent event : events) {
            FactoryMessages.send(player, DiagRender.event(event, now));
        }
        return 1;
    }

    /**
     * 开关实时监控：汇总走 actionbar，详情走聊天栏。
     *
     * @param player  玩家
     * @param lineRef 产线引用
     * @param seconds 推送间隔（秒，1–60）
     * @param detail  是否推详情（聊天栏多行）
     * @return 命令结果
     */
    public static int watch(ServerPlayer player, String lineRef, int seconds, boolean detail) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        PassiveLine line = requireLine(player, factory, lineRef);
        if (line == null) {
            return 0;
        }
        Watch existing = WATCHES.get(player.getUUID());
        if (existing != null && existing.lineId.equals(line.id())) {
            WATCHES.remove(player.getUUID());
            player.displayClientMessage(Component.empty(), true);
            FactoryMessages.send(player, FactoryMessages.ok("已停止监控 " + line.shortId()));
            return 1;
        }
        if (WATCHES.size() >= MAX_WATCHES && !WATCHES.containsKey(player.getUUID())) {
            FactoryMessages.send(player, FactoryMessages.err("监控名额已满（" + MAX_WATCHES + " 个）"));
            return 0;
        }
        int clamped = Math.max(1, Math.min(60, seconds));
        Watch watch = new Watch(factory.id(), line.id(), clamped * 20L,
                now(FactoryRuntime.get().server()), detail);
        WATCHES.put(player.getUUID(), watch);
        FactoryMessages.send(player, FactoryMessages.ok("开始监控 " + line.shortId()
                + (detail ? "（详情，每 " + clamped + " 秒）" : "（汇总，每 " + clamped + " 秒）")
                + "，再执行一次关闭"));
        return 1;
    }

    /**
     * 把事件导出成文本，给玩家贴报告用。
     *
     * @param player 玩家
     * @return 命令结果
     */
    public static int export(ServerPlayer player) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        FactoryRuntime runtime = FactoryRuntime.get();
        long now = now(runtime.server());
        Path directory = runtime.server().getServerDirectory().resolve("logs").resolve("idlefactory");
        Path file = directory.resolve("diag-" + LocalDateTime.now().format(STAMP) + ".log");
        List<String> lines = new ArrayList<>();
        lines.add("# Idle Factory diagnostics");
        lines.add("# factory=" + factory.id() + " owner=" + factory.owner());
        for (DiagEvent event : factory.diagnostics().recent(200)) {
            lines.add(DiagRender.plain(event, now));
        }
        try {
            Files.createDirectories(directory);
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            FactoryMessages.send(player, FactoryMessages.err("导出失败：" + e.getMessage()));
            return 0;
        }
        FactoryMessages.send(player, FactoryMessages.ok("已导出 " + (lines.size() - 2)
                + " 条到 " + file));
        return 1;
    }

    /**
     * 查看当前 IO 间隔。
     *
     * @param player 玩家
     * @return 命令结果
     */
    public static int io(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            FactoryMessages.send(player, FactoryMessages.err("Idle Factory 尚未启动"));
            return 0;
        }
        FactoryMessages.send(player, FactoryMessages.info("IO 间隔：" + runtime.ioInterval()
                + " tick（" + FactoryRuntime.MIN_IO_INTERVAL + "–"
                + FactoryRuntime.MAX_IO_INTERVAL + "）"));
        return 1;
    }

    /**
     * 设置 IO 间隔。
     *
     * @param player 玩家
     * @param ticks  间隔
     * @return 命令结果
     */
    public static int setIo(ServerPlayer player, int ticks) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            FactoryMessages.send(player, FactoryMessages.err("Idle Factory 尚未启动"));
            return 0;
        }
        runtime.setIoInterval(ticks);
        FactoryMessages.send(player, FactoryMessages.ok("IO 间隔 → " + runtime.ioInterval() + " tick"));
        return 1;
    }

    // ------------------------------------------------------------------
    // 记录与推送
    // ------------------------------------------------------------------

    /**
     * 记一条命令审计。只记会改状态的命令，只读命令不记。
     *
     * @param source  命令来源
     * @param action  动作名，例如 {@code line.start}
     * @param success 是否成功
     */
    public static void command(CommandSourceStack source, String action, boolean success) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return;
        }
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            return;
        }
        Optional<Factory> factory = runtime.manager().byOwner(player.getUUID());
        if (factory.isEmpty()) {
            return;
        }
        long now = now(runtime.server());
        String text = player.getGameProfile().getName() + " " + action + (success ? "" : " 失败");
        factory.get().diagnostics().record(DiagEvent.of(now,
                success ? DiagEvent.Level.OK : DiagEvent.Level.ERROR,
                DiagEvent.Category.CMD, null, null, null, text));
    }

    /**
     * 每 20 tick 调一次：采样速率、推 actionbar。
     *
     * @param server 服务端
     */
    public static void tick(MinecraftServer server) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return;
        }
        for (Factory factory : runtime.manager().all()) {
            factory.diagnostics().sample();
        }
        if (WATCHES.isEmpty()) {
            return;
        }
        long now = now(server);
        Iterator<Map.Entry<UUID, Watch>> iterator = WATCHES.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Watch> entry = iterator.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                iterator.remove();
                continue;
            }
            Watch watch = entry.getValue();
            Optional<Factory> factory = runtime.manager().byId(watch.factoryId);
            if (factory.isEmpty()) {
                iterator.remove();
                continue;
            }
            Optional<PassiveLine> line = factory.get().lines().byId(watch.lineId);
            if (line.isEmpty()) {
                iterator.remove();
                continue;
            }
            if (now < watch.nextPush) {
                continue;
            }
            watch.nextPush = now + watch.intervalTicks;
            long sleepUntil = runtime.lineRunner().sleepUntil(line.get().id());
            long sleepSince = runtime.lineRunner().sleepSince(line.get().id());
            if (watch.detail) {
                for (Component row : DiagRender.watchDetail(server, factory.get(), line.get(),
                        now, sleepUntil, sleepSince)) {
                    FactoryMessages.send(player, row);
                }
            } else {
                player.displayClientMessage(DiagRender.watchSummary(factory.get(), line.get(),
                        now, sleepUntil, sleepSince), true);
            }
        }
    }

    /** 玩家下线、服务器停止时清掉监控。 */
    public static void clearWatches() {
        WATCHES.clear();
    }

    // ------------------------------------------------------------------
    // IO 耗时
    // ------------------------------------------------------------------

    /** 最近多少个 IO 轮次的耗时。 */
    private static final int IO_COST_SAMPLES = 20;
    private static final long[] IO_COST = new long[IO_COST_SAMPLES];
    private static int ioCostIndex;
    private static int ioCostCount;

    /**
     * 记一次 IO 轮次的耗时。
     *
     * @param nanos 纳秒
     */
    public static void recordIoCost(long nanos) {
        IO_COST[ioCostIndex] = nanos;
        ioCostIndex = (ioCostIndex + 1) % IO_COST_SAMPLES;
        if (ioCostCount < IO_COST_SAMPLES) {
            ioCostCount++;
        }
    }

    /** 最近 IO 轮次的平均耗时（毫秒）。 */
    public static double ioCostAvgMs() {
        if (ioCostCount == 0) {
            return 0.0D;
        }
        long total = 0L;
        for (int i = 0; i < ioCostCount; i++) {
            total += IO_COST[i];
        }
        return total / (double) ioCostCount / 1_000_000.0D;
    }

    /** 最近 IO 轮次的最大耗时（毫秒）。 */
    public static double ioCostMaxMs() {
        long max = 0L;
        for (int i = 0; i < ioCostCount; i++) {
            max = Math.max(max, IO_COST[i]);
        }
        return max / 1_000_000.0D;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static long now(MinecraftServer server) {
        return server.overworld().getGameTime();
    }

    private static String seconds(long ticks) {
        return String.format(java.util.Locale.ROOT, "%.1f", ticks / 20.0D);
    }

    @Nullable
    private static Factory requireFactory(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            FactoryMessages.send(player, FactoryMessages.err("Idle Factory 尚未启动（服务端未就绪）"));
            return null;
        }
        Optional<Factory> factory = runtime.manager().byOwner(player.getUUID());
        if (factory.isEmpty()) {
            FactoryMessages.send(player, FactoryMessages.err("你还没有工厂"));
            FactoryMessages.send(player, FactoryMessages.info("先执行 /idf create"));
            return null;
        }
        return factory.get();
    }

    @Nullable
    private static PassiveLine requireLine(ServerPlayer player, Factory factory, String ref) {
        Optional<PassiveLine> line = FactoryRefs.line(factory, ref);
        if (line.isEmpty()) {
            FactoryMessages.send(player, FactoryMessages.err("找不到产线 " + ref));
            FactoryMessages.send(player, FactoryMessages.info("用 /idf line 查看现有产线"));
            return null;
        }
        return line.get();
    }

    /** 一个监控目标。 */
    private static final class Watch {
        private final UUID factoryId;
        private final UUID lineId;
        private final long intervalTicks;
        private final boolean detail;
        private long nextPush;

        private Watch(UUID factoryId, UUID lineId, long intervalTicks, long nextPush, boolean detail) {
            this.factoryId = factoryId;
            this.lineId = lineId;
            this.intervalTicks = intervalTicks;
            this.nextPush = nextPush;
            this.detail = detail;
        }
    }
}
