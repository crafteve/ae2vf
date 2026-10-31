package io.github.crafteve.idlefactory.client.screen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.factory.machine.MachineRef;
import appeng.api.stacks.AEKey;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;
import io.github.crafteve.idlefactory.network.payload.FactoryStorageS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTableS2C;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import io.github.crafteve.idlefactory.factory.storage.AeKeySupport;

/**
 * 工厂界面的客户端状态与派生视图。
 *
 * <p>只保存服务端已经算好的机器/仓库视图，以及界面自己的搜索、排序、滚动、展开和 tab；
 * 不探世界、不推导服务端状态。绘制、命中、输入都从这里读，避免同一份状态在多个类里各存一份。</p>
 *
 * <p>P1 删除产线后不再有「选中产线」这个状态：左表直接铺开工厂机器库里的每一台机器，
 * 拖拽改台数、加机器行等以产线堆叠组为前提的交互一并删除。</p>
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
    /** 服务端是否已经有工厂实例；没有时界面显示创建引导。 */
    boolean hasFactory;
    /** 仓库链接是否在线；表格中的数量仍以仓库包为准。 */
    boolean storageOnline;
    /** 左表当前滚动到的第一条机器下标。 */
    int firstRow;
    /** 当前右区 tab：仓库或机器。 */
    int activeTab = FactoryScreen.TAB_STORAGE;
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
    /** 已展开的左表行，按锚点机器 id 存；签名变化不影响展开状态。 */
    final Set<UUID> expandedStacks = new HashSet<>();

    /** 机器库展开的组，按锚点 id 记；一次只展开一张卡。 */
    @Nullable
    UUID expandedLibraryAnchor;

    /** 机器库展开区里最多列几台成员。 */
    static final int LIBRARY_MEMBER_ROWS = 8;
    /** 机器库成员行高。 */
    static final int LIBRARY_MEMBER_H = 16;

    /** 展开 IO 面板的像素滚动。 */
    int ioScroll;

    /** 当前解析后的搜索条件；响应器更新它并重建两侧视图。 */
    FactorySearchQuery searchQuery = FactorySearchQuery.EMPTY;
    /** 仓库搜索 + 排序后的可见条目。 */
    List<FactoryStorageS2C.Entry> filteredStorageEntries = List.of();
    /** 机器库搜索 + 排序后的可见视图；左表与机器库共用它。 */
    List<FactorySnapshotBuilder.MachineView> filteredMachines = List.of();

    /** 机器库按组聚合后的行；与 {@link #filteredMachines} 一起重算。 */
    List<FactoryMachineStack> libraryStacks = List.of();

    /** 左表逐台铺开后的行。 */
    List<FactoryMachineStack> tableStacks = List.of();
    /** 资源 → 搜索字段缓存；tooltip/tag 读取较重，避免每次过滤重复解析。 */
    final Map<AEKey, FactorySearchQuery.Fields> storageSearchFields = new HashMap<>();

    /** 跨界面保留的搜索词；关闭界面时写入。 */
    static String rememberedSearch = "";

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
        this.machines = payload.machines();
        Set<UUID> currentIds = new HashSet<>();
        for (FactorySnapshotBuilder.MachineView machine : this.machines) {
            currentIds.add(machine.ref().id());
        }
        this.expandedStacks.retainAll(currentIds);
        if (this.expandedLibraryAnchor != null
                && !currentIds.contains(this.expandedLibraryAnchor)) {
            // 展开的那台被删了：收起，避免下一次同名锚点出现时意外展开。
            this.expandedLibraryAnchor = null;
        }
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
                            && scope.accepts(AeKeySupport.kind(entry.key())),
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
     * 重算左表和机器库的行。
     *
     * <p>分组是纯视图操作，不缓存跨快照状态：每次可见视图变化都从头算，512 台上限下开销可以忽略。
     * 左表逐台铺开，机器库按「同方块、同规则」自动合并。</p>
     */
    private void rebuildStacks() {
        this.libraryStacks = FactoryMachineStack.library(this.filteredMachines);
        this.tableStacks = FactoryMachineStack.flat(this.filteredMachines);
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
     * 机器库卡片的展开高度；没展开或不是这一组时为 0。
     *
     * <p>最多列 {@link #LIBRARY_MEMBER_ROWS} 台，再多就写「还有 N 台」，避免一张卡撑满整页。</p>
     *
     * @param stack 机器组
     * @return 像素高度
     */
    int libraryDetailHeight(FactoryMachineStack stack) {
        if (this.expandedLibraryAnchor == null
                || !this.expandedLibraryAnchor.equals(stack.anchorId())) {
            return 0;
        }
        int shown = Math.min(stack.count(), LIBRARY_MEMBER_ROWS);
        int extra = stack.count() > shown ? 14 : 0;
        return shown * LIBRARY_MEMBER_H + extra + 4;
    }

    /**
     * 切换机器库某张卡的展开状态；一次只展开一张。
     *
     * @param stack 机器组
     */
    void toggleLibraryExpanded(FactoryMachineStack stack) {
        UUID anchorId = stack.anchorId();
        this.expandedLibraryAnchor = anchorId.equals(this.expandedLibraryAnchor) ? null : anchorId;
    }

    /**
     * 取一个资源 key 的搜索字段，按 key 缓存。
     *
     * @param key 资源
     * @return 搜索字段
     */
    FactorySearchQuery.Fields storageSearchFields(AEKey key) {
        return this.storageSearchFields.computeIfAbsent(key, k -> {
            String namespace = k.getId().getNamespace();
            return new FactorySearchQuery.Fields(FactoryUiText.resourceName(k),
                    k.getId().toString(), namespace, FactoryUiText.modName(namespace),
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
        String tooltip = FactoryStatus.text(machine, this.theme) + "\n"
                + machine.ref().dimension().location() + " "
                + machine.ref().pos().toShortString() + "\n"
                + "规则 " + machine.ruleCount() + " 条";
        return new FactorySearchQuery.Fields(name, id, namespace,
                FactoryUiText.modName(namespace), tooltip, List.of());
    }

    /**
     * 左表最多能滚到第几行。
     *
     * @return 最大首行下标；不足一屏为 0
     */
    int maxFirstRow() {
        return Math.max(0, this.tableStacks.size());
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
                    AeKeySupport.kind(rules.get(i).key()));
            width += FactoryLayout.CELL + 2 + this.font.width(rate) + 6;
        }
        // 末尾空槽是拖放目标，列宽必须给它留位置。
        width += FactoryLayout.CELL + 2;
        return Math.max(72, width);
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
     * 机器库一屏能放几行卡片；按展开后的实际高度算。
     *
     * @return 可见行数
     */
    int machineRowsVisible() {
        if (this.layout == null) {
            return 1;
        }
        int bottom = this.layout.gridY + this.layout.gridRows * FactoryLayout.CELL;
        int y = this.layout.gridY;
        int count = 0;
        for (int i = this.machineFirstRow; i < this.libraryStacks.size() && y < bottom; i++) {
            y += FactoryLayout.ROW_H + libraryDetailHeight(this.libraryStacks.get(i));
            count++;
        }
        return Math.max(1, count);
    }

    /**
     * 机器库最多能滚到第几行。
     *
     * <p>展开会让可见行数变化，这里只保证能滚到最后一张卡，不追求精确的「最后满屏」。</p>
     *
     * @return 最大首行下标
     */
    int maxMachineFirstRow() {
        return Math.max(0, this.libraryStacks.size() - 1);
    }
}
