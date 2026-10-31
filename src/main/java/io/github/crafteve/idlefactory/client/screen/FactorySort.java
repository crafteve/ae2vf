package io.github.crafteve.idlefactory.client.screen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder.MachineView;
import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder.TotalState;
import appeng.api.stacks.AEKey;
import io.github.crafteve.idlefactory.factory.capability.CapabilityKind;
import io.github.crafteve.idlefactory.network.payload.FactoryStorageS2C;
import appeng.api.stacks.AEFluidKey;
import io.github.crafteve.idlefactory.factory.storage.AeKeySupport;

/**
 * 仓库与机器库的排序规则。
 *
 * <p>只做「排序键 → 比较器」，不持有界面状态、不读世界。键与方向的记忆在
 * {@link FactorySortMenu}，应用在 {@link FactoryScreen}。默认键返回 {@code null}，
 * 表示保持服务端/登记顺序——这是刻意保留的稳定行为，避免 Shift 连续取物时格子跳动。</p>
 *
 * <p>所有比较器都以注册名或机器 id 作为最终兜底键，且方向只反转主键、不反转兜底键；
 * 因此同一份快照无论排序多少次，结果都一致。</p>
 */
final class FactorySort {
    private FactorySort() {
        // 纯静态工具类
    }

    /**
     * 仓库排序键。
     *
     * <p>{@code naturalDescending} 是首次选中该键时套用的方向：数量类默认降序，
     * 名称/类型类默认升序。</p>
     */
    enum StorageKey {
        /** 服务端快照顺序 + 首次出现稳定追加。 */
        DEFAULT("默认", "默", true),
        /** 当前库存。 */
        COUNT("数量", "量", true),
        /** 资源显示名。 */
        NAME("名称", "名", false),
        /** 模组显示名。 */
        MOD("模组", "模", false),
        /** 注册名。 */
        REGISTRY("注册名", "注", false),
        /** 资源类型。 */
        KIND("类型", "类", false);

        private final String label;
        private final String glyph;
        private final boolean naturalDescending;

        StorageKey(String label, String glyph, boolean naturalDescending) {
            this.label = label;
            this.glyph = glyph;
            this.naturalDescending = naturalDescending;
        }

        /** 菜单里的完整文案。 */
        String label() {
            return this.label;
        }

        /** 排序按钮上的单字。 */
        String glyph() {
            return this.glyph;
        }

        /** 首次选中时的自然方向；{@code true} 表示降序。 */
        boolean naturalDescending() {
            return this.naturalDescending;
        }
    }

    /**
     * 机器库排序键。
     *
     * <p>状态按「运行 → 可输入 → 缺料 → 堵塞 → 空闲 → 未配置 → 离线 → 未知」排；
     * 最近搬运把从未搬过的机器放最后。</p>
     */
    enum MachineKey {
        /** 服务端登记顺序。 */
        DEFAULT("默认", "默", true),
        /** 方块显示名。 */
        NAME("名称", "名", false),
        /** 维度 + 坐标。 */
        POSITION("坐标", "坐", false),
        /** 机器状态。 */
        STATUS("状态", "状", false),
        /** 方块类型；同类方块聚在一起，取不到方块 id 的排最后。 */
        LINE("方块", "块", false),
        /** 规则条数。 */
        RULES("规则数", "规", true),
        /** 距上次成功搬运的 tick；越近越前。 */
        RECENT("最近搬运", "近", false);

        private final String label;
        private final String glyph;
        private final boolean naturalDescending;

        MachineKey(String label, String glyph, boolean naturalDescending) {
            this.label = label;
            this.glyph = glyph;
            this.naturalDescending = naturalDescending;
        }

        /** 菜单里的完整文案。 */
        String label() {
            return this.label;
        }

        /** 排序按钮上的单字。 */
        String glyph() {
            return this.glyph;
        }

        /** 首次选中时的自然方向；{@code true} 表示降序。 */
        boolean naturalDescending() {
            return this.naturalDescending;
        }
    }

