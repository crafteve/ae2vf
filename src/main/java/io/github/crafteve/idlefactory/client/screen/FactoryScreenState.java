package io.github.crafteve.idlefactory.client.screen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.factory.machine.MachineRef;
import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;
import io.github.crafteve.idlefactory.network.payload.FactoryStorageS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTableS2C;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

/**
 * 工厂界面的客户端状态与派生视图。
 *
 * <p>只保存服务端已经算好的机器/仓库视图，以及界面自己的搜索、排序、滚动、展开、tab
 * 和选中产线；不探世界、不推导服务端状态。绘制、命中、输入都从这里读，避免同一份状态
 * 在多个类里各存一份。</p>
 *
 * <p>字段保持包内可见：同包的渲染器/输入类直接读，减少一层无意义的 getter；可变集合
 * 不暴露给包外。</p>
 */
final class FactoryScreenState {
    /** 当前主题；状态色与搜索字段共用。 */
    final FactoryGuiTheme theme;
    /** 当前布局；由界面 init 绑定，状态自己的行数/列宽计算也用它。 */
    FactoryLayout layout;
    /** 当前字体；搜索字段与列宽需要。 */
    Font font;
    /** 排序按钮；init 前可能为 {@code null}，此时退回不过滤、不排序。 */
    @Nullable
    FactorySortButtons sortButtons;

    /** 服务端最近一次给的全量机器视图；左表和机器库都从这份数据派生。 */
    List<FactorySnapshotBuilder.MachineView> machines = List.of();
    /** 服务端最近一次给的产线列表；产线下拉从这里取。 */
    List<FactorySnapshotBuilder.LineView> lines = List.of();
    /** 服务端是否已经有工厂实例；没有时界面显示创建引导。 */
    boolean hasFactory;
    /** 仓库链接是否在线；表格中的数量仍以仓库包为准。 */
    boolean storageOnline;
    /** 工厂产线总数。 */
    int lineCount;
    /** 当前运行中的产线数。 */
    int runningLineCount;
    /** 左表当前滚动到的第一条机器下标。 */
    int firstRow;
    /** 当前右区 tab：仓库或机器。 */
    int activeTab = FactoryScreen.TAB_STORAGE;
    /** 左表当前选中的产线 id；null 表示还没选择产线，此时左表为空。 */
    @Nullable
    UUID selectedLineId;
    /** 客户端仓库视图；全量快照在这里做稳定排序和过滤。 */
    final FactoryStorageRepo storageRepo = new FactoryStorageRepo();
    /** 正在拼装的分片快照；last 到齐后才替换 {@link #storageRepo}。 */
    final List<FactoryStorageS2C.Entry> pendingStorageEntries = new ArrayList<>();
    /** 下一个期望的分片序号，用来丢掉重复/错序的包。 */
    int pendingStoragePart;
    /** 是否已经收到过一次仓库快照。 */
    boolean storageReceived;
    /** 服务端仓库快照是否因上限被截断。 */
    boolean storageTruncated;
    /** 仓库当前滚动到的第一条记录。 */
    int storageFirstRow;
    /** 机器库当前滚动到的第一台机器。 */
    int machineFirstRow;
    /** 已展开的机器；同一时间最多一台，快照刷新后仍能保持展开。 */
    /** 展开的组，按锚点机器 id 存；签名变化不影响展开状态。 */
    final Set<UUID> expandedStacks = new HashSet<>();

    /** 滚轮改台数的合并窗口（毫秒）；滚轮一格在 Windows 上常一次发多个事件。 */
    static final long STACK_SCROLL_MERGE_MS = 150L;
    /** 发出改数量请求后，本地乐观显示最多保留多久（毫秒），等快照回来。 */
    static final long STACK_SCROLL_ECHO_MS = 500L;
    /** 一组最多多少台；和服务端 LineActions.MAX_STACK 一致。 */
    static final int MAX_STACK_COUNT = 512;

