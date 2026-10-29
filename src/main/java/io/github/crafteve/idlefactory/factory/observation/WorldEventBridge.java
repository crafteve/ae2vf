package io.github.crafteve.idlefactory.factory.observation;

import java.util.HashMap;
import java.util.Map;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.factory.location.BlockKey;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * 世界事件到观察的桥：有事才标脏，没事 tick 里什么都不多干。
 *
 * <p>只做三件事，每件都是“标记”，从不碰世界——查世界是下个 tick 复探的事，
 * 监听器里查世界是 NeoForge 明文禁止的：</p>
 * <ul>
 *   <li>邻居/方块更新：占用表里有这个位置才标脏，没有直接丢弃；同一位置 20 tick 内
 *       只标一次，红石抖动刷不出风暴；</li>
 *   <li>区块加载：把这个区块里 MISSING 的登记位置标脏，刚回来的第一时间确认；</li>
 *   <li>能力失效：早就有了（{@code LevelCapabilityCache} 的监听器），这里不重复。</li>
 * </ul>
 *
 * <p>过滤器就是占用表：事件过来先派生统一 id 查有没有登记——没登记的事件连 map 都
 * 不进，直接丢。这也是“事件驱动和全局表是天作之合”的意思。</p>
 */
@EventBusSubscriber(modid = IdleFactory.MODID)
public final class WorldEventBridge {
    /** 邻居更新标脏冷却（tick）：同一位置 20 tick 内只标一次。 */
    private static final long NEIGHBOR_COOLDOWN_TICKS = 20L;
    /** 冷却表上限：超了就把过期的清掉，防止只增不减。 */
    private static final int COOLDOWN_MAP_LIMIT = 2048;
    /** 冷却表过期时间（tick）：超过 1 分钟没动静的条目视为垃圾。 */
    private static final long COOLDOWN_TTL_TICKS = 1200L;

    /** 位置 → 上次标脏时刻（游戏时间），防红石抖动。只活在服务器线程。 */
    private static final Map<BlockKey, Long> NEIGHBOR_COOLDOWN = new HashMap<>();

    private WorldEventBridge() {
        // 纯静态事件处理
    }

    /**
     * 邻居更新：登记了的位置才标脏。
     *
     * @param event 邻居更新事件
     */
    @SubscribeEvent
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null || runtime.observations() == null) {
            return;
        }
        BlockPos pos = event.getPos();
        ResourceKey<Level> dimension = level.dimension();
        // 过滤器：没登记的位置直接丢，连标脏队列都不进。
        // 派生一次统一 id 是一次 MD5（亚微秒级），比一次能力探测便宜两个数量级。
        if (runtime.links().get(BlockKey.of(dimension, pos).id()).isEmpty()) {
            return;
        }
        long now = level.getServer().overworld().getGameTime();
        BlockKey key = new BlockKey(dimension, pos);
        Long last = NEIGHBOR_COOLDOWN.get(key);
        if (last != null && now - last < NEIGHBOR_COOLDOWN_TICKS) {
            return;
        }
        NEIGHBOR_COOLDOWN.put(key, now);
        if (NEIGHBOR_COOLDOWN.size() > COOLDOWN_MAP_LIMIT) {
            NEIGHBOR_COOLDOWN.entrySet().removeIf(entry -> now - entry.getValue() > COOLDOWN_TTL_TICKS);
        }
        runtime.observations().markDirty(key);
    }

    /**
     * 区块加载：把这个区块里登记过的位置标脏，MISSING 的第一时间复探确认。
     *
     * <p>范围天然有界：一个区块里撑死几十个登记位置。刚传送、刚进服的 burst 靠脏预算
     * 顺延消化，tick 不爆。</p>
     *
     * @param event 区块加载事件
     */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null || runtime.observations() == null) {
            return;
        }
        ChunkPos chunk = event.getChunk().getPos();
        for (BlockKey key : runtime.links().byChunk(chunk)) {
            if (key.dimension().equals(level.dimension())) {
                runtime.observations().markDirty(key);
            }
        }
    }
}
