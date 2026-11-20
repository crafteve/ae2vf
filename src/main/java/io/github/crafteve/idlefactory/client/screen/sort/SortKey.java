package io.github.crafteve.idlefactory.client.screen.sort;

import io.github.crafteve.idlefactory.client.screen.UiText;
import io.github.crafteve.idlefactory.client.screen.text.TerminalText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import appeng.api.config.SortDir;

import io.github.crafteve.idlefactory.menu.TerminalSortMode;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * 终端排序键：一个节点在某一时刻的全部排序事实，比较链只在 {@link #comparator} 一处定义。
 *
 * <p>两个终端只负责把各自的视图翻译成这把键，不许再各写一套比较逻辑。排序规则：先按档位取
 * 主键，主键相同再依次比名称、机器台数、节点 UUID；方向按钮把整套比较反过来，所以升序降序
 * 对四把尺子一起生效。</p>
 *
 * @param name         显示名：自定义节点名优先，否则是类型方块名
 * @param id           类型方块注册名字符串；没有时为空串
 * @param machineCount 节点机器台数
 * @param ruleCount    节点 IO 规则数
 * @param statusRank   状态等级：0 最好、越大越差
 * @param distance     到玩家的最近成员距离（平方）；不同维度为 {@link #UNREACHABLE_DISTANCE}
 * @param nodeId       节点 id，最后一把尺子
 */
public record SortKey(String name, String id, int machineCount, int ruleCount,
        int statusRank, long distance, UUID nodeId) {

    /** 不同维度或没有玩家位置时使用的距离，比任何真实距离都远。 */
    public static final long UNREACHABLE_DISTANCE = Long.MAX_VALUE;

    /**
     * 按上下文排序并返回不可变副本。
     *
     * <p>先为每一项算一次键再排序，避免比较过程中反复重建键。</p>
     *
     * @param items   待排序的行 / 块
     * @param keyOf   从行 / 块取排序键
     * @param context 档位、方向与玩家位置
     * @param <T>     行 / 块类型
     * @return 排序后的不可变列表
     */
    public static <T> List<T> sorted(List<T> items, Function<T, SortKey> keyOf,
            SortContext context) {
        if (items.size() < 2) {
            return List.copyOf(items);
        }
        Comparator<SortKey> comparator = comparator(context.mode(), context.direction());
        List<Keyed<T>> keyed = new ArrayList<>(items.size());
        for (T item : items) {
            keyed.add(new Keyed<>(keyOf.apply(item), item));
        }
        keyed.sort(Comparator.comparing(Keyed::key, comparator));
        List<T> sorted = new ArrayList<>(items.size());
        for (Keyed<T> entry : keyed) {
            sorted.add(entry.item());
        }
        return List.copyOf(sorted);
    }

    /**
     * 保持既有顺序重排：先按上一次的顺序摆放仍在的项，新出现的项按当前排序追加在末尾。
     *
     * <p>给改名框 / 删除确认框打开时用：快照照常更新数据，但列表不许在用户操作期间跳动。</p>
     *
     * @param items    本次快照过滤后的项
     * @param previous 上一次已排好序的项
     * @param idOf     从项取节点 id
     * @param keyOf    从项取排序键；新出现的项用它排序
     * @param context  档位、方向与玩家位置
     * @param <T>      行 / 块类型
     * @return 保持既有顺序的不可变列表
     */
    public static <T> List<T> preserveOrder(List<T> items, List<T> previous,
            Function<T, UUID> idOf, Function<T, SortKey> keyOf,
            SortContext context) {
        if (previous.isEmpty()) {
            return sorted(items, keyOf, context);
        }
        Map<UUID, T> remaining = new LinkedHashMap<>();
        for (T item : items) {
            remaining.put(idOf.apply(item), item);
        }
        List<T> ordered = new ArrayList<>(items.size());
        for (T old : previous) {
            T current = remaining.remove(idOf.apply(old));
            if (current != null) {
                ordered.add(current);
            }
        }
        ordered.addAll(sorted(new ArrayList<>(remaining.values()), keyOf, context));
        return List.copyOf(ordered);
    }

    /**
     * 按档位与方向构造比较链。
     *
     * @param mode      排序档位
     * @param direction 排序方向：降序把整条链反转
     * @return 比较器
     */
    public static Comparator<SortKey> comparator(TerminalSortMode mode,
            SortDir direction) {
        Comparator<SortKey> chain = switch (mode) {
            case NAME -> Comparator.comparing(SortKey::name);
            case ID -> Comparator.comparing(SortKey::id);
            case MACHINE_COUNT -> Comparator.comparingInt(SortKey::machineCount);
            case RULE_COUNT -> Comparator.comparingInt(SortKey::ruleCount);
            case STATUS -> Comparator.comparingInt(SortKey::statusRank);
            case DISTANCE -> Comparator.comparingLong(SortKey::distance);
        };
        if (mode != TerminalSortMode.NAME) {
            chain = chain.thenComparing(SortKey::name);
        }
        chain = chain.thenComparingInt(SortKey::machineCount)
                .thenComparing(SortKey::nodeId);
        return direction == SortDir.DESCENDING ? chain.reversed() : chain;
    }

    /**
     * 节点显示名：自定义名优先，否则用类型方块名；两者都没有时为空串。
     *
     * @param nodeName    节点自定义名
     * @param typeBlockId 类型方块 id
     * @return 用于排序与展示的显示名
     */
    public static String displayName(@Nullable String nodeName,
            @Nullable ResourceLocation typeBlockId) {
        if (nodeName != null && !nodeName.isEmpty()) {
            return nodeName;
        }
        return typeBlockId == null ? "" : UiText.blockName(typeBlockId);
    }

    /**
     * 一个方块到玩家的平方距离。
     *
     * <p>用平方比较省掉开方；不同维度、或缺少玩家位置时返回 {@link #UNREACHABLE_DISTANCE}。</p>
     *
     * @param playerDimension 玩家维度；未知时 {@code null}
     * @param playerPos       玩家坐标；未知时 {@code null}
     * @param blockDimension  方块维度
     * @param blockPos        方块坐标
     * @return 平方距离；不可达时为 {@link #UNREACHABLE_DISTANCE}
     */
    public static long distance(@Nullable ResourceKey<Level> playerDimension,
            @Nullable BlockPos playerPos, @Nullable ResourceKey<Level> blockDimension,
            @Nullable BlockPos blockPos) {
        if (playerDimension == null || playerPos == null || blockDimension == null
                || blockPos == null || !playerDimension.equals(blockDimension)) {
            return UNREACHABLE_DISTANCE;
        }
        long dx = (long) blockPos.getX() - playerPos.getX();
        long dy = (long) blockPos.getY() - playerPos.getY();
        long dz = (long) blockPos.getZ() - playerPos.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /** 排序中间态：键与原对象的配对。 */
    private record Keyed<T>(SortKey key, T item) {
    }
}
