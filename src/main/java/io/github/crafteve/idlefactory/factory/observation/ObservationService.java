package io.github.crafteve.idlefactory.factory.observation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntSupplier;

import io.github.crafteve.idlefactory.factory.capability.FaceCache;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.capability.FaceTable;
import io.github.crafteve.idlefactory.factory.capability.LevelCapabilityCache;
import io.github.crafteve.idlefactory.factory.probe.BlockProbe;
import io.github.crafteve.idlefactory.factory.probe.Health;
import io.github.crafteve.idlefactory.factory.probe.WorkState;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * 全局观测服务。
 *
 * <p>它只做三件事：<b>存</b>（一张按位置索引的表）、<b>调度</b>（该探谁、多久探一次、每 tick 最多探几个）、
 * <b>报迁移</b>（PRESENT↔UNLOADED↔MISSING 的变化交给上层）。</p>
 *
 * <p>它<b>不</b>改配置、<b>不</b>做 IO、<b>不</b>碰任何 {@code Factory}——被引用的键集合由
 * {@code FactoryRuntime} 每 tick 传进来。这样观测层可以保持零工厂依赖（见 {@link BlockKey}）。</p>
 *
 * <p>预算与退避是刻意的：平时排队的只有“有事的”（新的、刚脏过、失联、未加载），
 * PRESENT 固定 100 tick 复探——不能更长，健康过期阈值（默认 1200 tick）比它短的话，
 * 安静机器会把自己判成过期。稳态下每 tick 的探测次数与对象总数成正比但很小。</p>
 *
 * <p>预算由 {@code FactoryRuntime} 以 {@link IntSupplier} 注入，每次取预算都读当前 config，
 * 热改下一 tick 生效。探测顺序由调用方给的 {@code BlockKey} 序列决定（脏 > 在线机器 > 其余）。
 * 本类只探测、记 {@code observedAtTick}、吐 {@link Health}，<b>不判断 stale</b>。</p>
 */
public final class ObservationService {
    /**
     * 每 tick 最多处理几个「被失效事件标脏」的条目。
     *
     * <p>标脏是事件驱动的（能力失效、邻居更新、chunk 加载），平时用不满，事件 burst
     * 时才顶满——预算在这里的语义是 backlog 消化速度，不是摊薄。超了顺延到下 tick，
     * tick 不爆。</p>
     */
    private static final int DIRTY_BUDGET_PER_TICK = 64;
    /** 每 tick 最多探测几个工作状态。 */
    private static final int WORK_BUDGET_PER_TICK = 16;
    /** 连续 REJECTED 几次才刷一次面表（单次拒收可能只是抖动）。 */
    private static final int REJECT_STREAK_TO_REFRESH = 3;
    /** 同一位置两次面表刷新至少间隔多少 tick。 */
    private static final long FACE_REFRESH_COOLDOWN_TICKS = 100L;
    /** 熔断窗口（tick）：10 分钟内刷新超过上限就冻结。 */
    private static final long FACE_FREEZE_WINDOW_TICKS = 12000L;
    /** 窗口内最多刷新几次，超过就冻结。 */
    private static final int FACE_FREEZE_MAX_REFRESHES = 5;

    private final MinecraftServer server;
    /** 每 tick 探测预算；由运行时注入，读当前 config。 */
    private final IntSupplier probeBudget;
    /** 本 tick 已经探过的位置，避免脏优先与轮转重复探测。 */
    private final Set<BlockKey> probedThisTick = new HashSet<>();
    /** 键 → 观测。插入顺序稳定，便于诊断输出。 */
    private final Map<BlockKey, Observation> observations = new LinkedHashMap<>();
    /**
     * 面表缓存：和观察表同一拍进出（引用集里有才留），但住在 capability 包。
     *
     * <p>观察只留健康和时钟，地图搬到能力档案室——失效时机本来就和 handler 一致
     * （能力失效一起清），住一起天经地义。</p>
     */
    private final FaceCache faceCache = new FaceCache();
    /** 维度 → 能力句柄缓存（P3）。 */
    private final Map<ResourceKey<Level>, LevelCapabilityCache> capabilityCaches = new HashMap<>();
    /** 能力失效事件标脏的位置，下一 tick 优先重探。 */
    private final Set<BlockKey> dirty = new HashSet<>();
    /** 本 tick 产生的迁移，取走即清空。 */
    private final List<Transition> transitions = new ArrayList<>();
    /** 位置 → 连续 REJECTED 次数（成功一次就清零）。 */
    private final Map<BlockKey, Integer> rejectStreaks = new HashMap<>();
    /** 位置 → 上次刷面表的 tick（冷却用）。 */
    private final Map<BlockKey, Long> lastFaceRefresh = new HashMap<>();
    /** 位置 → 熔断窗口（起点，窗口内刷新次数）。 */
    private final Map<BlockKey, FaceWindow> faceWindows = new HashMap<>();
    /**
     * 面表被冻结的位置：只睡不刷，等成功一次再解冻。
     *
     * <p>冻结的意思是“这地方的面表一直在变，刷了也白刷，先别浪费探测”——注意冻的是
     * 刷新动作，不是健康结论，更不是 IO 本身。</p>
     */
    private final Set<BlockKey> frozenFaces = new HashSet<>();

