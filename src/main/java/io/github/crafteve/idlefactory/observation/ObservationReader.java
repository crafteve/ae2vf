package io.github.crafteve.idlefactory.observation;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.location.BlockKey;

/**
 * 观测表的只读视图：健康解析、类型迁移、视图片段构建都只从这里读。
 *
 * <p>实现 {@link BlockFacts.Source}：没登记过的位置返回未探测的空事实，调用方不必判空。
 * 本类不探测、不标脏、不改表；需要改观测的一方去调 {@link ObservationRunner}。</p>
 */
public final class ObservationReader implements BlockFacts.Source {
    private final ObservationRunner runner;

    /**
     * @param runner 观测调度者
     */
    ObservationReader(ObservationRunner runner) {
        this.runner = runner;
    }

    @Override
    public BlockFacts at(BlockKey key) {
        ObservationTable.Entry entry = this.runner.table().get(key);
        return entry == null ? BlockFacts.UNKNOWN : entry;
    }

    /**
     * 取一行。
     *
     * @param key 位置键
     * @return 行；没有登记时为 {@code null}
     */
    @Nullable
    public ObservationTable.Entry entry(BlockKey key) {
        return this.runner.table().get(key);
    }

    /**
     * 取一行的工作状态。
     *
     * @param key 位置键
     * @return 工作状态；没有登记时为 {@link WorkState#UNKNOWN}
     */
    public WorkState workState(BlockKey key) {
        ObservationTable.Entry entry = this.runner.table().get(key);
        return entry == null ? WorkState.UNKNOWN : entry.workState();
    }

    /**
     * 取一行「最后一次探到的方块 id」。
     *
     * @param key 位置键
     * @return 方块 id；没有登记或从没探到过时为 {@code null}
     */
    @Nullable
    public net.minecraft.resources.ResourceLocation lastBlockId(BlockKey key) {
        ObservationTable.Entry entry = this.runner.table().get(key);
        return entry == null ? null : entry.lastBlockId();
    }

    /** 立刻重探一个位置（下一轮调度，不直接写表）。 */
    public void refresh(BlockKey key) {
        this.runner.refresh(key);
    }

    /**
     * 这个位置是否具备能量能力。
     *
     * @param key 位置键
     * @return 具备为 {@code true}；没登记或没探到为 {@code false}
     */
    public boolean energyCapable(BlockKey key) {
        ObservationTable.Entry entry = this.runner.table().get(key);
        return entry != null && entry.energyCapable();
    }

    /** 当前观测时钟。 */
    public long currentTick() {
        return this.runner.currentTick();
    }

    /**
     * 是否登记过。
     *
     * @param key 位置键
     * @return 登记过为 {@code true}
     */
    public boolean contains(BlockKey key) {
        return this.runner.table().contains(key);
    }

    /**
     * 某个区块里是否登记过位置。
     *
     * @param id 区块标识
     * @return 有登记为 {@code true}
     */
    public boolean hasChunk(ObservationTable.ChunkId id) {
        return !this.runner.table().keysInChunk(id).isEmpty();
    }

    /**
     * 这个位置是否在事件队列里等复探。
     *
     * @param key 位置键
     * @return 在队列里为 {@code true}
     */
    public boolean isDirty(BlockKey key) {
        return this.runner.isDirty(key);
    }
}