    /** 正在用滚轮改的组锚点；{@code null} 表示没有待发请求。 */
    @Nullable
    UUID pendingStackAnchorId;
    /** 目标台数，本地乐观显示用。 */
    int pendingStackTarget;
    /** 最后一次滚轮时间。 */
    long pendingStackChangedAt;
    /** 请求发出的时间；{@code 0} 表示还没发。 */
    long pendingStackSentAt;
    /** 展开 IO 面板的像素滚动。 */
    int ioScroll;

    /** 当前解析后的搜索条件；响应器更新它并重建两侧视图。 */
    FactorySearchQuery searchQuery = FactorySearchQuery.EMPTY;
    /** 仓库搜索 + 排序后的可见条目。 */
    List<FactoryStorageS2C.Entry> filteredStorageEntries = List.of();
    /** 机器库搜索 + 排序后的可见视图；左表还会再按当前产线过滤。 */
    List<FactorySnapshotBuilder.MachineView> filteredMachines = List.of();

    /** 机器库按组聚合后的行；与 {@link #filteredMachines} 一起重算。 */
    List<FactoryMachineStack> libraryStacks = List.of();

    /** 当前产线按组聚合后的行；左表只画这些。 */
    List<FactoryMachineStack> tableStacks = List.of();
    /** 资源 → 搜索字段缓存；tooltip/tag 读取较重，避免每次过滤重复解析。 */
    final Map<ResourceKey, FactorySearchQuery.Fields> storageSearchFields = new HashMap<>();

    /** 跨界面保留的搜索词；关闭界面时写入。 */
    static String rememberedSearch = "";
    /** 跨界面保留的产线选择；acceptTable 会校验该产线仍存在。 */
    @Nullable
    static UUID rememberedLineId;

    /**
     * @param theme 当前主题
     */
    FactoryScreenState(FactoryGuiTheme theme) {
        this.theme = theme;
    }

    /**
     * 绑定界面尺寸/字体/排序按钮；由 {@link FactoryScreen#init()} 调用。
     *
     * @param layout      当前布局
     * @param font        当前字体
     * @param sortButtons 排序按钮；可能为 {@code null}
     */
    void bind(FactoryLayout layout, Font font, @Nullable FactorySortButtons sortButtons) {
        this.layout = layout;
        this.font = font;
        this.sortButtons = sortButtons;
    }

    /**
     * 收到服务端机器表快照。
     *
     * @param payload 机器表快照
     */
    void acceptTable(FactoryTableS2C payload) {
        this.hasFactory = payload.hasFactory();
        this.storageOnline = payload.storageOnline();
        this.lineCount = payload.lineCount();
        this.runningLineCount = payload.runningLineCount();
        this.lines = payload.lines();
        this.machines = payload.machines();
        Set<UUID> currentIds = new HashSet<>();
        for (FactorySnapshotBuilder.MachineView machine : this.machines) {
            currentIds.add(machine.ref().id());
        }
        this.expandedStacks.retainAll(currentIds);
        if (this.selectedLineId != null && findLine(this.selectedLineId) == null) {
            this.selectedLineId = null;
        }
        rememberedLineId = this.selectedLineId;
        if (!this.hasFactory) {
            this.storageRepo.clear();
            this.storageSearchFields.clear();
            this.storageReceived = false;
        }
        rebuildFiltered();
        updateColumnWidths();
    }

    /**
     * 收到服务端仓库快照分片。
     *
     * @param payload 仓库快照
     */
    void acceptStorage(FactoryStorageS2C payload) {
        if (payload.part() == 0) {
            this.pendingStorageEntries.clear();
            this.pendingStoragePart = 0;
            this.storageReceived = false;
        } else if (payload.part() != this.pendingStoragePart) {
            // 错序或重复的分片直接丢弃，等下一份完整快照。
            return;
        }
        this.pendingStorageEntries.addAll(payload.entries());
        this.pendingStoragePart = payload.part() + 1;
        if (!payload.last()) {
            return;
        }
        this.storageRepo.replace(this.pendingStorageEntries);
        this.pendingStorageEntries.clear();
        this.storageOnline = payload.online();
        this.storageTruncated = payload.truncated();
        this.storageReceived = true;
        rebuildFiltered();
    }