    private long tick;
    private int probesLastTick;

    /**
     * @param server      服务端实例
     * @param probeBudget 每 tick 探测预算供应器，读当前 config
     */
    public ObservationService(MinecraftServer server, IntSupplier probeBudget) {
        this.server = server;
        this.probeBudget = probeBudget;
    }

    /**
     * 这个位置是否被事件标脏、等下一 tick 优先重探。
     *
     * @param key 位置
     * @return 在待消化集合里时为 {@code true}
     */
    public boolean isDirty(BlockKey key) {
        return this.dirty.contains(key);
    }

    /**
     * 每 tick 推进一次。
     *
     * @param ordered 当前所有被引用位置，按优先级排序（脏 > 在线机器 > 其余）；
     *                不在里面的观测会被清掉（mark-sweep）
     */
    public void tick(List<BlockKey> ordered) {
        this.tick++;
        this.probesLastTick = 0;
        this.probedThisTick.clear();
        Set<BlockKey> referenced = new HashSet<>(ordered);

        for (BlockKey key : ordered) {
            this.observations.computeIfAbsent(key, ignored -> new Observation());
        }

        // 1) 事件标脏的优先：能力变了，面表一定过期
        int dirtyBudget = DIRTY_BUDGET_PER_TICK;
        Iterator<BlockKey> dirtyIterator = this.dirty.iterator();
        while (dirtyIterator.hasNext() && dirtyBudget > 0) {
            BlockKey key = dirtyIterator.next();
            dirtyIterator.remove();
            Observation observation = this.observations.get(key);
            if (observation == null) {
                continue;
            }
            // 脏的是“地图”不是“病历”：面表作废走面表缓存，病历等下面的 probe 重写。
            // 能力失效事件不改变方块 id，光复探抓不到它，必须在这里显式清。
            this.faceCache.invalidate(key);
            probe(key, observation);
            this.probedThisTick.add(key);
            this.probesLastTick++;
            dirtyBudget--;
        }

        // 2) 到期复探：按调用方排好的顺序，预算读当前 config
        int budget = Math.max(0, this.probeBudget.getAsInt());
        for (BlockKey key : ordered) {
            if (budget <= 0) {
                break;
            }
            if (this.probedThisTick.contains(key)) {
                continue;
            }
            Observation observation = this.observations.get(key);
            if (observation == null || observation.nextProbeTick() > this.tick) {
                continue;
            }
            probe(key, observation);
            this.probedThisTick.add(key);
            budget--;
            this.probesLastTick++;
        }

        // 3) 工作状态：独立预算，只探加载中的机器
        int workBudget = WORK_BUDGET_PER_TICK;
        for (Map.Entry<BlockKey, Observation> entry : this.observations.entrySet()) {
            if (workBudget <= 0) {
                break;
            }
            Observation observation = entry.getValue();
            if (!observation.workDue(this.tick)) {
                continue;
            }
            probeWork(entry.getKey(), observation);
            workBudget--;
        }

        // 4) mark-sweep：不再被引用的位置，连能力缓存一起丢
        this.observations.keySet().removeIf(key -> {
            if (referenced.contains(key)) {
                return false;
            }
            LevelCapabilityCache cache = this.capabilityCaches.get(key.dimension());
            if (cache != null) {
                cache.forget(key.pos());
            }
            return true;
        });
        // 面表缓存同一拍：引用集之外的连表带代数一起丢，和观察表同生共死。
        this.faceCache.sweep(referenced);
    }

