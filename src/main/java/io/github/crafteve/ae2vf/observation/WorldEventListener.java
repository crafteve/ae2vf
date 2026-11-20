package io.github.crafteve.ae2vf.observation;

import java.util.HashMap;
import java.util.Map;

import io.github.crafteve.ae2vf.key.BlockKey;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * 世界事件标脏：邻居更新与区块加载。
 *
 * <p>两件事都只标脏，从不读世界——读世界是下一 tick 复探的事。过滤器是观测表的「这个位置
 * 登记过吗」：没登记的位置连队列都不进，全服的邻居更新不会把观测层压垮。区块加载按表内的
 * 区块索引取位置，一个区块里撑死几十行。</p>
 *
 * <p>本类不引用工厂、不引用运行时：观测调度与观测表从构造注入，注册与注销由装配方负责。</p>
 */
public final class WorldEventListener {
    /** 邻居更新标脏冷却（tick）：同一位置 20 tick 内只标一次。 */
    private static final long NEIGHBOR_COOLDOWN_TICKS = 20L;
    /** 冷却表上限：超了就把过期的清掉，防止只增不减。 */
    private static final int COOLDOWN_MAP_LIMIT = 2048;
    /** 冷却表过期时间（tick）：超过 1 分钟没动静的条目视为垃圾。 */
    private static final long COOLDOWN_TTL_TICKS = 1200L;

    private final ObservationRunner runner;
    private final ObservationTable table;
    /** 位置 → 上次标脏时刻（游戏时间），防红石抖动。只活在服务器线程。 */
    private final Map<BlockKey, Long> neighborCooldown = new HashMap<>();

    /**
     * @param runner 观测调度者
     * @param table  观测表（只读，用于筛位置）
     */
    public WorldEventListener(ObservationRunner runner, ObservationTable table) {
        this.runner = runner;
        this.table = table;
    }

    /**
     * 邻居更新：登记过的位置才标脏。
     *
     * @param event 邻居更新事件
     */
    @SubscribeEvent
    public void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        ResourceKey<Level> dimension = level.dimension();
        BlockKey key = BlockKey.of(dimension, pos);
        if (!this.table.contains(key)) {
            return;
        }
        long now = level.getServer().overworld().getGameTime();
        Long last = this.neighborCooldown.get(key);
        if (last != null && now - last < NEIGHBOR_COOLDOWN_TICKS) {
            return;
        }
        this.neighborCooldown.put(key, now);
        if (this.neighborCooldown.size() > COOLDOWN_MAP_LIMIT) {
            this.neighborCooldown.entrySet()
                    .removeIf(entry -> now - entry.getValue() > COOLDOWN_TTL_TICKS);
        }
        this.runner.markDirty(key);
    }

    /**
     * 区块加载：把这个区块里登记过的位置标脏，未加载的行第一时间复探确认。
     *
     * @param event 区块加载事件
     */
    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ChunkPos chunk = event.getChunk().getPos();
        ObservationTable.ChunkId id = new ObservationTable.ChunkId(level.dimension(),
                ChunkPos.asLong(chunk.x, chunk.z));
        for (BlockKey key : this.table.keysInChunk(id)) {
            this.runner.markDirty(key);
        }
    }

    /** 停止时清冷却表。 */
    public void clear() {
        this.neighborCooldown.clear();
    }
}