    /**
     * 重新计算仓库和机器库的可见视图，并夹紧两个滚动位置。
     */
    void rebuildFiltered() {
        FactorySortButtons buttons = this.sortButtons;
        FactorySortButtons.Scope scope = buttons == null
                ? FactorySortButtons.Scope.ALL : buttons.storageScope();
        Comparator<FactoryStorageS2C.Entry> comparator = buttons == null ? null
                : FactorySort.storage(buttons.storageKey(), buttons.storageDescending(),
                        key -> storageSearchFields(key).name(),
                        key -> storageSearchFields(key).modName());
        if (this.searchQuery.isEmpty() && scope == FactorySortButtons.Scope.ALL) {
            this.storageRepo.configure(null, comparator);
        } else {
            this.storageRepo.configure(entry ->
                    (this.searchQuery.isEmpty()
                            || this.searchQuery.matches(storageSearchFields(entry.key())))
                            && scope.accepts(entry.key().kind()),
                    comparator);
        }

        List<FactorySnapshotBuilder.MachineView> visible;
        if (this.searchQuery.isEmpty()) {
            visible = this.machines;
        } else {
            List<FactorySnapshotBuilder.MachineView> filtered = new ArrayList<>();
            for (FactorySnapshotBuilder.MachineView machine : this.machines) {
                if (this.searchQuery.matches(machineSearchFields(machine))) {
                    filtered.add(machine);
                }
            }
            visible = filtered;
        }
        this.filteredMachines = buttons == null ? visible
                : FactorySort.machines(visible, buttons.machineKey(), buttons.machineDescending());

        this.filteredStorageEntries = this.storageRepo.view();
        rebuildStacks();
        this.firstRow = Math.max(0, Math.min(this.firstRow, maxFirstRow()));
        this.storageFirstRow = Math.max(0,
                Math.min(this.storageFirstRow, maxStorageFirstRow()));
        this.machineFirstRow = Math.max(0,
                Math.min(this.machineFirstRow, maxMachineFirstRow()));
    }

    /**
     * 重算左表和机器库的组行。
     *
     * <p>分组是纯视图操作，不缓存跨快照状态：每次可见视图变化都从头算，512 台上限下开销可以忽略。
     * 先算机器库是因为它用 {@code filteredMachines}（已搜索、已排序），左表则按机器库登记顺序。</p>
     */
    private void rebuildStacks() {
        this.libraryStacks = FactoryMachineStack.library(this.filteredMachines);
        this.tableStacks = FactoryMachineStack.table(tableMachines());
    }

    /**
     * 排序键或方向变化后重建视图，两个列表都回到顶部。
     */
    void onSortChanged() {
        this.storageFirstRow = 0;
        this.machineFirstRow = 0;
        rebuildFiltered();
    }

    /**
     * 更新搜索条件并重建两侧视图。
     *
     * @param query 新搜索条件
     */
    void setSearchQuery(FactorySearchQuery query) {
        this.searchQuery = query;
        rebuildFiltered();
    }

    /**
     * 滚轮改某组台数：先从当前台数起算，同一组的连续滚动会累加。
     *
     * @param stack 机器组
     * @param delta 增量；正数加、负数减
     */
    void scrollStackCount(FactoryMachineStack stack, int delta) {
        long now = System.currentTimeMillis();
        if (!stack.anchorId().equals(this.pendingStackAnchorId)) {
            this.pendingStackAnchorId = stack.anchorId();
            this.pendingStackTarget = stack.count();
            this.pendingStackSentAt = 0L;
        } else if (this.pendingStackSentAt > 0L) {
            // 上一个请求已经发出，又在它回来之前继续滚：以当前目标为基准接着加。
            this.pendingStackSentAt = 0L;
        }
        this.pendingStackTarget = Math.max(1,
                Math.min(MAX_STACK_COUNT, this.pendingStackTarget + delta));
        this.pendingStackChangedAt = now;
    }

    /**
     * 该组的显示台数：有未发出的滚轮请求时用目标值，其余用真实台数。
     *
     * @param stack 机器组
     * @return 显示用台数
     */
    int displayCount(FactoryMachineStack stack) {
        long now = System.currentTimeMillis();
        if (stack.anchorId().equals(this.pendingStackAnchorId)) {
            if (this.pendingStackSentAt == 0L
                    || now - this.pendingStackSentAt <= STACK_SCROLL_ECHO_MS) {
                return this.pendingStackTarget;
            }
        }
        return stack.count();
    }