    /**
     * 标记一个位置「能力可能变了」，下一 tick 优先重探。
     *
     * <p>只由 {@link LevelCapabilityCache} 的失效监听器调用；监听器里不许查世界，所以这里只记一笔。</p>
     *
     * @param key 位置
     */
    public void markDirty(BlockKey key) {
        this.dirty.add(key);
    }

    /**
     * IO 报告一次面级拒收（REJECTED）。
     *
     * <p>只刷面表，绝不动健康——面不对不等于机器没了。连续 {@value #REJECT_STREAK_TO_REFRESH}
     * 次才刷一次，单次拒收可能只是抖动；两次刷新之间有冷却；窗口内刷太多次就冻结，
     * 防止“报错→刷面表→还报错→再刷”的自激循环把 tick 点着。</p>
     *
     * <p>槽位级拒收（满了、机器内部不让放）一律按 NO_SPACE 睡，根本不许调这个方法——
     * 进到这里的只能是“走这个面递不进去”。</p>
     *
     * @param key 位置
     */
    public void noteRejected(BlockKey key) {
        if (this.frozenFaces.contains(key)) {
            return;
        }
        int streak = this.rejectStreaks.getOrDefault(key, 0) + 1;
        this.rejectStreaks.put(key, streak);
        if (streak < REJECT_STREAK_TO_REFRESH) {
            return;
        }
        this.rejectStreaks.put(key, 0);
        Long lastRefresh = this.lastFaceRefresh.get(key);
        if (lastRefresh != null && this.tick - lastRefresh < FACE_REFRESH_COOLDOWN_TICKS) {
            return;
        }
        this.lastFaceRefresh.put(key, this.tick);
        FaceWindow window = this.faceWindows.get(key);
        if (window == null || this.tick - window.start() > FACE_FREEZE_WINDOW_TICKS) {
            window = new FaceWindow(this.tick, 0);
        }
        window = new FaceWindow(window.start(), window.count() + 1);
        this.faceWindows.put(key, window);
        if (window.count() > FACE_FREEZE_MAX_REFRESHES) {
            this.frozenFaces.add(key);
            return;
        }
        this.faceCache.invalidate(key);
    }

    /**
     * IO 成功一次：清连续计数，解冻。
     *
     * <p>路通了，说明之前全是误会——计数、冻结一起清，干干净净。</p>
     *
     * @param key 位置
     */
    public void noteSuccess(BlockKey key) {
        this.rejectStreaks.remove(key);
        this.frozenFaces.remove(key);
    }

    /**
     * 取某个维度的能力句柄缓存。
     *
     * @param level 维度
     * @return 缓存；维度未注册时为 {@code null}
     */
    public LevelCapabilityCache capabilities(ServerLevel level) {
        return this.capabilityCaches.computeIfAbsent(level.dimension(),
                ignored -> new LevelCapabilityCache(level, this::markDirty));
    }

    /**
     * 读观测（不探测、不创建）。
     *
     * @param key 位置键
     * @return 观测；没被登记过时返回只读的 {@link Observation#UNKNOWN}
     */
    public Observation observe(BlockKey key) {
        Observation found = this.observations.get(key);
        return found == null ? Observation.UNKNOWN : found;
    }

    /**
     * 立即探测一次并返回结果（命令、接入、诊断用）。
     *
     * @param key 位置键
     * @return 刷新后的观测
     */
    public Observation refresh(BlockKey key) {
        Observation observation = this.observations.computeIfAbsent(key, ignored -> new Observation());
        probe(key, observation);
        probeWork(key, observation);
        return observation;
    }

    /**
     * 读缓存的面表；没建过时返回空表。
     *
     * <p>纯读，绝不碰世界——原来在这里偷偷建表（贵，21 次查询）是最大的毛病。
     * 需要内容时调 {@link #ensureFaces}，由调用方显式承担那 21 次查询。</p>
     *
     * @param key 位置键
     * @return 面表
     */
    public FaceTable faces(BlockKey key) {
        FaceTable cached = this.faceCache.get(key);
        return cached == null ? FaceTable.EMPTY : cached;
    }

