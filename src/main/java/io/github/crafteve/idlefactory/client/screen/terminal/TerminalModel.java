package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import appeng.api.config.SortDir;

import io.github.crafteve.idlefactory.client.screen.search.SearchQuery;
import io.github.crafteve.idlefactory.client.screen.sort.SortContext;
import io.github.crafteve.idlefactory.client.screen.sort.SortKey;
import io.github.crafteve.idlefactory.menu.FactorySortMode;
import io.github.crafteve.idlefactory.view.ViewTable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 两个终端共用的只读模型基座：数据源、搜索词、排序档位 / 方向、玩家位置与可见行。
 *
 * <p>流程固定：{@link #accept(ViewTable)} 收下拼好的表 → {@link #buildRows} 把表变成行 →
 * {@link #matches} 过滤 → {@link #sortKey} 排序。子类只实现这四件事，不再各自维护搜索与排序
 * 状态，也不用反查客户端世界取玩家位置。</p>
 *
 * @param <T> 行类型（节点行 / 机器块）
 */
public abstract class TerminalModel<T> {
    private final TerminalSource source;
    @Nullable
    private ViewTable table;
    private SearchQuery query = SearchQuery.EMPTY;
    private FactorySortMode sortMode = FactorySortMode.NAME;
    private SortDir sortDirection = SortDir.ASCENDING;
    @Nullable
    private ResourceKey<Level> playerDimension;
    @Nullable
    private BlockPos playerPos;
    private boolean sortFrozen;
    private List<T> rows = List.of();

    /**
     * @param source 终端数据源
     */
    protected TerminalModel(TerminalSource source) {
        this.source = source;
    }

    /** 数据源；屏幕发请求与取版本用。 */
    public final TerminalSource source() {
        return this.source;
    }

    /**
     * 收下一张拼好的视图表并重建行。
     *
     * @param next 视图表
     */
    public final void accept(ViewTable next) {
        this.table = next;
        rebuild();
    }

    /**
     * 更新搜索词并重建行。
     *
     * @param next 新搜索条件；{@code null} 视为空条件
     */
    public final void setQuery(@Nullable SearchQuery next) {
        this.query = next == null ? SearchQuery.EMPTY : next;
        rebuild();
    }

    /**
     * 更新排序档位与方向；只记录，调用方随后调 {@link #resort()}。
     *
     * @param mode      档位
     * @param direction 方向
     */
    public final void setSort(FactorySortMode mode, SortDir direction) {
        this.sortMode = mode;
        this.sortDirection = direction;
    }

    /**
     * 更新玩家位置；距离档用它算最近成员。
     *
     * @param dimension 玩家维度；未知时 {@code null}
     * @param pos       玩家脚下方块坐标；未知时 {@code null}
     */
    public final void setPlayerContext(@Nullable ResourceKey<Level> dimension,
            @Nullable BlockPos pos) {
        this.playerDimension = dimension;
        this.playerPos = pos;
    }

    /**
     * 冻结或解冻行序；冻结时表照常更新，但行序保持不变。
     *
     * @param frozen 是否冻结
     */
    public final void setSortFrozen(boolean frozen) {
        this.sortFrozen = frozen;
    }

    /** 按当前表与排序设置重新排序。 */
    public final void resort() {
        rebuild();
    }

    /** 玩家所在维度；未知时 {@code null}（子类算距离用）。 */
    @Nullable
    protected final ResourceKey<Level> playerDimension() {
        return this.playerDimension;
    }

    /** 玩家脚下方块坐标；未知时 {@code null}（子类算距离用）。 */
    @Nullable
    protected final BlockPos playerPosition() {
        return this.playerPos;
    }

    /** 当前可见行。 */
    public final List<T> rows() {
        return this.rows;
    }

    /** 服务端是否已经有工厂。 */
    public final boolean hasFactory() {
        return this.table != null && this.table.header().hasFactory();
    }

    /** 仓库是否在线。 */
    public final boolean storageOnline() {
        return this.table != null && this.table.header().storageOnline();
    }

    /** 当前搜索词是否命中任何行。 */
    public final boolean filteredEmpty() {
        return !this.query.isEmpty() && this.rows.isEmpty();
    }

    /**
     * 把表变成行：先过滤、再排序。
     *
     * <p>顺序只在 {@link SortKey} 一处定义，方向按钮把整套比较反过来。</p>
     */
    private void rebuild() {
        if (this.table == null) {
            this.rows = List.of();
            return;
        }
        List<T> next = new ArrayList<>();
        for (T row : buildRows(this.table)) {
            if (this.query.isEmpty() || this.query.matches(fields(row))) {
                next.add(row);
            }
        }
        SortContext context = new SortContext(this.sortMode, this.sortDirection,
                this.playerDimension, this.playerPos);
        if (this.sortFrozen) {
            this.rows = SortKey.preserveOrder(next, this.rows, this::rowId, this::sortKey, context);
        } else {
            this.rows = SortKey.sorted(next, this::sortKey, context);
        }
    }

    /**
     * 把整张表展开成行（尚未过滤、尚未排序）。
     *
     * @param table 视图表
     * @return 行
     */
    protected abstract List<T> buildRows(ViewTable table);

    /**
     * 一行的搜索字段。
     *
     * @param row 行
     * @return 搜索字段
     */
    protected abstract SearchQuery.Fields fields(T row);

    /**
     * 一行的排序键。
     *
     * @param row 行
     * @return 排序键
     */
    protected abstract SortKey sortKey(T row);

    /**
     * 一行的稳定 id，冻结顺序时对齐用。
     *
     * @param row 行
     * @return 节点 id
     */
    protected abstract UUID rowId(T row);

}