    /**
     * 是否该把滚轮累计的台数发给服务端。
     *
     * @param now 当前毫秒
     * @return 停手超过合并窗口且还没发过时为 {@code true}
     */
    boolean pendingStackDue(long now) {
        return this.pendingStackAnchorId != null && this.pendingStackSentAt == 0L
                && now - this.pendingStackChangedAt >= STACK_SCROLL_MERGE_MS;
    }

    /** 标记请求已发出，本地乐观显示开始倒计时。 */
    void markPendingStackSent(long now) {
        this.pendingStackSentAt = now;
    }

    /**
     * 乐观显示是否过期；过期后回到服务端快照的真实台数。
     *
     * @param now 当前毫秒
     * @return 过期为 {@code true}
     */
    boolean pendingStackExpired(long now) {
        return this.pendingStackAnchorId != null && this.pendingStackSentAt > 0L
                && now - this.pendingStackSentAt > STACK_SCROLL_ECHO_MS;
    }

    /** 放弃未发出的滚轮请求。 */
    void clearPendingStack() {
        this.pendingStackAnchorId = null;
        this.pendingStackSentAt = 0L;
    }

    /**
     * 取一个资源 key 的搜索字段，按 key 缓存。
     *
     * @param key 资源
     * @return 搜索字段
     */
    FactorySearchQuery.Fields storageSearchFields(ResourceKey key) {
        return this.storageSearchFields.computeIfAbsent(key, k -> {
            String namespace = k.id().getNamespace();
            return new FactorySearchQuery.Fields(FactoryUiText.resourceName(k),
                    k.id().toString(), namespace, FactoryUiText.modName(namespace),
                    FactoryUiText.resourceTooltip(Minecraft.getInstance(), k),
                    FactoryUiText.resourceTags(k));
        });
    }

    /**
     * 构造一台机器的搜索字段。
     *
     * @param machine 机器视图
     * @return 搜索字段
     */
    FactorySearchQuery.Fields machineSearchFields(FactorySnapshotBuilder.MachineView machine) {
        String id = machine.blockId() == null ? "" : machine.blockId().toString();
        String namespace = machine.blockId() == null ? "" : machine.blockId().getNamespace();
        String name = FactoryLabels.blockName(machine.blockId());
        if (!machine.lineNames().isEmpty()) {
            name += " " + String.join(" ", machine.lineNames());
        }
        String tooltip = FactoryStatus.text(machine, this.theme) + "\n"
                + machine.ref().dimension().location() + " "
                + machine.ref().pos().toShortString() + "\n"
                + (machine.lineNames().isEmpty()
                        ? "未编线" : "产线 " + String.join("/", machine.lineNames()))
                + "\n规则 " + machine.ruleCount() + " 条";
        return new FactorySearchQuery.Fields(name, id, namespace,
                FactoryUiText.modName(namespace), tooltip, List.of());
    }

    /**
     * 左表最多能滚到第几行。
     *
     * @return 最大首行下标；未选产线为 0
     */
    int maxFirstRow() {
        if (this.selectedLineId == null) {
            return 0;
        }
        return Math.max(0, this.tableStacks.size());
    }

    /**
     * 当前左表产线下的机器；没有选产线时返回空表。
     *
     * @return 当前产线机器
     */
    List<FactorySnapshotBuilder.MachineView> tableMachines() {
        if (this.selectedLineId == null) {
            return List.of();
        }
        List<FactorySnapshotBuilder.MachineView> filtered = new ArrayList<>();
        for (FactorySnapshotBuilder.MachineView machine : this.machines) {
            if (machine.lineIds().contains(this.selectedLineId)) {
                filtered.add(machine);
            }
        }
        return filtered;
    }