    /**
     * 构建仓库比较器。
     *
     * @param key         排序键
     * @param descending  是否降序
     * @param nameOf      资源显示名解析；调用方应传入带缓存的实现
     * @param modOf       模组显示名解析；调用方应传入带缓存的实现
     * @return 比较器；默认顺序返回 {@code null}
     */
    @Nullable
    static Comparator<FactoryStorageS2C.Entry> storage(StorageKey key, boolean descending,
            Function<AEKey, String> nameOf, Function<AEKey, String> modOf) {
        if (key == StorageKey.DEFAULT) {
            return null;
        }
        Comparator<FactoryStorageS2C.Entry> primary = switch (key) {
            case DEFAULT -> throw new IllegalStateException("默认排序没有比较器");
            case COUNT -> Comparator.comparingDouble(FactorySort::sortAmount);
            case NAME -> Comparator.comparing(entry -> nameOf.apply(entry.key()),
                    String.CASE_INSENSITIVE_ORDER);
            case MOD -> Comparator
                    .comparing((FactoryStorageS2C.Entry entry) -> modOf.apply(entry.key()),
                            String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(entry -> nameOf.apply(entry.key()),
                            String.CASE_INSENSITIVE_ORDER);
            case REGISTRY -> Comparator.comparing(entry -> entry.key().getId().toString());
            case KIND -> Comparator
                    .comparingInt((FactoryStorageS2C.Entry entry) ->
                            AeKeySupport.kind(entry.key()).ordinal())
                    .thenComparing(entry -> nameOf.apply(entry.key()),
                            String.CASE_INSENSITIVE_ORDER);
        };
        // 兜底键不随方向翻转：数量相同或同名时，顺序仍然确定。
        Comparator<FactoryStorageS2C.Entry> tie =
                Comparator.comparing(entry -> entry.key().getId().toString());
        return descending ? primary.reversed().thenComparing(tie) : primary.thenComparing(tie);
    }

    /**
     * 数量排序值。
     *
     * <p>流体在仓库格里按 B 渲染（1000 mB = 1 B），排序也换算成 B，避免显示 "2.0B"
     * 却按 2000 mB 与物品混排；物品、FE、化学品保持原单位（FE 没有 B 的概念）。</p>
     *
     * @param entry 仓库条目
     * @return 排序用数量
     */
    private static double sortAmount(FactoryStorageS2C.Entry entry) {
        return (entry.key() instanceof AEFluidKey)
                ? entry.count() / 1000.0D
                : entry.count();
    }

    /**
     * 排序机器列表。
     *
     * @param views      服务端顺序或搜索过滤后的机器
     * @param key        排序键
     * @param descending 是否降序
     * @return 排序后的不可变列表；默认顺序直接返回原列表
     */
    static List<MachineView> machines(List<MachineView> views, MachineKey key, boolean descending) {
        if (key == MachineKey.DEFAULT || views.size() < 2) {
            return views;
        }
        Comparator<MachineView> primary = switch (key) {
            case DEFAULT -> throw new IllegalStateException("默认排序没有比较器");
            case NAME -> Comparator.comparing(
                    machine -> FactoryLabels.blockName(machine.blockId()),
                    String.CASE_INSENSITIVE_ORDER);
            case POSITION -> Comparator
                    .comparing((MachineView machine) ->
                            machine.ref().dimension().location().toString())
                    .thenComparingInt(machine -> machine.ref().pos().getX())
                    .thenComparingInt(machine -> machine.ref().pos().getY())
                    .thenComparingInt(machine -> machine.ref().pos().getZ());
            case STATUS -> Comparator.comparingInt(machine -> stateRank(machine.totalState()));
            case LINE -> Comparator
                    .comparing((MachineView machine) -> machine.groupBlockId() == null)
                    .thenComparing(machine -> machine.groupBlockId() == null
                            ? "" : machine.groupBlockId().toString(),
                            String.CASE_INSENSITIVE_ORDER);
            case RULES -> Comparator.comparingInt(MachineView::ruleCount);
            case RECENT -> Comparator.comparingLong(machine -> machine.sinceMoveTicks() < 0L
                    ? Long.MAX_VALUE : machine.sinceMoveTicks());
        };
        Comparator<MachineView> tie =
                Comparator.comparing(machine -> machine.ref().id().toString());
        List<MachineView> sorted = new ArrayList<>(views);
        sorted.sort(descending ? primary.reversed().thenComparing(tie) : primary.thenComparing(tie));
        return List.copyOf(sorted);
    }

    /**
     * 状态在「状态」排序里的权重；数字越小越靠前。
     *
     * <p>不用枚举 ordinal：{@link TotalState} 以后可能扩展，界面顺序必须稳定。</p>
     */
    private static int stateRank(TotalState state) {
        return switch (state) {
            case RUNNING -> 0;
            case INPUT_OK -> 1;
            case WAITING_MATERIAL -> 2;
            case INPUT_BLOCKED -> 3;
            case IDLE -> 4;
            case UNCONFIGURED -> 5;
            case OFFLINE -> 6;
            case UNKNOWN -> 7;
        };
    }
}
