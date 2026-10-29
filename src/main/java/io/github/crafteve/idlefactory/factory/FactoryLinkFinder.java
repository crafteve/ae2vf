package io.github.crafteve.idlefactory.factory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.crafteve.idlefactory.factory.machine.MachineRef;
import io.github.crafteve.idlefactory.factory.probe.BlockProbe;
import io.github.crafteve.idlefactory.factory.probe.Health;
import io.github.crafteve.idlefactory.factory.observation.ObservationKey;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 绑定杖的连锁查找。
 *
 * <p>从目标方块出发做 6 邻接 BFS：只穿过同方块 id 且仍然是有效目标的方块。这样一排同 id 的
 * 装饰方块或管道不会把搜索带到很远的地方，也不会把「只碰了一个角」的独立机器算进来。</p>
 *
 * <p>BFS 用显式队列，不用递归 DFS：大型机器群不会爆栈，且能随时按「结果数 / 访问数 / 半径」
 * 三个上限停住。</p>
 */
public final class FactoryLinkFinder {
    /** 一次连锁最多返回多少台机器。 */
    public static final int MAX_MACHINES = 128;
    /** 一次连锁最多返回多少个容器。 */
    public static final int MAX_CONTAINERS = 16;
    /** 最多访问多少个方块，防止大面积同 id 方块拖垮服务端。 */
    private static final int MAX_VISITS = 4096;
    /** 连锁半径（格）。 */
    private static final int MAX_RADIUS = 32;

    private FactoryLinkFinder() {
        // 纯静态工具类
    }

    /**
     * 查找目标机器所在的同 id 连通群。
     *
     * @param level  服务端世界
     * @param origin 目标机器
     * @return 机器坐标；目标不是有效机器时为空
     */
    public static List<BlockPos> machines(ServerLevel level, BlockPos origin) {
        return find(level, origin, MAX_MACHINES, pos -> isMachine(level, pos));
    }

    /**
     * 查找目标容器所在的同 id 连通群。
     *
     * @param level  服务端世界
     * @param origin 目标容器
     * @return 容器坐标；目标不是有效容器时为空
     */
    public static List<BlockPos> containers(ServerLevel level, BlockPos origin) {
        return find(level, origin, MAX_CONTAINERS, pos -> FactoryService.isAnyContainer(level, pos));
    }

    /**
     * 目标方块是否是可接入的机器。
     *
     * <p>判定与 {@code /idf factory connect} 一致：方块实体存在，且至少提供物品 / 流体 / 能量
     * 中的一种能力。</p>
     *
     * @param level 服务端世界
     * @param pos   坐标
     * @return 是可接入机器时为 {@code true}
     */
    public static boolean isMachine(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return false;
        }
        MinecraftServer server = level.getServer();
        BlockProbe.Presence presence = BlockProbe.presence(server,
                ObservationKey.of(MachineRef.of(level.dimension(), pos)));
        return presence.health() == Health.PRESENT && !presence.kinds().isEmpty();
    }

    private static List<BlockPos> find(ServerLevel level, BlockPos origin, int max,
            java.util.function.Predicate<BlockPos> valid) {
        if (!level.isLoaded(origin) || !valid.test(origin)) {
            return List.of();
        }
        Block targetBlock = level.getBlockState(origin).getBlock();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        List<BlockPos> result = new ArrayList<>();
        queue.add(origin);
        visited.add(origin);
        int visits = 0;
        while (!queue.isEmpty() && result.size() < max && visits < MAX_VISITS) {
            BlockPos pos = queue.poll();
            visits++;
            if (!level.isLoaded(pos) || level.getBlockState(pos).getBlock() != targetBlock) {
                continue;
            }
            // 轻过滤：没有方块实体的不可能是有效目标（机器和容器的判定都要 BE）。
            // 这一步只是跳过贵的能力探测，结论还是下面的全量校验说了算；
            // 无效块本来就不扩展，和原来行为一致。
            if (level.getBlockEntity(pos) == null) {
                continue;
            }
            if (!valid.test(pos)) {
                continue;
            }
            result.add(pos);
            for (Direction direction : Direction.values()) {
                BlockPos next = pos.relative(direction);
                if (next.distSqr(origin) > (double) MAX_RADIUS * MAX_RADIUS) {
                    continue;
                }
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        return result;
    }
}
