package io.github.crafteve.idlefactory.factory.observation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.resources.ResourceKey;
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
 * {@code FactoryRuntime} 每 tick 传进来。这样观测层可以保持零工厂依赖（见 {@link Located}）。</p>
 *
 * <p>预算与退避是刻意的：一次全量探测 100 台机器要 400 次能力查询，摊到 8/tick 就是十几个 tick；
 * 稳态下 PRESENT 每 5 秒复探一次，平均每 tick 只要 1 台。</p>
 */
public final class ObservationService {
    /** 每 tick 最多主动探测几个到期条目。 */
    private static final int PROBE_BUDGET_PER_TICK = 8;
    /** 每 tick 最多处理几个「被失效事件标脏」的条目。 */
    private static final int DIRTY_BUDGET_PER_TICK = 32;

    private final MinecraftServer server;
    /** 键 → 观测。插入顺序稳定，便于诊断输出。 */
    private final Map<ObservationKey, Observation> observations = new LinkedHashMap<>();
    /** 维度 → 能力句柄缓存（P3）。 */
    private final Map<ResourceKey<Level>, LevelCapabilityCache> capabilityCaches = new HashMap<>();
    /** 能力失效事件标脏的位置，下一 tick 优先重探。 */
    private final Set<ObservationKey> dirty = new HashSet<>();
    /** 本 tick 产生的迁移，取走即清空。 */
    private final List<Transition> transitions = new ArrayList<>();

    private long tick;
    private int probesLastTick;

    /**
     * @param server 服务端实例
     */
    public ObservationService(MinecraftServer server) {
        this.server = server;
    }

    /**
     * 每 tick 推进一次。
     *
     * @param referenced 当前所有工厂引用到的位置；不在里面的观测会被清掉（mark-sweep）
     */
    public void tick(Set<ObservationKey> referenced) {
        this.tick++;
        this.probesLastTick = 0;

        for (ObservationKey key : referenced) {
            this.observations.computeIfAbsent(key, ignored -> new Observation());
        }

        // 1) 事件标脏的优先：能力变了，面表一定过期
        int dirtyBudget = DIRTY_BUDGET_PER_TICK;
        Iterator<ObservationKey> dirtyIterator = this.dirty.iterator();
        while (dirtyIterator.hasNext() && dirtyBudget > 0) {
            ObservationKey key = dirtyIterator.next();
            dirtyIterator.remove();
            Observation observation = this.observations.get(key);
            if (observation == null) {
                continue;
            }
            observation.invalidateFaces();
            probe(key, observation);
            this.probesLastTick++;
            dirtyBudget--;
        }

        // 2) 到期复探
        int budget = PROBE_BUDGET_PER_TICK;
        for (Map.Entry<ObservationKey, Observation> entry : this.observations.entrySet()) {
            if (budget <= 0) {
                break;
            }
            Observation observation = entry.getValue();
            if (observation.nextProbeTick() > this.tick) {
                continue;
            }
            probe(entry.getKey(), observation);
            budget--;
            this.probesLastTick++;
        }

        // 3) mark-sweep：不再被引用的位置，连能力缓存一起丢
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
    }

    /**
     * 标记一个位置「能力可能变了」，下一 tick 优先重探。
     *
     * <p>只由 {@link LevelCapabilityCache} 的失效监听器调用；监听器里不许查世界，所以这里只记一笔。</p>
     *
     * @param key 位置
     */
    public void markDirty(ObservationKey key) {
        this.dirty.add(key);
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
     * @param located 机器引用或容器引用
     * @return 观测；没被登记过时返回只读的 {@link Observation#UNKNOWN}
     */
    public Observation observe(Located located) {
        Observation found = this.observations.get(ObservationKey.of(located));
        return found == null ? Observation.UNKNOWN : found;
    }

    /**
     * 立即探测一次并返回结果（命令、接入、诊断用）。
     *
     * @param located 机器引用或容器引用
     * @return 刷新后的观测
     */
    public Observation refresh(Located located) {
        ObservationKey key = ObservationKey.of(located);
        Observation observation = this.observations.computeIfAbsent(key, ignored -> new Observation());
        probe(key, observation);
        return observation;
    }

    /**
     * 取面表，惰性构建并缓存。
     *
     * <p>只对 {@link Health#PRESENT} 的位置有效；其余情况返回 {@link FaceTable#EMPTY}。</p>
     *
     * @param located 机器引用或容器引用
     * @return 面表
     */
    public FaceTable faces(Located located) {
        ObservationKey key = ObservationKey.of(located);
        Observation observation = this.observations.get(key);
        if (observation == null) {
            return FaceTable.EMPTY;
        }
        FaceTable cached = observation.faces();
        if (cached != null) {
            return cached;
        }
        if (observation.health() != Health.PRESENT) {
            return FaceTable.EMPTY;
        }
        ServerLevel level = this.server.getLevel(key.dimension());
        if (level == null || !level.isLoaded(key.pos())) {
            return FaceTable.EMPTY;
        }
        FaceTable table = BlockProbe.faces(level, key.pos());
        observation.applyFaces(table);
        return table;
    }

    /**
     * 面表代数：每次面表被作废都会 +1，路由表拿它判断自己过没过期。
     *
     * @param located 机器引用或容器引用
     * @return 代数；没登记过时为 {@code 0}
     */
    public long facesGeneration(Located located) {
        Observation observation = this.observations.get(ObservationKey.of(located));
        return observation == null ? 0L : observation.facesGeneration();
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
    public Map<ObservationKey, Observation> view() {
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
                this.probesLastTick, this.tick);
    }

    private void probe(ObservationKey key, Observation observation) {
        Health before = observation.health();
        BlockProbe.Presence presence = BlockProbe.presence(this.server, key);
        observation.applyProbe(presence, this.tick);
        if (before != observation.health()) {
            this.transitions.add(new Transition(key, before, observation.health(), this.tick));
        }
    }

    /**
     * 一次状态迁移。
     *
     * @param key  位置
     * @param from 旧结论
     * @param to   新结论
     * @param tick 发生时刻（服务内部时钟）
     */
    public record Transition(ObservationKey key, Health from, Health to, long tick) {
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
     * @param tick          服务内部时钟
     */
    public record Stats(int total, int present, int unloaded, int missing, int unknown,
                        int probesLastTick, long tick) {
    }
}