    /**
     * 确保面表建好：缓存没有就当场建一次。
     *
     * <p>贵（21 次能力查询），只允许真正要用表的调用方调——目前只有容器仓库的取面窄化，
     * 拿不到表它会退回全六面，所以调不调用都不影响正确性，只影响 IO 少试几个面。
     * 健康不是 PRESENT 或区块没加载时返回空表，不建（建了也是错的）。</p>
     *
     * @param key 位置键
     * @return 面表
     */
    public FaceTable ensureFaces(BlockKey key) {
        FaceTable cached = this.faceCache.get(key);
        if (cached != null) {
            return cached;
        }
        Observation observation = this.observations.get(key);
        if (observation == null || observation.health() != Health.PRESENT) {
            return FaceTable.EMPTY;
        }
        ServerLevel level = this.server.getLevel(key.dimension());
        if (level == null || !level.isLoaded(key.pos())) {
            return FaceTable.EMPTY;
        }
        FaceTable table = BlockProbe.faces(level, key.pos());
        this.faceCache.put(key, table);
        return table;
    }

    /**
     * 面表代数：每次面表被作废都会 +1，路由表拿它判断自己过没过期。
     *
     * <p>代数住在面表缓存里，这里只是转发——病历本（{@code Observation}）里已经没有
     * 代数这个字段了，它只留健康和时钟。</p>
     *
     * @param key 位置键
     * @return 代数；没登记过时为 {@code 0}
     */
    public long facesGeneration(BlockKey key) {
        return this.faceCache.generation(key);
    }

    /**
     * 取走本 tick 的状态迁移。
     *
     * @return 迁移列表；没有时为空列表
     */
    public List<Transition> drainTransitions() {
        if (this.transitions.isEmpty()) {
            return List.of();
        }
        List<Transition> drained = List.copyOf(this.transitions);
        this.transitions.clear();
        return drained;
    }

    /** 当前 tick（服务内部时钟，与游戏时间无关）。 */
    public long currentTick() {
        return this.tick;
    }

    /** 只读视图，诊断输出用。 */
    public Map<BlockKey, Observation> view() {
        return Collections.unmodifiableMap(this.observations);
    }

    /**
     * 汇总计数。
     *
     * @return 统计快照
     */
    public Stats stats() {
        int present = 0;
        int unloaded = 0;
        int missing = 0;
        int unknown = 0;
        for (Observation observation : this.observations.values()) {
            switch (observation.health()) {
                case PRESENT -> present++;
                case UNLOADED -> unloaded++;
                case MISSING, NO_DIMENSION -> missing++;
                default -> unknown++;
            }
        }
        return new Stats(this.observations.size(), present, unloaded, missing, unknown,
                this.probesLastTick, this.dirty.size(), this.tick);
    }

    private void probe(BlockKey key, Observation observation) {
        Health before = observation.health();
        ResourceLocation oldBlock = observation.blockId();
        ResourceLocation oldBe = observation.beTypeId();
        BlockProbe.Presence presence = BlockProbe.presence(this.server, key);
        observation.applyProbe(presence, this.tick);
        if (before != observation.health()) {
            this.transitions.add(new Transition(key, before, observation.health(), this.tick));
        }
        if (before != observation.health()
                || !Objects.equals(oldBlock, observation.blockId())
                || !Objects.equals(oldBe, observation.beTypeId())) {
            // 方块换过／可用性变了：面表一定过期。以前这行藏在 applyProbe 里，
            // 现在判和清分开——病历只管记，动手的是面表缓存。
            this.faceCache.invalidate(key);
        }
    }

    private void probeWork(BlockKey key, Observation observation) {
        ServerLevel level = this.server.getLevel(key.dimension());
        WorkState state = level == null
                ? WorkState.UNKNOWN
                : BlockProbe.workState(level, key.pos());
        observation.applyWorkState(state, this.tick);
    }

    /**
     * 一次状态迁移。
     *
     * @param key  位置
     * @param from 旧结论
     * @param to   新结论
     * @param tick 发生时刻（服务内部时钟）
     */
    public record Transition(BlockKey key, Health from, Health to, long tick) {
    }

    /**
     * 熔断窗口：起点，以及窗口内已经刷了几次面表。
     *
     * @param start 窗口起点（服务内部时钟）
     * @param count 窗口内刷新次数
     */
    private record FaceWindow(long start, int count) {
    }

    /**
     * 观测汇总。
     *
     * @param total         条目总数
     * @param present       就绪
     * @param unloaded      未加载
     * @param missing       失联（含维度不存在）
     * @param unknown       未探测
     * @param probesLastTick 本 tick 主动探测次数
     * @param dirtyPending  还没消化的标脏数（事件 burst 时会涨）
     * @param tick          服务内部时钟
     */
    public record Stats(int total, int present, int unloaded, int missing, int unknown,
                        int probesLastTick, int dirtyPending, long tick) {
    }
}