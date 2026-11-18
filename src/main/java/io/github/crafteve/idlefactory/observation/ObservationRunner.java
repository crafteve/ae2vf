package io.github.crafteve.idlefactory.observation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;

import io.github.crafteve.idlefactory.location.BlockKey;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 观测调度：结构对账 → 消化事件队列 → 健康 / 工态两条游标轮转探测 → 批量广播变化。
 *
 * <p>预算按三条通道分开：健康、工态、事件各由运行时的配置注入，每次取预算都读当前 config，
 * 热改下一 tick 生效。探测结果原地写回观测表的行，不新建对象。</p>
 *
 * <p>对账不每 tick 做：读档首 tick 一次，之后每 {@link #RECONCILE_INTERVAL} tick 一次。
 * 两次对账之间，被引用集合的变化由写配置的一方经 {@link #markDirty} 补进结构队列立即复探。</p>
 *
 * <p>本类不认识工厂、不认识节点：被引用集合由上层每 tick 传进来，观测层保持对配置层零依赖。</p>
 */
public final class ObservationRunner {
    /** 对账间隔（tick）：读档首 tick 一次，之后每 1200 tick 一次。 */
    public static final int RECONCILE_INTERVAL = 1200;

    private final MinecraftServer server;
    private final ObservationTable table = new ObservationTable();
    private final StructureQueue structure = new StructureQueue();
    private final ContentQueue content = new ContentQueue();
    private final ObservationReader reader = new ObservationReader(this);
    private final CapabilityListener capability;
    /** 健康 / 工态 / 事件三条通道的每 tick 预算。 */
    private final IntSupplier healthBudget;
    private final IntSupplier workBudget;
    private final IntSupplier eventBudget;
    private final List<ObservationChangeListener> listeners = new ArrayList<>();
    /** 本 tick 收集的变化，广播后清空。 */
    private final List<ObservationChange.BlockChange> pendingChanges = new ArrayList<>();
    private long tick;
    /** 是否至少对过一次账。 */
    private boolean reconciled;
    private long lastReconcileTick = Long.MIN_VALUE;

    /**
     * @param server        服务端
     * @param healthBudget  每 tick 健康探测预算
     * @param workBudget    每 tick 工态探测预算
     * @param eventBudget   每 tick 事件队列消化预算
     */
    public ObservationRunner(MinecraftServer server, IntSupplier healthBudget,
            IntSupplier workBudget, IntSupplier eventBudget) {
        this.server = server;
        this.capability = new CapabilityListener(server, this);
        this.healthBudget = healthBudget;
        this.workBudget = workBudget;
        this.eventBudget = eventBudget;
    }

    /** 观测表；只有观测层自己写，外部拿它做诊断与区块索引查询。 */
    public ObservationTable table() {
        return this.table;
    }

    /** 只读视图：界面、健康解析、类型迁移都从这里读。 */
    public ObservationReader reader() {
        return this.reader;
    }

    /**
     * 注册变化监听器。
     *
     * @param listener 监听器；停服前必须 {@link #unregister} 注销
     */
    public void register(ObservationChangeListener listener) {
        this.listeners.add(listener);
    }

    /**
     * 注销变化监听器。
     *
     * @param listener 监听器
     */
    public void unregister(ObservationChangeListener listener) {
        this.listeners.remove(listener);
    }

    /** 当前观测时钟。 */
    public long currentTick() {
        return this.tick;
    }

    /**
     * 确保位置已登记：没登记过就补一行并立刻探测。
     *
     * <p>配置层新增机器时调它，避免新位置等到下一次全量对账（1200 tick）才出现。</p>
     *
     * @param key 位置键
     */
    public void ensure(BlockKey key) {
        if (!this.table.contains(key)) {
            this.table.create(key, this.tick);
        }
    }

    /**
     * 标脏：下一 tick 立刻复探这个位置。
     *
     * @param key 位置键；没登记过的位置直接丢弃（观测表只认被引用集合）
     */
    public void markDirty(BlockKey key) {
        if (this.table.contains(key)) {
            this.structure.add(key);
        }
    }

    /**
     * 这个位置是否在事件队列里等复探。
     *
     * @param key 位置键
     * @return 在队列里为 {@code true}
     */
    public boolean isDirty(BlockKey key) {
        return this.structure.contains(key);
    }

    /**
     * 立刻重探一个位置。
     *
     * @param key 位置键；没登记过时什么都不做
     */
    public void refresh(BlockKey key) {
        ObservationTable.Entry entry = this.table.get(key);
        if (entry != null) {
            this.probeHealth(entry);
        }
    }

    /**
     * 推进一 tick。
     *
     * @param referenced 当前被配置引用的全部位置
     */
    public void tick(Set<BlockKey> referenced) {
        this.tick++;
        if (!this.reconciled || this.tick - this.lastReconcileTick >= RECONCILE_INTERVAL) {
            int[] removed = this.table.reconcile(referenced, this.tick);
            this.content.onRemoved(removed);
            this.capability.retain(this.table);
            this.reconciled = true;
            this.lastReconcileTick = this.tick;
        }
        this.drainStructure();
        this.probeHealthRound();
        this.probeWorkRound();
        this.broadcast();
    }

    /** 消费事件队列：把到期的位置拉到「立刻探」。 */
    private void drainStructure() {
        int budget = Math.max(1, this.eventBudget.getAsInt());
        for (int i = 0; i < budget; i++) {
            BlockKey key = this.structure.poll();
            if (key == null) {
                return;
            }
            ObservationTable.Entry entry = this.table.get(key);
            if (entry != null) {
                entry.markDue(this.tick);
            }
        }
    }

    /** 健康游标：最多探 budget 个到期行，最多绕表一圈。 */
    private void probeHealthRound() {
        List<ObservationTable.Entry> entries = this.table.entries();
        if (entries.isEmpty()) {
            this.content.healthCursor(0);
            return;
        }
        int size = entries.size();
        int cursor = this.content.healthCursor();
        if (cursor >= size) {
            cursor = 0;
        }
        int budget = Math.max(1, this.healthBudget.getAsInt());
        int probes = 0;
        int visited = 0;
        while (visited < size && probes < budget) {
            ObservationTable.Entry entry = entries.get(cursor);
            if (entry.healthDue(this.tick)) {
                this.probeHealth(entry);
                probes++;
            }
            cursor = (cursor + 1) % size;
            visited++;
        }
        this.content.healthCursor(cursor);
    }

    /** 工态游标：只探 PRESENT 且到期的行。 */
    private void probeWorkRound() {
        List<ObservationTable.Entry> entries = this.table.entries();
        if (entries.isEmpty()) {
            this.content.workCursor(0);
            return;
        }
        int size = entries.size();
        int cursor = this.content.workCursor();
        if (cursor >= size) {
            cursor = 0;
        }
        int budget = Math.max(1, this.workBudget.getAsInt());
        int probes = 0;
        int visited = 0;
        while (visited < size && probes < budget) {
            ObservationTable.Entry entry = entries.get(cursor);
            if (entry.workDue(this.tick)) {
                ServerLevel level = this.server.getLevel(entry.key().dimension());
                WorkState state = level == null
                        ? WorkState.UNKNOWN
                        : BlockProbe.workState(level, entry.key().pos());
                entry.applyWorkState(state, this.tick);
                probes++;
            }
            cursor = (cursor + 1) % size;
            visited++;
        }
        this.content.workCursor(cursor);
    }

    /**
     * 探一行健康，并把结论变化记进本 tick 的变化批次。
     *
     * @param entry 行
     */
    private void probeHealth(ObservationTable.Entry entry) {
        this.capability.track(entry.key());
        BlockProbe.Presence result = BlockProbe.presence(this.server, entry.key());
        Health before = entry.health();
        entry.applyProbe(result, this.tick);
        Health after = entry.health();
        if (before != after) {
            this.pendingChanges.add(new ObservationChange.BlockChange(entry.key(), before, after,
                    this.tick));
        }
    }

    /** 本 tick 有变化就广播一批。 */
    private void broadcast() {
        if (this.pendingChanges.isEmpty()) {
            return;
        }
        ObservationChange change = new ObservationChange(List.copyOf(this.pendingChanges),
                this.tick);
        this.pendingChanges.clear();
        for (ObservationChangeListener listener : List.copyOf(this.listeners)) {
            listener.onObservationChanged(change);
        }
    }

    /** 停服：清表、清队列、注销全部监听器。 */
    public void shutdown() {
        this.table.clear();
        this.structure.clear();
        this.capability.clear();
        this.listeners.clear();
        this.pendingChanges.clear();
        this.reconciled = false;
        this.lastReconcileTick = Long.MIN_VALUE;
    }
}