    /**
     * 按当前内容重算四列宽度。
     */
    void updateColumnWidths() {
        if (this.layout == null || this.font == null) {
            return;
        }
        int name = 72;
        int state = 64;
        int input = 72;
        int output = 72;
        for (FactoryMachineStack stack : this.tableStacks) {
            name = Math.max(name,
                    this.font.width(FactoryLabels.blockName(stack.blockId()))
                            + FactoryLayout.CELL + 12);
            input = Math.max(input, ruleWidth(stack.inputs(), stack.inputRates()));
            output = Math.max(output, ruleWidth(stack.outputs(), stack.outputRates()));
        }
        for (FactoryMachineStack stack : this.libraryStacks) {
            state = Math.max(state, this.font.width(stack.statusText(this.theme)) + 17);
        }
        this.layout.relayoutColumns(new int[] { name, state, input, output });
    }

    /**
     * 估一条规则列需要的宽度。
     *
     * @param rules 规则
     * @param rates 规则速率
     * @return 最小 72 的列宽
     */
    int ruleWidth(List<ResourceSpec> rules, List<Long> rates) {
        int width = 4;
        for (int i = 0; i < Math.min(3, rules.size()); i++) {
            String rate = FactoryLabels.rateText(i < rates.size() ? rates.get(i) : 0L,
                    rules.get(i).key().kind());
            width += FactoryLayout.CELL + 2 + this.font.width(rate) + 6;
        }
        // 末尾空槽是拖放目标，列宽必须给它留位置。
        width += FactoryLayout.CELL + 2;
        return Math.max(72, width);
    }

    /**
     * 顶栏产线按钮的文案。
     *
     * @return 按钮文字
     */
    String lineButtonText() {
        FactorySnapshotBuilder.LineView line = selectedLine();
        return line == null ? "选择产线" : line.name();
    }

    /**
     * 当前选中的产线。
     *
     * @return 产线；未选择或已被删除时为 {@code null}
     */
    @Nullable
    FactorySnapshotBuilder.LineView selectedLine() {
        return this.selectedLineId == null ? null : findLine(this.selectedLineId);
    }

    /**
     * 当前产线是否处于不执行 IO 的状态。
     *
     * @return 已暂停 / 睡眠中；运行中或未选线为 {@code null}
     */
    @Nullable
    String lineIdleLabel() {
        FactorySnapshotBuilder.LineView line = selectedLine();
        if (line == null) {
            return null;
        }
        if (!line.running()) {
            return "已暂停";
        }
        return line.sleeping() ? "睡眠中" : null;
    }

    /**
     * 按 id 找产线。
     *
     * @param id 产线 id
     * @return 产线；不存在时为 {@code null}
     */
    @Nullable
    FactorySnapshotBuilder.LineView findLine(UUID id) {
        for (FactorySnapshotBuilder.LineView line : this.lines) {
            if (line.id().equals(id)) {
                return line;
            }
        }
        return null;
    }

    /**
     * 选中一条产线；切换后左表从第一行重新显示。
     *
     * @param lineId 产线 id；{@code null} 表示清空
     */
    void selectLine(@Nullable UUID lineId) {
        if (Objects.equals(this.selectedLineId, lineId)) {
            return;
        }
        this.selectedLineId = lineId;
        rememberedLineId = lineId;
        this.firstRow = 0;
        rebuildStacks();
        updateColumnWidths();
    }

    /**
     * 仓库最多能滚到第几行。
     *
     * @return 最大首行下标；条目不足一屏时为 0
     */
    int maxStorageFirstRow() {
        if (this.layout == null) {
            return 0;
        }
        int rows = (this.filteredStorageEntries.size() + FactoryLayout.GRID_COLS - 1)
                / FactoryLayout.GRID_COLS;
        return Math.max(0, rows - this.layout.gridRows);
    }

    /**
     * 机器库一屏能放几行卡片。
     *
     * @return 可见行数
     */
    int machineRowsVisible() {
        if (this.layout == null) {
            return 1;
        }
        return Math.max(1, this.layout.gridRows * FactoryLayout.CELL / FactoryLayout.ROW_H);
    }

    /**
     * 机器库最多能滚到第几行。
     *
     * @return 最大首行下标；不足一屏为 0
     */
    int maxMachineFirstRow() {
        return Math.max(0, this.libraryStacks.size() - machineRowsVisible());
    }
}
