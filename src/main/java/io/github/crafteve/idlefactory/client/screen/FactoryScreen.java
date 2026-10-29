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

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.storage.FactoryStorageRepo;
import io.github.crafteve.idlefactory.client.theme.GuiTheme;
import io.github.crafteve.idlefactory.client.widget.GuiLayer;
import io.github.crafteve.idlefactory.client.widget.GuiTextures;
import io.github.crafteve.idlefactory.client.widget.NineSlice;
import io.github.crafteve.idlefactory.client.widget.SearchEditBox;
import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.factory.io.IoResult;
import io.github.crafteve.idlefactory.factory.line.IoSide;
import io.github.crafteve.idlefactory.factory.machine.MachineRef;
import io.github.crafteve.idlefactory.factory.resource.MatchMode;
import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
import io.github.crafteve.idlefactory.factory.resource.ResourceKind;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;
import io.github.crafteve.idlefactory.integration.jecharacters.JecharactersCompat;
import io.github.crafteve.idlefactory.menu.FactoryMenu;
import io.github.crafteve.idlefactory.network.payload.FactoryEditFeedbackS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryStorageActionC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryStorageActionC2S.Gesture;
import io.github.crafteve.idlefactory.network.payload.FactoryStorageRequestC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryStorageS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTableRequestC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryTableS2C;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 工厂工作台界面。
 *
 * <p>界面分成四块：顶栏负责工厂状态、产线筛选和统计；左表显示服务端
 * {@link FactoryTableS2C} 给出的机器视图；右侧上半区在「仓库」和「机器」
 * 两个 tab 间切换；右上角搜索框只过滤当前可见内容，不改变服务端数据。</p>
 *
 * <p>左表的每一行对应一个 {@link FactorySnapshotBuilder.MachineView}。行高不是固定的：
 * 机器可以展开成「输入/输出」两行规则详情，所以绘制和命中测试统一走
 * {@link #tableHitAt(double)}，不能再用固定 {@code rowY} 推算。</p>
 *
 * <p>仓库区不伪造原版槽位，而是用 {@link FactoryStorageRepo} 维护服务端快照。
 * 仓库格点击只发送 {@code FactoryStorageActionC2S} 手势，实际数量、背包扣减和
 * carried 同步全部由服务端决定；这样 AE2 的大数量物品和容器仓库都能走同一条路。</p>
 *
 * <p>机器库使用深色卡片、独立图标槽和状态色条；所有静态外观来自 {@link NineSlice}
 * 贴图，本类只负责坐标与绘制顺序。几何值集中在 {@link FactoryLayout}，调布局尺寸
 * 不应改这里。</p>
 */
public class FactoryScreen extends AbstractContainerScreen<FactoryMenu> {
    /** 标题文字与「选择产线」按钮之间的水平间距。 */
    private static final int TITLE_GAP = 10;
    /** 顶栏关闭按钮的边长。 */
    private static final int CLOSE_SIZE = 14;
    /** 机器卡片第一行文字：机器名，使用接近白色的冷色。 */
    private static final int CARD_NAME = 0xFFE8ECF5;
    /** 机器卡片第二行右侧：规则标签，使用灰蓝色。 */
    private static final int CARD_META = 0xFF98A2B8;

    /** 左表每隔多少 tick 主动请求一次机器快照。 */
    private static final int REQUEST_INTERVAL = 20;
    /** 仓库 tab 打开时每隔多少 tick 刷新一次仓库快照。 */
    private static final int STORAGE_REQUEST_INTERVAL = 100;
    /** tab 下标：仓库。 */
    private static final int TAB_STORAGE = 0;
    /** tab 下标：机器。 */
    private static final int TAB_MACHINES = 1;

    private final GuiTheme theme = GuiTheme.current();
    private FactoryLayout layout;
    private SearchEditBox searchBox;
    /** 服务端最近一次给的全量机器视图；左表和机器库都从这份数据派生。 */
    private List<FactorySnapshotBuilder.MachineView> machines = List.of();
    /** 服务端最近一次给的产线列表；产线下拉从这里取。 */
    private List<FactorySnapshotBuilder.LineView> lines = List.of();
    /** 服务端是否已经有工厂实例；没有时界面显示创建引导。 */
    private boolean hasFactory;
    /** 仓库链接是否在线；表格中的数量仍以仓库包为准。 */
    private boolean storageOnline;
    /** 工厂产线总数。 */
    private int lineCount;
    /** 当前运行中的产线数。 */
    private int runningLineCount;
    /** 左表当前滚动到的第一条机器下标。 */
    private int firstRow;
    /** 机器表请求节拍器。 */
    private int requestTicker;

    /** 当前右区 tab：仓库或机器。 */
    private int activeTab = TAB_STORAGE;
    /** 左表当前选中的产线 id；null 表示还没选择产线，此时左表为空。 */
    @Nullable
    private UUID selectedLineId;
    /** 编辑态总控：产线下拉、命名/确认浮层、反馈。 */
    @Nullable
    private FactoryEditController editController;
    /** 右区排序按钮：键、方向、范围。 */
    @Nullable
    private FactorySortButtons sortButtons;
    /** 客户端仓库视图；全量快照在这里做稳定排序和过滤。 */
    private final FactoryStorageRepo storageRepo = new FactoryStorageRepo();
    /** 正在拼装的分片快照；last 到齐后才替换 {@link #storageRepo}。 */
    private final List<FactoryStorageS2C.Entry> pendingStorageEntries = new ArrayList<>();
    /** 下一个期望的分片序号，用来丢掉重复/错序的包。 */
    private int pendingStoragePart;
    /** 是否已经收到过一次仓库快照。 */
    private boolean storageReceived;
    /** 服务端仓库快照是否因上限被截断。 */
    private boolean storageTruncated;
    /** 仓库当前滚动到的第一条记录。 */
    private int storageFirstRow;
    /** 机器库当前滚动到的第一台机器。 */
    private int machineFirstRow;
    /** 仓库请求节拍器。 */
    private int storageRequestTicker;
    /** 已展开的机器；同一时间最多一台，快照刷新后仍能保持展开。 */
    private final Set<MachineRef> expandedMachines = new HashSet<>();
    /** 展开 IO 面板的像素滚动。 */
    private int ioScroll;

    private FactorySearchQuery searchQuery = FactorySearchQuery.EMPTY;
    private List<FactoryStorageS2C.Entry> filteredStorageEntries = List.of();
    private List<FactorySnapshotBuilder.MachineView> filteredMachines = List.of();
    private final Map<ResourceKey, FactorySearchQuery.Fields> storageSearchFields =
            new HashMap<>();
    private static String rememberedSearch = "";
    @Nullable
    private static UUID rememberedLineId;
    /** 本帧鼠标位置，用于槽位背景悬停；{@code renderSlot} 本身不接收鼠标坐标。*/
    private int renderMouseX;
    private int renderMouseY;

    /**
     * @param menu            菜单
     * @param playerInventory 玩家背包
     * @param title           标题
     */
    public FactoryScreen(FactoryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = FactoryLayout.MIN_PANEL_W;
        this.imageHeight = FactoryLayout.MIN_PANEL_H;
    }

    @Override
    protected void init() {
        this.layout = FactoryLayout.of(this.width, this.height);
        this.imageWidth = this.layout.panelW;
        this.imageHeight = this.layout.panelH;
        // AbstractContainerScreen 用 imageWidth/imageHeight 计算居中位置；
        // 这里让它们和 FactoryLayout 的 panelW/panelH 完全一致，避免自己再算一份偏移。
        super.init();
        if (this.editController == null) {
            this.editController = new FactoryEditController(this, this.font, this.theme);
        }
        this.editController.rebind(this.layout);
        this.editController.closeAll();
        FactorySortButtons buttons = this.sortButtons;
        if (buttons == null) {
            buttons = new FactorySortButtons(this::onSortChanged);
            this.sortButtons = buttons;
        }
        if (this.selectedLineId == null) {
            this.selectedLineId = rememberedLineId;
        }
        positionSlots();
        String previousQuery = this.searchBox == null
                ? rememberedSearch
                : this.searchBox.getValue();
        int searchX = this.layout.rightX + 4;
        int searchW = this.layout.searchWidth();
        this.searchBox = new SearchEditBox(this.font, searchX + 18,
                this.layout.searchY + 3, searchW - 32, 10,
                this.theme, Component.literal("搜索…"));
        this.searchBox.setResponder(value -> {
            this.searchQuery = FactorySearchQuery.parse(value, JecharactersCompat::contains);
            rebuildFiltered();
        });
        this.searchBox.setValue(previousQuery);
        this.addRenderableWidget(this.searchBox);
        this.searchQuery = FactorySearchQuery.parse(previousQuery, JecharactersCompat::contains);
        rebuildFiltered();
        logLayout();
        requestTable();
        if (this.activeTab == TAB_STORAGE) {
            requestStorage();
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (this.editController != null) {
            this.editController.tick();
        }
        if (++this.requestTicker >= REQUEST_INTERVAL) {
            this.requestTicker = 0;
            requestTable();
        }
        if (this.activeTab == TAB_STORAGE
                && ++this.storageRequestTicker >= STORAGE_REQUEST_INTERVAL) {
            requestStorage();
        }
    }

    /** 向服务端要一份当前机器表。*/
    private void requestTable() {
        if (this.minecraft != null && this.minecraft.player != null) {
            PacketDistributor.sendToServer(new FactoryTableRequestC2S(this.menu.containerId));
        }
    }

    /** 向服务端要一份当前仓库摘要。*/
    private void requestStorage() {
        this.storageRequestTicker = 0;
        if (this.minecraft != null && this.minecraft.player != null) {
            PacketDistributor.sendToServer(new FactoryStorageRequestC2S(this.menu.containerId));
        }
    }

    /**
     * 收到服务端机器表快照。
     *
     * <p>由 {@code ClientFactoryTableHandler} 在客户端线程调用。这里只保存服务端已经
     * 计算好的视图，不重新观察世界、不推导工作状态；收到后同步清理已不存在的展开行。</p>
     *
     * @param payload 机器表快照
     */
    public void acceptTable(FactoryTableS2C payload) {
        this.hasFactory = payload.hasFactory();
        this.storageOnline = payload.storageOnline();
        this.lineCount = payload.lineCount();
        this.runningLineCount = payload.runningLineCount();
        this.lines = payload.lines();
        this.machines = payload.machines();
        Set<MachineRef> currentRefs = new HashSet<>();
        for (FactorySnapshotBuilder.MachineView machine : this.machines) {
            currentRefs.add(machine.ref());
        }
        this.expandedMachines.retainAll(currentRefs);
        if (this.selectedLineId != null && findLine(this.selectedLineId) == null) {
            this.selectedLineId = null;
        }
        rememberedLineId = this.selectedLineId;
        this.firstRow = Math.max(0, Math.min(this.firstRow, maxFirstRow()));
        if (!this.hasFactory) {
            this.storageRepo.clear();
            this.storageSearchFields.clear();
            this.storageReceived = false;
        }
        rebuildFiltered();
        updateColumnWidths();
        if (this.editController != null) {
            this.editController.onTable(this.lines, this.selectedLineId, this.hasFactory);
        }
    }

    /**
     * 收到服务端仓库快照。
     *
     * <p>快照可能分多个包到达：{@code part == 0} 表示重新开始，{@code last} 到齐才整体替换
     * {@link #storageRepo}，避免界面闪到半份数据。替换后仍保持已有条目的相对顺序，只追加新条目，
     * 防止 Shift 连续取物时格子因数量降序重排而跳动。</p>
     *
     * @param payload 仓库快照分片
     */
    public void acceptStorage(FactoryStorageS2C payload) {
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
     * 收到编辑动作的服务端反馈。
     *
     * <p>界面打开时聊天栏不可见，所以反馈在左表底部临时显示；命名浮层/IO 编辑区后续会
     * 在自己的状态行里显示同一份文案。</p>
     *
     * @param payload 反馈
     */
    public void acceptEditFeedback(FactoryEditFeedbackS2C payload) {
        if (this.editController != null) {
            this.editController.onFeedback(payload);
        }
    }

    /**
     * 重新计算仓库和机器库的可见视图。
     *
     * <p>搜索框同时过滤左右两侧：仓库走 {@link FactoryStorageRepo#configure}，
     * 机器库直接保留能匹配的 {@link FactorySnapshotBuilder.MachineView}。
     * 过滤后还要夹紧两个滚动位置，避免搜索把当前页缩短后越界。</p>
     */
    private void rebuildFiltered() {
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
            List<FactorySnapshotBuilder.MachineView> machines = new ArrayList<>();
            for (FactorySnapshotBuilder.MachineView machine : this.machines) {
                if (this.searchQuery.matches(machineSearchFields(machine))) {
                    machines.add(machine);
                }
            }
            visible = machines;
        }
        this.filteredMachines = buttons == null ? visible
                : FactorySort.machines(visible, buttons.machineKey(), buttons.machineDescending());

        this.filteredStorageEntries = this.storageRepo.view();
        this.storageFirstRow = Math.max(0,
                Math.min(this.storageFirstRow, maxStorageFirstRow()));
        this.machineFirstRow = Math.max(0,
                Math.min(this.machineFirstRow, maxMachineFirstRow()));
    }

    /**
     * 排序键或方向变化后重建视图。
     *
     * <p>排序变化会让原来的滚动位置失去意义，所以两个列表都回到顶部；搜索过滤保持不变。</p>
     */
    private void onSortChanged() {
        this.storageFirstRow = 0;
        this.machineFirstRow = 0;
        rebuildFiltered();
    }

    private FactorySearchQuery.Fields storageSearchFields(ResourceKey key) {
        return this.storageSearchFields.computeIfAbsent(key, k -> {
            String namespace = k.id().getNamespace();
            return new FactorySearchQuery.Fields(resourceName(k), k.id().toString(),
                    namespace, modName(namespace), resourceTooltip(k), resourceTags(k));
        });
    }

    private FactorySearchQuery.Fields machineSearchFields(
            FactorySnapshotBuilder.MachineView machine) {
        String id = machine.blockId() == null ? "" : machine.blockId().toString();
        String namespace = machine.blockId() == null ? "" : machine.blockId().getNamespace();
        String name = FactoryLabels.blockName(machine.blockId());
        if (!machine.lineNames().isEmpty()) {
            name += " " + String.join(" ", machine.lineNames());
        }
        String tooltip = statusText(machine) + "\n"
                + machine.ref().dimension().location() + " "
                + machine.ref().pos().toShortString() + "\n"
                + (machine.lineNames().isEmpty()
                        ? "未编线" : "产线 " + String.join("/", machine.lineNames()))
                + "\n规则 " + machine.ruleCount() + " 条";
        return new FactorySearchQuery.Fields(name, id, namespace, modName(namespace),
                tooltip, List.of());
    }

    private int maxFirstRow() {
        // 最后一行是“新增机器”空槽，下标等于机器数；它可以滚到顶部。
        if (this.selectedLineId == null) {
            return 0;
        }
        return Math.max(0, tableMachines().size());
    }

    /**
     * 当前左表产线下的机器；没有选产线时返回空表，不把不同产线混在一起。
     *
     * @return 当前产线机器
     */
    private List<FactorySnapshotBuilder.MachineView> tableMachines() {
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

    private void updateColumnWidths() {
        if (this.layout == null) {
            return;
        }
        int name = 72;
        int state = 64;
        int input = 72;
        int output = 72;
        for (FactorySnapshotBuilder.MachineView machine : tableMachines()) {
            name = Math.max(name,
                    this.font.width(FactoryLabels.blockName(machine.blockId())) + FactoryLayout.CELL + 12);
            input = Math.max(input, ruleWidth(machine.inputs(), machine.inputRates()));
            output = Math.max(output, ruleWidth(machine.outputs(), machine.outputRates()));
        }
        for (FactorySnapshotBuilder.MachineView machine : this.machines) {
            state = Math.max(state, this.font.width(statusText(machine)) + 17);
        }
        this.layout.relayoutColumns(new int[] { name, state, input, output });
    }

    private int ruleWidth(List<ResourceSpec> rules, List<Long> rates) {
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

    private String lineButtonText() {
        FactorySnapshotBuilder.LineView line = selectedLine();
        return line == null ? "选择产线" : line.name();
    }

    /**
     * 当前选中的产线。
     *
     * @return 产线；未选择或已被删除时为 {@code null}
     */
    @Nullable
    private FactorySnapshotBuilder.LineView selectedLine() {
        return this.selectedLineId == null ? null : findLine(this.selectedLineId);
    }

    /**
     * 当前产线是否处于不执行 IO 的状态。
     *
     * <p>暂停与睡眠时卡片不再显示上一轮的红色诊断，改用中性文案；运行中返回
     * {@code null}，由各卡片按真实原因上色。</p>
     *
     * @return 已暂停 / 睡眠中；运行中或未选线为 {@code null}
     */
    @Nullable
    private String lineIdleLabel() {
        FactorySnapshotBuilder.LineView line = selectedLine();
        if (line == null) {
            return null;
        }
        if (!line.running()) {
            return "已暂停";
        }
        return line.sleeping() ? "睡眠中" : null;
    }

    @Nullable
    private FactorySnapshotBuilder.LineView findLine(UUID id) {
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
        if (java.util.Objects.equals(this.selectedLineId, lineId)) {
            return;
        }
        this.selectedLineId = lineId;
        rememberedLineId = lineId;
        this.firstRow = 0;
        updateColumnWidths();
    }

    /** 当前选中产线 id，给编辑控制器读。 */
    @Nullable
    UUID selectedLineId() {
        return this.selectedLineId;
    }

    /** 服务端最近一次给的产线列表。 */
    List<FactorySnapshotBuilder.LineView> lines() {
        return this.lines;
    }

    /** 服务端最近一次给的机器视图。 */
    List<FactorySnapshotBuilder.MachineView> machines() {
        return this.machines;
    }

    /** 玩家是否已有工厂。 */
    boolean hasFactory() {
        return this.hasFactory;
    }

    private int lineButtonX() {
        return this.layout.headerX + 6 + this.font.width("放置工厂")
                + TITLE_GAP;
    }

    private int lineButtonW() {
        return Math.max(24, Math.min(140, this.font.width(lineButtonText()) + 14));
    }

    private int maxStorageFirstRow() {
        int rows = (this.filteredStorageEntries.size() + FactoryLayout.GRID_COLS - 1)
                / FactoryLayout.GRID_COLS;
        return Math.max(0, rows - this.layout.gridRows);
    }

    private int machineRowsVisible() {
        return Math.max(1, this.layout.gridRows * FactoryLayout.CELL / FactoryLayout.ROW_H);
    }

    private int maxMachineFirstRow() {
        return Math.max(0, this.filteredMachines.size() - machineRowsVisible());
    }

    private static String modName(String namespace) {
        if (namespace == null || namespace.isEmpty()) {
            return "";
        }
        return ModList.get().getModContainerById(namespace)
                .map(container -> container.getModInfo().getDisplayName())
                .orElse(namespace);
    }

    private String resourceTooltip(ResourceKey key) {
        if (key.kind() == ResourceKind.ENERGY) {
            return "FE";
        }
        if (this.minecraft == null) {
            return key.id().toString();
        }
        ItemStack stack = resourceStack(key);
        if (stack.isEmpty()) {
            return key.id().toString();
        }
        StringBuilder text = new StringBuilder();
        for (Component line : stack.getTooltipLines(
                Item.TooltipContext.of(this.minecraft.level), this.minecraft.player,
                TooltipFlag.Default.NORMAL)) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(line.getString());
        }
        return text.toString();
    }

    private static List<String> resourceTags(ResourceKey key) {
        List<String> tags = new ArrayList<>();
        switch (key.kind()) {
            case ITEM -> {
                resourceStack(key).getTags()
                        .forEach(tag -> tags.add(tag.location().toString()));
            }
            case FLUID -> {
                var fluid = BuiltInRegistries.FLUID.get(key.id());
                BuiltInRegistries.FLUID.wrapAsHolder(fluid).tags()
                        .forEach(tag -> tags.add(tag.location().toString()));
            }
            default -> {
                // 能量和化学品没有物品 tag，搜索字段留空即可。
            }
        }
        return tags;
    }

    private static ItemStack resourceStack(ResourceKey key) {
        return switch (key.kind()) {
            case ITEM -> key.toStack();
            case FLUID -> {
                var fluid = BuiltInRegistries.FLUID.get(key.id());
                Item bucket = fluid.getBucket();
                yield bucket == Items.AIR ? new ItemStack(Items.BUCKET) : new ItemStack(bucket);
            }
            case ENERGY -> new ItemStack(Items.REDSTONE);
            default -> ItemStack.EMPTY;
        };
    }

    /**
     * 把当前布局打进日志。
     *
     * <p>尺寸问题（挤压配方查看器、列被裁掉）只有在具体分辨率 + GUI 缩放下才复现，
     * 从截图反推缩放并不可靠。因此把面板、左右区、行数和列宽一次打进日志，
     * 排障时只需要看这一行。</p>
     */
    private void logLayout() {
        FactoryLayout l = this.layout;
        StringBuilder columns = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) {
                columns.append(' ');
            }
            columns.append(l.columnW(i) == 0 ? "-" : String.valueOf(l.columnW(i)));
        }
        double guiScale = this.minecraft == null ? -1.0D : this.minecraft.getWindow().getGuiScale();
        IdleFactory.LOGGER.info(
                "Idle Factory UI: screen={}x{} guiScale={} gutter={} panel={}x{} left={} right={}"
                        + " rows={} gridRows={} columns=[{}]",
                this.width, this.height, guiScale, FactoryLayout.gutter(this.width),
                l.panelW, l.panelH, l.leftW, l.rightW, l.rowsVisible, l.gridRows, columns);
    }

    /**
     * 把 36 个玩家槽位摆到右区底部。
     *
     * <p>1.21.1 的 {@code Slot.x/y} 是 final，不能直接改坐标，所以按新坐标重建
     * Slot 对象；容器和容器索引保持不变，服务端不受影响（服务端不需要这些坐标）。</p>
     *
     * <p>Slot 坐标是相对面板左上角的，而 {@link #leftPos}/{@link #topPos} 是面板在
     * 屏幕上的偏移，因此这里要先减掉偏移。前 9 格是快捷栏，后 27 格是主背包。</p>
     */
    private void positionSlots() {
        FactoryLayout l = this.layout;
        int relInvX = l.invX - this.leftPos;
        int relInvY = l.invY - this.topPos;
        int relHotbarY = l.hotbarY - this.topPos;

        List<Slot> slots = this.menu.slots;
        int count = Math.min(FactoryMenu.PLAYER_SLOTS, slots.size());
        for (int i = 0; i < count; i++) {
            Slot old = slots.get(i);
            int x;
            int y;
            if (i < 9) {
                x = relInvX + i * FactoryLayout.CELL + 1;
                y = relHotbarY + 1;
            } else {
                int index = i - 9;
                x = relInvX + index % FactoryLayout.GRID_COLS * FactoryLayout.CELL + 1;
                y = relInvY + index / FactoryLayout.GRID_COLS * FactoryLayout.CELL + 1;
            }
            Slot slot = new Slot(old.container, old.getContainerSlot(), x, y);
            slot.index = i;
            slots.set(i, slot);
        }
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderMouseX = mouseX;
        this.renderMouseY = mouseY;
        super.render(graphics, mouseX, mouseY, partialTick);
        drawHeader(graphics);
        drawTable(graphics);
        drawRightPanel(graphics, mouseX, mouseY);
        drawTooltips(graphics, mouseX, mouseY);
        if (this.editController != null) {
            this.editController.draw(graphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        FactoryLayout l = this.layout;

        NineSlice.panel(graphics, this.leftPos, this.topPos, this.imageWidth, this.imageHeight);

        // 顶栏下沿只画到右区左边缘，右侧 tab 条自己带走自己的边线。
        NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK,
                l.headerX, l.headerY + FactoryLayout.HEADER_H, l.rightX - l.headerX, 1);

        // 左右两区之间的竖向分隔线；宽度固定 1px，避免不同 GUI 缩放下变粗。
        NineSlice.fill(graphics, GuiTextures.DIVIDER,
                l.rightX - FactoryLayout.GAP / 2, l.bodyY, 1, l.bodyH);

        // 表头整体向内缩 2px，和外层深色/白色双层表框留出层次；
        // 表框本身在 drawTable 的最后绘制，保证白色内圈不会被行卡片盖住。
        NineSlice.header(graphics, l.leftX + 2, l.tableHeaderY + 2,
                l.leftW - 4, FactoryLayout.TABLE_HEADER_H - 2);

        // 表格底部统计行上方的细分隔线。
        NineSlice.fill(graphics, GuiTextures.DIVIDER, l.tableX, l.footerY, l.tableW, 1);

        drawRightChrome(graphics);
    }

    /**
     * 右区的静态底板。
     *
     * <p>只画 tab、搜索框、仓库/机器网格的凹槽、合成占位和箭头；实际的仓库物品、
     * 机器卡片、文字与 tooltip 在 {@link #drawRightPanel} 里画。这样静态底图和
     * 动态数据分开，刷新数据时不会重复铺底图。</p>
     */
    private void drawRightChrome(GuiGraphics graphics) {
        FactoryLayout l = this.layout;

        // tab 条占据顶栏右端的完整宽度；最后一个 tab 吸收除不尽的多余像素。
        int tabWidth = l.rightW / FactoryLayout.TAB_COUNT;
        for (int i = 0; i < FactoryLayout.TAB_COUNT; i++) {
            int x = l.rightX + i * tabWidth;
            int w = i == FactoryLayout.TAB_COUNT - 1 ? l.rightW - i * tabWidth : tabWidth;
            NineSlice.button(graphics, x, l.tabY, w, FactoryLayout.HEADER_H);
        }
        // 选中 tab 用一条 2px 深色上沿强调；宽度跟随当前 tab 的实际宽度。
        int activeWidth = this.activeTab == FactoryLayout.TAB_COUNT - 1
                ? l.rightW - this.activeTab * tabWidth
                : tabWidth;
        NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK,
                l.rightX + this.activeTab * tabWidth, l.tabY, activeWidth, 2);

        drawSearchField(graphics);
        NineSlice.icon(graphics, GuiTextures.SEARCH, l.rightX + 9, l.searchY + 2,
                GuiTextures.SEARCH_SIZE);
        if (this.searchBox != null && !this.searchBox.getValue().isEmpty()) {
            NineSlice.icon(graphics, GuiTextures.SEARCH_CLEAR,
                    clearButtonX(), l.searchY + 4, GuiTextures.SEARCH_CLEAR_SIZE);
        }
        drawSortButtons(graphics);
        if (this.activeTab == TAB_STORAGE) {
            drawGrid(graphics, l.gridX, l.gridY, FactoryLayout.GRID_COLS, l.gridRows,
                    FactoryLayout.CELL);
        }

        // 合成占位目前还是 P0 视觉：两个 tab 都保留，避免切 tab 时布局跳动。
        drawGrid(graphics, l.craftX, l.craftY, FactoryLayout.CRAFT, FactoryLayout.CRAFT,
                FactoryLayout.CRAFT_CELL);

        // 3×3 与结果格之间的箭头：一条细横线和右侧结果凹槽。
        int arrowY = l.resultY + FactoryLayout.CRAFT_RESULT / 2;
        NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK,
                l.craftX + FactoryLayout.CRAFT * FactoryLayout.CRAFT_CELL + 4, arrowY,
                FactoryLayout.CRAFT_ARROW - 8, 1);

        NineSlice.slot(graphics, l.resultX, l.resultY,
                FactoryLayout.CRAFT_RESULT, FactoryLayout.CRAFT_RESULT);
    }

    /**
     * 画左侧表格的 2px 外框。
     *
     * <p>外层深色、内层亮色各 1px，内容在 {@code x + 2} 处开始坐进框里。
     * 这层框必须最后画，否则行卡片会把白色内圈盖成灰色。</p>
     */
    private void drawTableFrame(GuiGraphics graphics, int x, int y, int width, int height) {
        NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK, x, y, width, 1);
        NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK, x, y + height - 1, width, 1);
        NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK, x, y, 1, height);
        NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK, x + width - 1, y, 1, height);

        NineSlice.fill(graphics, GuiTextures.TABLE_LIGHT, x + 1, y + 1, width - 2, 1);
        NineSlice.fill(graphics, GuiTextures.TABLE_LIGHT, x + 1, y + 1, 1, height - 2);
        NineSlice.fill(graphics, GuiTextures.TABLE_LIGHT, x + 1, y + height - 2, width - 2, 1);
        NineSlice.fill(graphics, GuiTextures.TABLE_LIGHT, x + width - 2, y + 1, 1, height - 2);
    }

    /**
     * 铺一片固定大小的凹槽网格。
     *
     * <p>只画背景格子，不画物品；仓库、机器库和合成占位共用这一套底图。
     * 格子间距由布局给出的 {@code cell} 决定，不在绘制阶段做额外取整。</p>
     */
    private void drawGrid(GuiGraphics graphics, int x, int y, int columns, int rows, int cell) {
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                NineSlice.slot(graphics, x + col * cell, y + row * cell, cell, cell);
            }
        }
    }

    /**
     * 顶栏：标题、产线选择、工厂状态、统计、关闭按钮。
     *
     * <p>统计文字只有在状态文字和关闭按钮之间放得下时才画；空间不足时优先保证
     * 标题、产线、状态和关闭按钮，避免文字互相压住。</p>
     */
    private void drawHeader(GuiGraphics graphics) {
        FactoryLayout l = this.layout;
        int x = l.headerX + 6;
        int textY = l.headerY + (FactoryLayout.HEADER_H - 9) / 2;

        String title = "放置工厂";
        graphics.drawString(this.font, FactoryLabels.coloredBold(title, 0xFF000000),
                x, textY, 0xFFFFFFFF, false);
        x += this.font.width(title) + TITLE_GAP;

        String line = lineButtonText();
        int buttonW = lineButtonW();
        NineSlice.button(graphics, x, l.headerY + 3, buttonW, FactoryLayout.TAB_H);
        String lineLabel = this.font.plainSubstrByWidth(line, buttonW - 8);
        graphics.drawString(this.font, lineLabel,
                x + (buttonW - this.font.width(lineLabel)) / 2, textY,
                this.theme.textPrimary(), false);
        x += buttonW + 8;

        String status;
        int statusColor;
        FactorySnapshotBuilder.LineView activeLine = selectedLine();
        if (!this.hasFactory) {
            status = "未创建";
            statusColor = this.theme.statusIdle();
        } else if (activeLine != null) {
            // 顶栏状态描述的是当前产线，不是仓库连接；仓库在线状态由右侧统计显示。
            if (!activeLine.running()) {
                status = "已暂停";
                statusColor = this.theme.error();
            } else if (activeLine.sleeping()) {
                status = "睡眠 " + FactoryLabels.seconds(activeLine.sleepRemainingTicks()) + "s";
                statusColor = this.theme.statusIdle();
            } else {
                status = "运行中";
                statusColor = this.theme.statusRun();
            }
        } else {
            // 顶栏状态描述的是当前产线，不是工厂整体。没有选线时没有可报的
            // 运行状态，直接显示「未选产线」，不借用别的状态。
            status = "未选产线";
            statusColor = this.theme.statusIdle();
        }
        graphics.drawString(this.font, status, x + 4, textY, statusColor, false);
        int statusEnd = x + 4 + this.font.width(status);

        // 顶栏右侧同时要放统计和关闭按钮。窄屏（例如 640 逻辑宽）下，
        // 统计是最不重要的信息，放不下就直接省略，优先保证状态和关闭按钮可读。
        int storageColor = storageStatusColor();
        List<TextPart> stats = List.of(
                new TextPart(Integer.toString(this.machines.size()), this.theme.textValue()),
                new TextPart(" 台", this.theme.textPrimary()),
                new TextPart(" · ", this.theme.textPrimary()),
                new TextPart(Integer.toString(this.lineCount), this.theme.textValue()),
                new TextPart(" 线", this.theme.textPrimary()),
                new TextPart(" · ", this.theme.textPrimary()),
                new TextPart("仓库", storageColor),
                new TextPart(storageStatusText(), storageColor));
        int closeX = l.rightX - 6 - CLOSE_SIZE;
        int statsX = closeX - 8 - partsWidth(stats);
        if (statsX > statusEnd + 8) {
            drawParts(graphics, stats, statsX, textY);
        }

        NineSlice.button(graphics, closeX, l.headerY + 4, CLOSE_SIZE, CLOSE_SIZE);
        graphics.drawString(this.font, "x",
                closeX + (CLOSE_SIZE - this.font.width("x")) / 2, textY,
                this.theme.textPrimary(), false);
    }

    /**
     * 顶栏统计里的仓库状态：同步中、在线、离线。
     *
     * <p>没有工厂时显示未创建；仓库快照还没到时显示同步中，避免刚打开界面就误报离线。</p>
     *
     * @return 状态文字
     */
    private String storageStatusText() {
        if (!this.hasFactory) {
            return "未创建";
        }
        if (!this.storageReceived) {
            return "同步中";
        }
        return this.storageOnline ? "在线" : "离线";
    }

    /**
     * 顶栏仓库状态颜色：同步中/未创建用 idle，在线用 run，离线用 error。
     *
     * @return 主题颜色
     */
    private int storageStatusColor() {
        if (!this.hasFactory || !this.storageReceived) {
            return this.theme.statusIdle();
        }
        return this.storageOnline ? this.theme.statusRun() : this.theme.error();
    }

    /**
     * 画左侧机器表。
     *
     * <p>表头按 {@link FactoryLayout#columnId(int)} 的显示顺序取，不能假设前 N 列
     * 一定可见：窄屏会丢掉输入/输出列，第三列可能是速率。行高由
     * {@link #rowHeight} 动态计算，展开的机器会把后续行整体下推。</p>
     */
    private void drawTable(GuiGraphics graphics) {
        FactoryLayout l = this.layout;
        int headerY = l.tableHeaderY + (FactoryLayout.TABLE_HEADER_H - 9) / 2 + 1;
        String[] headers = { "机器", "状态", "输入", "输出" };
        // 显示顺序 → 逻辑列下标；窄屏时输入/输出可能被丢，不能直接拿 slot 当列号。
        for (int slot = 0; slot < l.columnCount; slot++) {
            int id = l.columnId(slot);
            int color = id == FactoryLayout.COL_INPUT || id == FactoryLayout.COL_OUTPUT
                    ? this.theme.textValue() : this.theme.textPrimary();
            graphics.drawString(this.font, headers[id], l.columnX(id) + 4, headerY,
                    color, false);
        }

        if (this.selectedLineId == null) {
            graphics.drawString(this.font, "先选择一条产线", l.tableX, l.footerY + 3,
                    this.theme.textDim(), false);
            drawTableFrame(graphics);
            return;
        }

        List<FactorySnapshotBuilder.MachineView> table = tableMachines();
        int total = table.size();
        int shown = 0;
        int y = l.rowTop;
        // 最后一行是“新增机器”空槽；selectedLineId 非空时始终存在。
        for (int i = this.firstRow; i <= total; i++) {
            if (y + FactoryLayout.ROW_H > l.footerY) {
                break;
            }
            if (i < total) {
                FactorySnapshotBuilder.MachineView machine = table.get(i);
                int detailHeight = detailHeight(machine, y);
                drawRow(graphics, machine, y, detailHeight);
                y += FactoryLayout.ROW_H + detailHeight;
            } else {
                drawAddMachineRow(graphics, y);
                y += FactoryLayout.ROW_H;
            }
            shown++;
        }

        int ready = 0;
        int unloaded = 0;
        int missing = 0;
        for (FactorySnapshotBuilder.MachineView machine : table) {
            if (machine.machineMissing()) {
                missing++;
            } else {
                switch (machine.health()) {
                    case PRESENT -> ready++;
                    case UNLOADED -> unloaded++;
                    case MISSING, NO_DIMENSION -> missing++;
                    default -> {
                        // UNKNOWN 不计
                    }
                }
            }
        }
        List<TextPart> footer = new ArrayList<>();
        footer.add(new TextPart("共 ", this.theme.textPrimary()));
        footer.add(new TextPart(Integer.toString(total), this.theme.textPrimary()));
        footer.add(new TextPart(" 台", this.theme.textPrimary()));
        footer.add(new TextPart(" · ", this.theme.textPrimary()));
        footer.add(new TextPart("就绪 ", this.theme.statusRun()));
        footer.add(new TextPart(Integer.toString(ready), this.theme.statusRun()));
        footer.add(new TextPart(" · ", this.theme.textPrimary()));
        footer.add(new TextPart("未载 ", this.theme.error()));
        footer.add(new TextPart(Integer.toString(unloaded), this.theme.error()));
        footer.add(new TextPart(" · ", this.theme.textPrimary()));
        footer.add(new TextPart("失联 ", this.theme.error()));
        footer.add(new TextPart(Integer.toString(missing), this.theme.error()));
        if (this.maxFirstRow() > 0) {
            footer.add(new TextPart(" · ", this.theme.textPrimary()));
            footer.add(new TextPart(Integer.toString(this.firstRow + 1), this.theme.textValue()));
            footer.add(new TextPart("-", this.theme.textPrimary()));
            footer.add(new TextPart(Integer.toString(this.firstRow + shown), this.theme.textValue()));
        }
        drawParts(graphics, footer, l.tableX, l.footerY + 3);
        drawTableFrame(graphics);
    }

    /** 用当前布局的位置画整张表的外框；具体线条由 {@link #drawTableFrame(GuiGraphics, int, int, int, int)} 负责。 */
    private void drawTableFrame(GuiGraphics graphics) {
        FactoryLayout l = this.layout;
        int tableBottom = l.footerY + FactoryLayout.FOOTER_H;
        drawTableFrame(graphics, l.leftX, l.tableHeaderY, l.leftW,
                tableBottom - l.tableHeaderY);
    }

    /**
     * 画一台机器的基础行。
     *
     * <p>基础行固定高 {@link FactoryLayout#ROW_H}，包含状态条、机器图标、名称、
     * 产线、状态和输入/输出摘要；行尾按钮决定是否继续画展开区。展开内容不在这
     * 一行里，而是由 {@link IoDetailRender} 接到行底部。</p>
     */
    private void drawRow(GuiGraphics graphics, FactorySnapshotBuilder.MachineView machine,
            int y, int detailHeight) {
        FactoryLayout l = this.layout;
        int textY = y + (FactoryLayout.ROW_H - 9) / 2;
        Status status = statusOf(machine);
        String idleLabel = lineIdleLabel();

        NineSlice.card(graphics, l.leftX + 2, y, l.leftW - 4,
                FactoryLayout.ROW_H - 1);
        // 1px 状态条：从卡片顶部内缘一直贯穿到底部内缘，颜色跟随总状态。
        NineSlice.fill(graphics, status.strip(), l.leftX + 3, y + 1, 1,
                FactoryLayout.ROW_H - 2);

        int iconX = l.columnX(FactoryLayout.COL_NAME) + 5;
        NineSlice.slot(graphics, iconX - 1, y + 4, FactoryLayout.CELL,
                FactoryLayout.CELL);
        ItemStack icon = machine.machineMissing()
                ? new ItemStack(Items.BARRIER)
                : FactoryLabels.blockStack(machine.blockId());
        graphics.renderItem(icon.isEmpty() ? new ItemStack(Items.BARRIER) : icon,
                iconX, y + 5);

        String name = machine.machineMissing() ? "已失联" : FactoryLabels.blockName(machine.blockId());
        int nameWidth = l.columnW(FactoryLayout.COL_NAME) - FactoryLayout.CELL - 10;
        graphics.drawString(this.font,
                this.font.plainSubstrByWidth(name, nameWidth),
                iconX + FactoryLayout.CELL + 2, y + 4,
                this.theme.textPrimary(), false);
        String lineName = machine.lineNames().isEmpty()
                ? "未编线"
                : "产线 " + String.join("/", machine.lineNames());
        graphics.drawString(this.font,
                this.font.plainSubstrByWidth(lineName, nameWidth),
                iconX + FactoryLayout.CELL + 2, y + 14,
                machine.lineNames().isEmpty()
                        ? this.theme.textDim() : this.theme.textValue(), false);

        int stateX = l.columnX(FactoryLayout.COL_STATE) + 4;
        NineSlice.icon(graphics, status.lamp(), stateX, textY + 2,
                GuiTextures.STATUS_LAMP_SIZE);
        String statusLabel = status.text();
        int statusWidth = l.columnW(FactoryLayout.COL_STATE) - 14;
        graphics.drawString(this.font,
                this.font.plainSubstrByWidth(statusLabel, statusWidth),
                stateX + 9, textY, status.color(), false);

        if (l.hasColumn(FactoryLayout.COL_INPUT)) {
            int stripX = l.columnX(FactoryLayout.COL_INPUT) + 2;
            RuleStrip.Layout strip = RuleStrip.layout(this.font, machine.inputs(),
                    machine.inputRates(), stripX, y + 4,
                    l.columnW(FactoryLayout.COL_INPUT) - 4, true);
            RuleStrip.draw(graphics, this.font, this.theme, strip, machine.inputs(),
                    machine.inputRates(), machine.inputReasons(), this.renderMouseX,
                    this.renderMouseY, this.editController != null
                            && this.editController.isDraggingResource(),
                    idleLabel != null);
        }
        if (l.hasColumn(FactoryLayout.COL_OUTPUT)) {
            int stripX = l.columnX(FactoryLayout.COL_OUTPUT) + 2;
            RuleStrip.Layout strip = RuleStrip.layout(this.font, machine.outputs(),
                    machine.outputRates(), stripX, y + 4,
                    l.columnW(FactoryLayout.COL_OUTPUT) - 4
                            - FactoryLayout.EXPAND_BUTTON - 2, true);
            RuleStrip.draw(graphics, this.font, this.theme, strip, machine.outputs(),
                    machine.outputRates(), machine.outputReasons(), this.renderMouseX,
                    this.renderMouseY, this.editController != null
                            && this.editController.isDraggingResource(),
                    idleLabel != null);
        }
        drawExpandButton(graphics, y, isOpen(machine));
        int deleteX = deleteButtonX();
        boolean deleteHovered = this.renderMouseX >= deleteX && this.renderMouseX < deleteX + 12
                && this.renderMouseY >= y + 4 && this.renderMouseY < y + FactoryLayout.ROW_H - 4;
        graphics.drawString(this.font, "×", deleteX, y + (FactoryLayout.ROW_H - 8) / 2,
                deleteHovered ? this.theme.error() : this.theme.textDim(), false);
        if (detailHeight > 0) {
            IoDetailLayout.Detail detail = IoDetailLayout.layout(l, machine,
                    y + FactoryLayout.ROW_H, detailHeight, this.font, this.ioScroll);
            IoDetailRender.draw(graphics, this.font, this.theme, l, machine, detail,
                    this.renderMouseX, this.renderMouseY,
                    this.editController != null && this.editController.isDraggingResource(),
                    idleLabel);
        }
    }

    /**
     * 画“新增机器”行：机器列是一个空槽，整行都是拖拽放置目标。
     *
     * @param graphics 画布
     * @param y        行顶部
     */
    private void drawAddMachineRow(GuiGraphics graphics, int y) {
        FactoryLayout l = this.layout;
        int cardX = l.leftX + 2;
        int cardW = l.leftW - 4;
        NineSlice.card(graphics, cardX, y, cardW, FactoryLayout.ROW_H - 1);

        boolean dragging = this.editController != null
                && this.editController.isDraggingMachine();
        boolean hover = dragging && addMachineRowContains(this.renderMouseX, this.renderMouseY);
        if (hover) {
            NineSlice.fill(graphics, GuiTextures.ROW_ALT, cardX + 1, y + 1,
                    cardW - 2, FactoryLayout.ROW_H - 3);
        }

        int iconX = l.columnX(FactoryLayout.COL_NAME) + 5;
        NineSlice.slot(graphics, iconX - 1, y + 4, FactoryLayout.CELL, FactoryLayout.CELL);
        String plus = "+";
        graphics.drawString(this.font, plus,
                iconX + (FactoryLayout.CELL - this.font.width(plus)) / 2, y + 8,
                this.theme.textDim(), false);

        String title = "新增机器";
        String hint;
        if (dragging) {
            String name = this.editController == null ? "" : this.editController.draggedMachineName();
            hint = hover ? "松手放入 " + name : "放到这里";
        } else {
            hint = "从右侧机器库拖入";
        }
        graphics.drawString(this.font, title, iconX + FactoryLayout.CELL + 2, y + 4,
                this.theme.textPrimary(), false);
        // “新增机器”是一整行卡片，提示不跟机器名列共用宽度，否则会被截成“从右侧机”。
        int hintX = iconX + FactoryLayout.CELL + 2;
        int hintWidth = Math.max(0, cardX + cardW - 4 - hintX);
        graphics.drawString(this.font,
                this.font.plainSubstrByWidth(hint, hintWidth),
                hintX, y + 14,
                hover ? this.theme.statusRun() : this.theme.textDim(), false);
    }

    /**
     * “新增机器”行的顶部 y；放不下时为 {@code null}。
     *
     * <p>绘制、命中、拖放都用这一个函数，避免滚动/展开后坐标漂移。</p>
     *
     * @return 行顶部 y
     */
    @Nullable
    private Integer addMachineRowY() {
        if (this.selectedLineId == null) {
            return null;
        }
        List<FactorySnapshotBuilder.MachineView> table = tableMachines();
        int y = this.layout.rowTop;
        for (int i = this.firstRow; i < table.size(); i++) {
            if (y + FactoryLayout.ROW_H > this.layout.footerY) {
                return null;
            }
            y += FactoryLayout.ROW_H + detailHeight(table.get(i), y);
        }
        return y + FactoryLayout.ROW_H <= this.layout.footerY ? y : null;
    }

    /**
     * 鼠标是否在“新增机器”行上。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 命中为 {@code true}
     */
    boolean addMachineRowContains(double mouseX, double mouseY) {
        Integer y = addMachineRowY();
        return y != null
                && mouseY >= y && mouseY < y + FactoryLayout.ROW_H
                && mouseX >= this.layout.leftX + 2
                && mouseX < this.layout.leftX + this.layout.leftW - 2;
    }

    /**
     * 鼠标下的机器库卡片（机器 tab）。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 机器视图；没命中为 {@code null}
     */
    @Nullable
    FactorySnapshotBuilder.MachineView machineAt(double mouseX, double mouseY) {
        if (this.activeTab != TAB_MACHINES) {
            return null;
        }
        FactoryLayout l = this.layout;
        int gridW = FactoryLayout.GRID_COLS * FactoryLayout.CELL;
        int gridH = l.gridRows * FactoryLayout.CELL;
        if (mouseX < l.gridX || mouseX >= l.gridX + gridW
                || mouseY < l.gridY || mouseY >= l.gridY + gridH) {
            return null;
        }
        int row = (int) ((mouseY - l.gridY) / FactoryLayout.ROW_H);
        int index = this.machineFirstRow + row;
        if (row >= machineRowsVisible() || index < 0 || index >= this.filteredMachines.size()) {
            return null;
        }
        return this.filteredMachines.get(index);
    }

    /**
     * 鼠标下的删除按钮（机器行最右侧的叉号）。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 机器视图；没命中为 {@code null}
     */
    @Nullable
    FactorySnapshotBuilder.MachineView deleteButtonAt(double mouseX, double mouseY) {
        if (this.activeTab != TAB_MACHINES) {
            return null;
        }
        TableHit hit = tableHitAt(mouseY);
        if (hit == null || mouseY >= hit.y() + FactoryLayout.ROW_H) {
            return null;
        }
        int x = deleteButtonX();
        return inside(mouseX, mouseY, x, hit.y() + 2, 12, FactoryLayout.ROW_H - 4)
                ? hit.machine() : null;
    }

    /** 机器行删除叉号的 x；放在展开按钮左侧。 */
    private int deleteButtonX() {
        return expandButtonX() - 16;
    }

    /**
     * 鼠标下的左表机器行（机器列），用作“拖回机器库”的源。
     *
     * <p>不只认 18×18 图标：机器名也算，减少“必须精准按住图标”的摩擦。</p>
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 机器视图；没命中为 {@code null}
     */
    @Nullable
    FactorySnapshotBuilder.MachineView machineIconAt(double mouseX, double mouseY) {
        if (this.selectedLineId == null) {
            return null;
        }
        TableHit hit = tableHitAt(mouseY);
        if (hit == null || mouseY >= hit.y() + FactoryLayout.ROW_H) {
            return null;
        }
        int x = this.layout.columnX(FactoryLayout.COL_NAME) + 2;
        int width = this.layout.columnW(FactoryLayout.COL_NAME) - 4;
        return inside(mouseX, mouseY, x, hit.y() + 2,
                width, FactoryLayout.ROW_H - 4) ? hit.machine() : null;
    }

    /**
     * 鼠标是否落在机器库网格上（移出机器的拖放目标）。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 命中为 {@code true}
     */
    boolean isInMachineLibrary(double mouseX, double mouseY) {
        if (this.activeTab != TAB_MACHINES) {
            return false;
        }
        FactoryLayout l = this.layout;
        return mouseX >= l.gridX
                && mouseX < l.gridX + FactoryLayout.GRID_COLS * FactoryLayout.CELL
                && mouseY >= l.gridY
                && mouseY < l.gridY + l.gridRows * FactoryLayout.CELL;
    }

    /**
     * 一个 IO 空卡的放置目标。
     *
     * @param machine 机器
     * @param side    输入/输出
     */
    record IoDropTarget(FactorySnapshotBuilder.MachineView machine, IoSide side) {
    }

    /**
     * 鼠标下的 IO 空卡/空槽；拖动资源时用它决定放到哪一侧。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 目标；没命中为 {@code null}
     */
    @Nullable
    IoDropTarget ioDropTargetAt(double mouseX, double mouseY) {
        TableHit hit = tableHitAt(mouseY);
        if (hit == null) {
            return null;
        }
        FactorySnapshotBuilder.MachineView machine = hit.machine();
        if (hit.detailHeight() > 0 && mouseY >= hit.y() + FactoryLayout.ROW_H) {
            IoDetailLayout.Card card = detailCardAt(hit, mouseX, mouseY);
            if (card != null && card.empty()) {
                return new IoDropTarget(machine, card.input() ? IoSide.INPUT : IoSide.OUTPUT);
            }
            return null;
        }
        if (mouseY >= hit.y() + FactoryLayout.ROW_H) {
            return null;
        }
        FactoryLayout l = this.layout;
        if (l.hasColumn(FactoryLayout.COL_INPUT)
                && inColumn(l, FactoryLayout.COL_INPUT, mouseX)) {
            RuleStrip.Layout strip = RuleStrip.layout(this.font, machine.inputs(),
                    machine.inputRates(), l.columnX(FactoryLayout.COL_INPUT) + 2,
                    hit.y() + 4, l.columnW(FactoryLayout.COL_INPUT) - 4, true);
            if (RuleStrip.hitAdd(strip, mouseX, mouseY)) {
                return new IoDropTarget(machine, IoSide.INPUT);
            }
        }
        if (l.hasColumn(FactoryLayout.COL_OUTPUT)
                && inColumn(l, FactoryLayout.COL_OUTPUT, mouseX)) {
            RuleStrip.Layout strip = RuleStrip.layout(this.font, machine.outputs(),
                    machine.outputRates(), l.columnX(FactoryLayout.COL_OUTPUT) + 2,
                    hit.y() + 4,
                    l.columnW(FactoryLayout.COL_OUTPUT) - 4
                            - FactoryLayout.EXPAND_BUTTON - 2, true);
            if (RuleStrip.hitAdd(strip, mouseX, mouseY)) {
                return new IoDropTarget(machine, IoSide.OUTPUT);
            }
        }
        return null;
    }

    /**
     * 鼠标是否在仓库网格上；拖规则回仓库删除时用它判断目标。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 命中为 {@code true}
     */
    boolean isInStorageGrid(double mouseX, double mouseY) {
        if (this.activeTab != TAB_STORAGE) {
            return false;
        }
        FactoryLayout l = this.layout;
        return mouseX >= l.gridX
                && mouseX < l.gridX + FactoryLayout.GRID_COLS * FactoryLayout.CELL
                && mouseY >= l.gridY
                && mouseY < l.gridY + l.gridRows * FactoryLayout.CELL;
    }

    /**
     * 滚轮落在展开的 IO 面板上时，滚动卡片而不是表格。
     *
     * @param mouseX  屏幕 x
     * @param mouseY  屏幕 y
     * @param scrollY 滚轮量
     * @return 是否消费
     */
    private boolean ioScrollAt(double mouseX, double mouseY, double scrollY) {
        TableHit hit = tableHitAt(mouseY);
        if (hit == null || hit.detailHeight() <= 0
                || mouseY < hit.y() + FactoryLayout.ROW_H) {
            return false;
        }
        if (mouseX < this.layout.leftX
                || mouseX >= this.layout.leftX + this.layout.leftW) {
            return false;
        }
        IoDetailLayout.Detail detail = IoDetailLayout.layout(this.layout, hit.machine(),
                hit.y() + FactoryLayout.ROW_H, hit.detailHeight(), this.font, this.ioScroll);
        if (detail.maxScroll() <= 0) {
            return true;
        }
        int step = (int) Math.round(scrollY * FactoryLayout.IO_SCROLL_STEP);
        this.ioScroll = Math.max(0, Math.min(detail.maxScroll(), this.ioScroll - step));
        return true;
    }

    /**
     * 点击展开区卡片上的控件；空卡只作为拖放目标，不响应单击。
     *
     * @param machine 机器
     * @param card    卡片
     * @param mouseX  屏幕 x
     * @param mouseY  屏幕 y
     * @param button  鼠标键
     * @return 是否消费
     */
    boolean handleIoCardMouse(FactorySnapshotBuilder.MachineView machine,
            IoDetailLayout.Card card, double mouseX, double mouseY, int button) {
        IoSide side = card.input() ? IoSide.INPUT : IoSide.OUTPUT;
        if (card.empty() || this.editController == null) {
            return true;
        }
        List<ResourceSpec> rules = card.input() ? machine.inputs() : machine.outputs();
        if (card.ruleIndex() >= rules.size()) {
            return true;
        }
        ResourceSpec spec = rules.get(card.ruleIndex());
        IoCardControls.Hit hit = IoCardControls.hit(card.x(), card.y(), card.width(),
                false, spec, mouseX, mouseY);
        switch (hit.kind()) {
            case FACE -> {
                // 左键下一个面、右键上一个面；面按钮本身就是当前面的显示。
                if (button == 0 || button == 1) {
                    Direction next = IoCardControls.nextFace(spec.face(),
                            button == 0 ? 1 : -1);
                    this.editController.updateRule(machine, side, spec, next, spec.mode());
                }
            }
            case MATCH -> {
                if (button == 0) {
                    MatchMode next = spec.mode() == MatchMode.IGNORE_COMPONENTS
                            ? MatchMode.EXACT : MatchMode.IGNORE_COMPONENTS;
                    this.editController.updateRule(machine, side, spec, spec.face(), next);
                }
            }
            case BODY -> {
                if (button == 0) {
                    this.editController.beginRuleDrag(machine, side, spec, mouseX, mouseY);
                }
            }
            default -> {
                // 空卡/未命中：不动作。
            }
        }
        return true;
    }

    /**
     * 点击紧凑输入/输出列的规则图标：开始一次“拖回仓库删除”的拖拽。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @param button 鼠标键
     * @return 是否消费
     */
    boolean handleRuleStripMouse(double mouseX, double mouseY, int button) {
        if (button != 0 || this.editController == null) {
            return false;
        }
        TableHit hit = tableHitAt(mouseY);
        if (hit == null || mouseY >= hit.y() + FactoryLayout.ROW_H) {
            return false;
        }
        FactoryLayout l = this.layout;
        if (l.hasColumn(FactoryLayout.COL_INPUT)
                && inColumn(l, FactoryLayout.COL_INPUT, mouseX)) {
            RuleStrip.Layout strip = RuleStrip.layout(this.font, hit.machine().inputs(),
                    hit.machine().inputRates(), l.columnX(FactoryLayout.COL_INPUT) + 2,
                    hit.y() + 4, l.columnW(FactoryLayout.COL_INPUT) - 4, true);
            RuleStrip.Entry entry = RuleStrip.hitRule(strip, mouseX, mouseY);
            if (entry != null && entry.ruleIndex() < hit.machine().inputs().size()) {
                this.editController.beginRuleDrag(hit.machine(), IoSide.INPUT,
                        hit.machine().inputs().get(entry.ruleIndex()), mouseX, mouseY);
                return true;
            }
            return RuleStrip.hitAdd(strip, mouseX, mouseY);
        }
        if (l.hasColumn(FactoryLayout.COL_OUTPUT)
                && inColumn(l, FactoryLayout.COL_OUTPUT, mouseX)) {
            RuleStrip.Layout strip = RuleStrip.layout(this.font, hit.machine().outputs(),
                    hit.machine().outputRates(), l.columnX(FactoryLayout.COL_OUTPUT) + 2,
                    hit.y() + 4,
                    l.columnW(FactoryLayout.COL_OUTPUT) - 4
                            - FactoryLayout.EXPAND_BUTTON - 2, true);
            RuleStrip.Entry entry = RuleStrip.hitRule(strip, mouseX, mouseY);
            if (entry != null && entry.ruleIndex() < hit.machine().outputs().size()) {
                this.editController.beginRuleDrag(hit.machine(), IoSide.OUTPUT,
                        hit.machine().outputs().get(entry.ruleIndex()), mouseX, mouseY);
                return true;
            }
            return RuleStrip.hitAdd(strip, mouseX, mouseY);
        }
        return false;
    }

    /**
     * 该机器在当前行位置上的展开高度。
     *
     * <p>没有动画，直接按「这一行下面还剩多少空间」夹紧。绘制循环、命中测试和 JEI
     * 都走同一个函数，保证点击不会落到相邻行。</p>
     *
     * @param machine 机器视图
     * @param rowY    该行当前顶部
     * @return 像素高度；未展开为 0
     */
    private int detailHeight(FactorySnapshotBuilder.MachineView machine, int rowY) {
        if (!this.expandedMachines.contains(machine.ref())) {
            return 0;
        }
        int required = IoDetailLayout.requiredHeight(this.layout, machine, this.font);
        int available = Math.max(0, this.layout.footerY - rowY - FactoryLayout.ROW_H);
        return Math.min(required, available);
    }

    /** 该机器是否处于展开状态。 */
    private boolean isOpen(FactorySnapshotBuilder.MachineView machine) {
        return this.expandedMachines.contains(machine.ref());
    }

    /** 行尾展开按钮的 x。 */
    private int expandButtonX() {
        FactoryLayout l = this.layout;
        return l.leftX + l.leftW - FactoryLayout.EXPAND_BUTTON - 4;
    }

    /**
     * 行尾展开按钮。
     *
     * <p>14×14 九宫格按钮 + 7×7 箭头贴图；悬停换浅蓝底。收起时箭头向右，展开时向下。
     * 点击命中在 {@link #clickMachineExpand} 里处理。</p>
     */
    private void drawExpandButton(GuiGraphics graphics, int y, boolean open) {
        int x = expandButtonX();
        int buttonY = y + 6;
        boolean hovered = inside(this.renderMouseX, this.renderMouseY, x, buttonY,
                FactoryLayout.EXPAND_BUTTON, FactoryLayout.EXPAND_BUTTON);
        if (hovered) {
            NineSlice.buttonHover(graphics, x, buttonY,
                    FactoryLayout.EXPAND_BUTTON, FactoryLayout.EXPAND_BUTTON);
        } else {
            NineSlice.button(graphics, x, buttonY,
                    FactoryLayout.EXPAND_BUTTON, FactoryLayout.EXPAND_BUTTON);
        }
        int offset = (FactoryLayout.EXPAND_BUTTON - GuiTextures.CHEVRON_SIZE) / 2;
        NineSlice.icon(graphics,
                open ? GuiTextures.CHEVRON_DOWN : GuiTextures.CHEVRON_RIGHT,
                x + offset, buttonY + offset, GuiTextures.CHEVRON_SIZE);
    }

    /** 左表中一次命中：机器、行顶部 y、该行当前实际高度和展开区高度。 */
    private record TableHit(FactorySnapshotBuilder.MachineView machine, int y, int height,
            int detailHeight) {
    }

    /**
     * 按鼠标 y 找到它落在哪个可见机器行里。
     *
     * <p>行高可变，不能再用 {@code (mouseY - rowTop) / ROW_H}。这里从
     * {@link #firstRow} 开始逐行累加，和绘制循环使用同一个 {@link #detailHeight}。</p>
     *
     * @param mouseY 屏幕 y
     * @return 命中行；空白区或超出表格时为 {@code null}
     */
    @Nullable
    private TableHit tableHitAt(double mouseY) {
        FactoryLayout l = this.layout;
        if (mouseY < l.rowTop || mouseY >= l.footerY) {
            return null;
        }
        List<FactorySnapshotBuilder.MachineView> table = tableMachines();
        int y = l.rowTop;
        for (int i = this.firstRow; i < table.size(); i++) {
            if (y + FactoryLayout.ROW_H > l.footerY) {
                return null;
            }
            FactorySnapshotBuilder.MachineView machine = table.get(i);
            int detailHeight = detailHeight(machine, y);
            int height = FactoryLayout.ROW_H + detailHeight;
            if (mouseY >= y && mouseY < y + height) {
                return new TableHit(machine, y, height, detailHeight);
            }
            y += height;
        }
        return null;
    }

    /**
     * 点击行尾按钮时切换展开状态。
     *
     * <p>只认按钮区域，不把整行点击都当成展开。没有动画：展开/收起一步到位，
     * 同一时间只允许一台机器展开。</p>
     */
    private boolean clickMachineExpand(double mouseX, double mouseY) {
        int buttonX = expandButtonX();
        if (mouseX < buttonX || mouseX >= buttonX + FactoryLayout.EXPAND_BUTTON) {
            return false;
        }
        TableHit hit = tableHitAt(mouseY);
        if (hit == null
                || mouseY < hit.y() + 6
                || mouseY >= hit.y() + 6 + FactoryLayout.EXPAND_BUTTON) {
            return false;
        }
        MachineRef ref = hit.machine().ref();
        if (this.expandedMachines.contains(ref)) {
            this.expandedMachines.remove(ref);
        } else {
            this.expandedMachines.clear();
            this.expandedMachines.add(ref);
            ensureExpansionVisible(ref);
        }
        this.ioScroll = 0;
        this.firstRow = Math.max(0, Math.min(this.firstRow, maxFirstRow()));
        return true;
    }

    /**
     * 展开行需要的高度超过当前剩余空间时，把它滚到表格顶部。
     *
     * <p>没有动画，展开后直接是最终高度；只调 {@code firstRow}，不改数据。</p>
     */
    private void ensureExpansionVisible(MachineRef ref) {
        List<FactorySnapshotBuilder.MachineView> table = tableMachines();
        int index = -1;
        for (int i = 0; i < table.size(); i++) {
            if (table.get(i).ref().equals(ref)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return;
        }
        if (index < this.firstRow) {
            this.firstRow = index;
            return;
        }
        int y = this.layout.rowTop;
        for (int i = this.firstRow; i < index; i++) {
            y += FactoryLayout.ROW_H + detailHeight(table.get(i), y);
            if (y >= this.layout.footerY) {
                this.firstRow = index;
                return;
            }
        }
        int required = IoDetailLayout.requiredHeight(this.layout, table.get(index), this.font);
        int available = Math.max(0, this.layout.footerY - y - FactoryLayout.ROW_H);
        if (required > available) {
            this.firstRow = index;
        }
    }

    private record Status(String text, int color, ResourceLocation strip,
            ResourceLocation lamp) {
    }

    /** 一行多色文字里的一个片段；用于统计、底部汇总这类「标签暗、数字亮」的地方。 */
    private record TextPart(String text, int color) {
    }

    /** 一组多色片段的总宽度。 */
    private int partsWidth(List<TextPart> parts) {
        int width = 0;
        for (TextPart part : parts) {
            width += this.font.width(part.text());
        }
        return width;
    }

    /** 从左到右画一组多色片段。 */
    private int drawParts(GuiGraphics graphics, List<TextPart> parts, int x, int y) {
        int cursor = x;
        for (TextPart part : parts) {
            graphics.drawString(this.font, part.text(), cursor, y, part.color(), false);
            cursor += this.font.width(part.text());
        }
        return cursor;
    }

    private Status statusOf(FactorySnapshotBuilder.MachineView machine) {
        return switch (machine.totalState()) {
            case OFFLINE -> offlineStatus(machine);
            case UNCONFIGURED -> new Status(machine.lineNames().isEmpty() ? "未编线" : "无规则",
                    this.theme.statusIdle(), GuiTextures.STATUS_STRIP_IDLE,
                    GuiTextures.STATUS_LAMP_IDLE);
            case RUNNING -> new Status("运行", this.theme.statusRun(),
                    GuiTextures.STATUS_STRIP_RUN, GuiTextures.STATUS_LAMP_RUN);
            case IDLE -> new Status("空闲", this.theme.statusIdle(),
                    GuiTextures.STATUS_STRIP_IDLE, GuiTextures.STATUS_LAMP_IDLE);
            case WAITING_MATERIAL -> new Status("仓库缺料", this.theme.statusWarn(),
                    GuiTextures.STATUS_STRIP_WARN, GuiTextures.STATUS_LAMP_WARN);
            case INPUT_OK -> new Status("可输入", this.theme.statusRun(),
                    GuiTextures.STATUS_STRIP_RUN, GuiTextures.STATUS_LAMP_RUN);
            case INPUT_BLOCKED -> new Status("输入堵塞",
                    this.theme.statusWarn(), GuiTextures.STATUS_STRIP_WARN,
                    GuiTextures.STATUS_LAMP_WARN);
            default -> new Status("未知", this.theme.statusIdle(),
                    GuiTextures.STATUS_STRIP_IDLE, GuiTextures.STATUS_LAMP_IDLE);
        };
    }

    private Status offlineStatus(FactorySnapshotBuilder.MachineView machine) {
        if (machine.machineMissing()) {
            return new Status("失联", this.theme.error(),
                    GuiTextures.STATUS_STRIP_ERROR, GuiTextures.STATUS_LAMP_ERROR);
        }
        return switch (machine.health()) {
            case MISSING, NO_DIMENSION -> new Status("失联", this.theme.error(),
                    GuiTextures.STATUS_STRIP_ERROR, GuiTextures.STATUS_LAMP_ERROR);
            case UNLOADED -> new Status("未载", this.theme.statusWarn(),
                    GuiTextures.STATUS_STRIP_WARN, GuiTextures.STATUS_LAMP_WARN);
            default -> new Status("未探测", this.theme.statusWarn(),
                    GuiTextures.STATUS_STRIP_WARN, GuiTextures.STATUS_LAMP_WARN);
        };
    }

    private String statusText(FactorySnapshotBuilder.MachineView machine) {
        return statusOf(machine).text();
    }

    private int statusColor(FactorySnapshotBuilder.MachineView machine) {
        return statusOf(machine).color();
    }

    private void drawResourceIcon(GuiGraphics graphics, ResourceKey key, int x, int y) {
        ResourceIconRenderer.draw(graphics, this.font, key, x, y);
    }

    /** 资源对应的界面图标；JEI 查询也复用这一份。*/
    private static ItemStack iconStack(ResourceKey key) {
        return FactoryLabels.icon(key);
    }

    private void drawCount(GuiGraphics graphics, long count, ResourceKind kind,
            int right, int bottom) {
        String text = FactoryLabels.gridCount(count, kind);
        float scale = 0.666F;
        float textWidth = this.font.width(text);
        // 缩写后仍然偏长时再缩一点，尽量不要压到左边格子；最小 0.5 保证可读。
        if (textWidth * scale > FactoryLayout.CELL - 2) {
            scale = Math.max(0.5F, (FactoryLayout.CELL - 2) / textWidth);
        }
        float width = textWidth * scale;
        float height = this.font.lineHeight * scale;
        graphics.pose().pushPose();
        graphics.pose().translate(right - width - 1.0F, bottom - height,
                GuiLayer.GRID_COUNT.z());
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(this.font, text, 0, 0, 0xFFFFFFFF, true);
        graphics.pose().popPose();
    }

    private static String resourceName(ResourceKey key) {
        return FactoryLabels.name(key);
    }

    private static String faceName(ResourceSpec spec) {
        return FactoryLabels.face(spec);
    }

    /**
     * 右区的动态内容：tab 文字、仓库格/机器卡片、底部标签。
     *
     * <p>仓位格子、机器卡片和 tooltip 都在这里画，静态凹槽底板由
     * {@link #drawRightChrome} 先铺好。仓库 tab 先画悬停底图再画物品，保证悬停色
     * 不会盖住图标和数量。</p>
     */
    private void drawRightPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        FactoryLayout l = this.layout;
        String[] tabs = { "仓库", "机器" };
        int tabWidth = l.rightW / FactoryLayout.TAB_COUNT;
        for (int i = 0; i < tabs.length; i++) {
            int x = l.rightX + i * tabWidth;
            graphics.drawString(this.font, tabs[i],
                    x + (tabWidth - this.font.width(tabs[i])) / 2,
                    l.tabY + (FactoryLayout.HEADER_H - 9) / 2,
                    this.theme.textPrimary(),
                    false);
        }

        if (this.activeTab == TAB_STORAGE) {
            drawStorageHover(graphics, mouseX, mouseY);
            drawStorageEntries(graphics);
            // 描边放在图标之后：流体贴图是不透明的，画在下面会被完全盖住。
            drawStorageHoverOutline(graphics, mouseX, mouseY);
        } else {
            drawMachineLibrary(graphics);
        }

        graphics.drawString(this.font, "合成", l.craftX, l.craftLabelY,
                this.theme.textPrimary(), false);
        graphics.drawString(this.font, "背包", l.invX, l.invLabelY,
                this.theme.textPrimary(), false);
    }

    /**
     * 仓库格的悬停底图。
     *
     * <p>只覆盖鼠标所在的那一格，包括空格；底图本身带普通槽位的上/左暗边，
     * 因此不会出现白色边框压过相邻格子的视觉断层。</p>
     */
    private void drawStorageHover(GuiGraphics graphics, int mouseX, int mouseY) {
        FactoryLayout l = this.layout;
        if (mouseX < l.gridX
                || mouseX >= l.gridX + FactoryLayout.GRID_COLS * FactoryLayout.CELL
                || mouseY < l.gridY
                || mouseY >= l.gridY + l.gridRows * FactoryLayout.CELL) {
            return;
        }
        int col = (mouseX - l.gridX) / FactoryLayout.CELL;
        int row = (mouseY - l.gridY) / FactoryLayout.CELL;
        NineSlice.icon(graphics, GuiTextures.SLOT_HOVER,
                l.gridX + col * FactoryLayout.CELL,
                l.gridY + row * FactoryLayout.CELL,
                GuiTextures.SLOT_HOVER_SIZE);
    }

    /**
     * 悬停格的 1px 描边。
     *
     * <p>仿 AE2 的 {@code renderSlotHighlight}：只画四周，不盖图标。这样不透明的流体
     * 贴图也不会把高亮完全遮住。</p>
     */
    private void drawStorageHoverOutline(GuiGraphics graphics, int mouseX, int mouseY) {
        FactoryLayout l = this.layout;
        if (mouseX < l.gridX
                || mouseX >= l.gridX + FactoryLayout.GRID_COLS * FactoryLayout.CELL
                || mouseY < l.gridY
                || mouseY >= l.gridY + l.gridRows * FactoryLayout.CELL) {
            return;
        }
        int col = (mouseX - l.gridX) / FactoryLayout.CELL;
        int row = (mouseY - l.gridY) / FactoryLayout.CELL;
        int x = l.gridX + col * FactoryLayout.CELL;
        int y = l.gridY + row * FactoryLayout.CELL;
        int right = x + FactoryLayout.CELL;
        int bottom = y + FactoryLayout.CELL;
        int color = this.theme.hoverOutline();
        graphics.fill(x, y, right, y + 1, color);
        graphics.fill(x, bottom - 1, right, bottom, color);
        graphics.fill(x, y, x + 1, bottom, color);
        graphics.fill(right - 1, y, right, bottom, color);
    }

    /**
     * 画当前可见的仓库格。
     *
     * <p>可见条目来自 {@link FactoryStorageRepo#view()}，滚动由
     * {@code storageFirstRow} 控制；同步中/离线由顶栏统计显示，空仓库/无匹配也不在
     * 槽位区域写文字。每个格子只画图标和数量，点击与手势由 {@code mouseClicked}
     * 统一处理。</p>
     */
    private void drawStorageEntries(GuiGraphics graphics) {
        FactoryLayout l = this.layout;
        // 同步中/离线由顶栏统计显示；仓库为空或搜索无匹配时也不在槽位区域放文字。
        if (!this.storageReceived || !this.storageOnline) {
            return;
        }
        if (this.storageRepo.all().isEmpty() || this.filteredStorageEntries.isEmpty()) {
            return;
        }

        for (int row = 0; row < l.gridRows; row++) {
            for (int col = 0; col < FactoryLayout.GRID_COLS; col++) {
                int index = (this.storageFirstRow + row) * FactoryLayout.GRID_COLS + col;
                if (index >= this.filteredStorageEntries.size()) {
                    return;
                }
                FactoryStorageS2C.Entry entry = this.filteredStorageEntries.get(index);
                int x = l.gridX + col * FactoryLayout.CELL;
                int y = l.gridY + row * FactoryLayout.CELL;
                drawStorageEntry(graphics, entry, x, y);
            }
        }
    }

    /** 画一个仓库格的图标和右下角数量。 */
    private void drawStorageEntry(GuiGraphics graphics, FactoryStorageS2C.Entry entry,
            int x, int y) {
        drawResourceIcon(graphics, entry.key(), x + 1, y + 1);
        drawCount(graphics, entry.count(), entry.key().kind(), x + FactoryLayout.CELL - 1,
                y + FactoryLayout.CELL - 1);
    }

    /**
     * 画当前鼠标下的提示。
     *
     * <p>顺序上先处理搜索框语法提示，再处理仓库格和机器库卡片，最后处理左表的
     * 机器/输入/输出列。产线下拉展开时跳过 tooltip，避免菜单和提示叠在一起。</p>
     */
    private void drawTooltips(GuiGraphics graphics, int mouseX, int mouseY) {
        if (this.editController != null && this.editController.isOverlayActive()) {
            return;
        }
        FactoryLayout l = this.layout;
        if (this.searchBox != null && !this.searchBox.isFocused()
                && mouseX >= l.rightX + 4
                && mouseX < l.rightX + 4 + l.searchWidth()
                && mouseY >= l.searchY && mouseY < l.searchY + FactoryLayout.SEARCH_H) {
            graphics.renderComponentTooltip(this.font, List.of(
                    searchHint("@", "按模组"),
                    searchHint("#", "按标签"),
                    searchHint("$", "按 tooltip"),
                    searchHint("*", "按注册名"),
                    searchHint("|", "或；空格 与")), mouseX, mouseY);
            return;
        }

        int sortButton = isSortButtonAt(mouseX, mouseY);
        if (sortButton >= 0 && this.sortButtons != null) {
            List<Component> lines = this.sortButtons
                    .tooltipLines(sortButton, this.activeTab == TAB_STORAGE)
                    .stream().map(line -> (Component) Component.literal(line)).toList();
            if (!lines.isEmpty()) {
                graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
            }
            return;
        }

        // 背包/快捷栏直接用原版物品 tooltip，完整继承模组追加的文本和组件。
        for (Slot slot : this.menu.slots) {
            if (slot.hasItem() && isHovering(slot.x, slot.y, 16, 16, mouseX, mouseY)) {
                graphics.renderTooltip(this.font, slot.getItem(), mouseX, mouseY);
                return;
            }
        }

        if (this.activeTab == TAB_STORAGE
                && mouseX >= l.gridX && mouseX < l.gridX + FactoryLayout.GRID_COLS * FactoryLayout.CELL
                && mouseY >= l.gridY && mouseY < l.gridY + l.gridRows * FactoryLayout.CELL) {
            int col = (mouseX - l.gridX) / FactoryLayout.CELL;
            int row = (mouseY - l.gridY) / FactoryLayout.CELL;
            int index = (this.storageFirstRow + row) * FactoryLayout.GRID_COLS + col;
            if (index >= 0 && index < this.filteredStorageEntries.size()) {
                FactoryStorageS2C.Entry entry = this.filteredStorageEntries.get(index);
                // 仓库格同样不做自定义摘要：物品走原版 tooltip，流体/能量只保留名称。
                if (entry.key().kind() == ResourceKind.ITEM) {
                    graphics.renderTooltip(this.font, iconStack(entry.key()), mouseX, mouseY);
                } else {
                    graphics.renderComponentTooltip(this.font, List.of(
                            FactoryLabels.colored(resourceName(entry.key()),
                                    this.theme.tooltip().title())), mouseX, mouseY);
                }
                return;
            }
        }

        if (this.activeTab == TAB_MACHINES
                && mouseX >= l.gridX && mouseX < l.gridX + FactoryLayout.GRID_COLS * FactoryLayout.CELL
                && mouseY >= l.gridY && mouseY < l.gridY + l.gridRows * FactoryLayout.CELL) {
            int row = (mouseY - l.gridY) / FactoryLayout.ROW_H;
            int index = this.machineFirstRow + row;
            if (row < machineRowsVisible() && index >= 0 && index < this.filteredMachines.size()) {
                FactorySnapshotBuilder.MachineView machine = this.filteredMachines.get(index);
                drawMachineTooltip(graphics, machine, mouseX, mouseY);
                return;
            }
        }

        if (addMachineRowContains(mouseX, mouseY)) {
            graphics.renderComponentTooltip(this.font, List.of(
                    FactoryLabels.colored("新增机器", this.theme.tooltip().title()),
                    FactoryLabels.colored("在右侧机器库按住左键拖一台机器到这里",
                            this.theme.tooltip().label())), mouseX, mouseY);
            return;
        }

        if (mouseX >= l.leftX && mouseX < l.rightX - FactoryLayout.GAP
                && mouseY >= l.rowTop && mouseY < l.footerY) {
            TableHit hit = tableHitAt(mouseY);
            if (hit == null) {
                return;
            }
            FactorySnapshotBuilder.MachineView machine = hit.machine();
            // 展开按钮压在输出列右端，必须先判按钮，否则会弹出输出列规则提示。
            if (inExpandButton(hit, mouseX, mouseY)) {
                drawExpandTooltip(graphics, machine, mouseX, mouseY);
                return;
            }
            IoDetailLayout.Card card = detailCardAt(hit, mouseX, mouseY);
            if (card != null) {
                drawCardTooltip(graphics, machine, card, mouseX, mouseY);
                return;
            }
            // 列规则提示只对基础行有效；展开区不再回落成列提示。
            if (mouseY >= hit.y() + FactoryLayout.ROW_H) {
                return;
            }
            if (l.hasColumn(FactoryLayout.COL_INPUT)
                    && inColumn(l, FactoryLayout.COL_INPUT, mouseX)) {
                drawRuleTooltip(graphics, "输入", machine.inputs(),
                        machine.inputRates(), machine.inputReasons(), mouseX, mouseY,
                        lineIdleLabel());
                return;
            }
            if (l.hasColumn(FactoryLayout.COL_OUTPUT)
                    && inColumn(l, FactoryLayout.COL_OUTPUT, mouseX)) {
                drawRuleTooltip(graphics, "输出", machine.outputs(),
                        machine.outputRates(), machine.outputReasons(), mouseX, mouseY,
                        lineIdleLabel());
                return;
            }
        }
    }

    /** 搜索语法提示：符号强调色，说明暗色。 */
    private Component searchHint(String key, String description) {
        GuiTheme.Tooltip tt = this.theme.tooltip();
        return Component.empty()
                .append(FactoryLabels.coloredBold(key, tt.value()))
                .append(FactoryLabels.colored(" " + description, tt.label()));
    }

    /** 鼠标是否落在某行的展开按钮上。 */
    private boolean inExpandButton(TableHit hit, double mouseX, double mouseY) {
        return inside(mouseX, mouseY, expandButtonX(), hit.y() + 6,
                FactoryLayout.EXPAND_BUTTON, FactoryLayout.EXPAND_BUTTON);
    }

    /**
     * 鼠标命中的展开区卡片。
     *
     * @return 卡片；没展开或没命中为 {@code null}
     */
    @Nullable
    private IoDetailLayout.Card detailCardAt(TableHit hit, double mouseX, double mouseY) {
        if (hit.detailHeight() <= 0) {
            return null;
        }
        IoDetailLayout.Detail detail = IoDetailLayout.layout(this.layout, hit.machine(),
                hit.y() + FactoryLayout.ROW_H, hit.detailHeight(), this.font, this.ioScroll);
        return IoDetailLayout.cardAt(detail, mouseX, mouseY);
    }

    /** 展开按钮的提示；和输出列规则提示区分开。 */
    private void drawExpandTooltip(GuiGraphics graphics,
            FactorySnapshotBuilder.MachineView machine, int mouseX, int mouseY) {
        List<Component> lines = new ArrayList<>();
        GuiTheme.Tooltip tt = this.theme.tooltip();
        lines.add(FactoryLabels.colored(
                isOpen(machine) ? "收起 IO 详情" : "展开 IO 详情", tt.title()));
        lines.add(Component.empty()
                .append(FactoryLabels.colored("输入 ", tt.label()))
                .append(FactoryLabels.coloredBold(
                        Integer.toString(machine.inputs().size()), tt.value()))
                .append(FactoryLabels.colored(" · 输出 ", tt.label()))
                .append(FactoryLabels.coloredBold(
                        Integer.toString(machine.outputs().size()), tt.value()))
                .append(FactoryLabels.colored(" 条", tt.label())));
        graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
    }

    /** 展开区卡片的完整信息：规则内容、面、匹配和控件说明。 */
    private void drawCardTooltip(GuiGraphics graphics,
            FactorySnapshotBuilder.MachineView machine, IoDetailLayout.Card card,
            int mouseX, int mouseY) {
        List<ResourceSpec> rules = card.input() ? machine.inputs() : machine.outputs();
        GuiTheme.Tooltip tt = this.theme.tooltip();
        List<Component> lines = new ArrayList<>();
        if (card.empty()) {
            lines.add(FactoryLabels.colored(card.input() ? "输入空卡" : "输出空卡", tt.title()));
            lines.add(FactoryLabels.colored("从仓库拖一个资源到这里新增规则", tt.label()));
            graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
            return;
        }
        if (card.ruleIndex() >= rules.size()) {
            return;
        }
        ResourceSpec spec = rules.get(card.ruleIndex());
        String idleLabel = lineIdleLabel();
        IoCardControls.Hit hit = IoCardControls.hit(card.x(), card.y(), card.width(),
                false, spec, mouseX, mouseY);
        switch (hit.kind()) {
            case FACE -> {
                lines.add(FactoryLabels.colored(
                        card.input() ? "输入面" : "输出面", tt.title()));
                lines.add(FactoryLabels.faceLine(
                        "当前：", faceName(spec), FactoryLabels.faceEn(spec), this.theme));
                lines.add(FactoryLabels.colored("左键下一个面，右键上一个面", tt.label()));
                graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
                return;
            }
            case MATCH -> {
                lines.add(FactoryLabels.colored("匹配模式", tt.title()));
                lines.add(FactoryLabels.colored(spec.fuzzy()
                        ? "模糊（忽略组件），点击改精确"
                        : "精确（含组件），点击改模糊", tt.label()));
                graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
                return;
            }
            default -> {
                // 卡片空白处：回落到规则详情。
            }
        }

        long rate = machine.ruleRate(card.input(), card.ruleIndex());
        List<IoResult.Reason> reasons = card.input()
                ? machine.inputReasons() : machine.outputReasons();
        IoResult.Reason reason = card.ruleIndex() < reasons.size()
                ? reasons.get(card.ruleIndex()) : IoResult.Reason.NO_ITEM;
        IoResult.Reason effective = idleLabel != null ? IoResult.Reason.NO_ITEM
                : FactoryLabels.effectiveReason(rate, reason);

        lines.add(Component.empty()
                .append(FactoryLabels.colored(card.input() ? "输入" : "输出", tt.title()))
                .append(FactoryLabels.colored(" #", tt.label()))
                .append(FactoryLabels.colored(Integer.toString(card.ruleIndex() + 1),
                        tt.index()))
                .append(FactoryLabels.colored(" · ", tt.label()))
                .append(FactoryLabels.colored(FactoryLabels.name(spec.key()), tt.title())));
        lines.add(FactoryLabels.faceLine(
                card.input() ? "输入面：" : "输出面：",
                faceName(spec), FactoryLabels.faceEn(spec), this.theme));
        if (spec.fuzzy()) {
            lines.add(FactoryLabels.coloredBold("忽略组件", 0xFFFF55));
        }
        lines.add(Component.empty()
                .append(FactoryLabels.colored("速率：", tt.label()))
                .append(FactoryLabels.rate(rate, spec.key().kind(), effective, this.theme)));
        if (idleLabel != null) {
            lines.add(Component.empty()
                    .append(FactoryLabels.colored("状态：", tt.label()))
                    .append(FactoryLabels.coloredBold(idleLabel, tt.idle())));
        } else {
            lines.add(FactoryLabels.state(effective, card.input(), this.theme));
        }
        lines.add(FactoryLabels.colored("拖回仓库删除", tt.label()));
        graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
    }

    /** 鼠标是否落在某个逻辑列的横向范围内。 */
    private boolean inColumn(FactoryLayout layout, int column, double mouseX) {
        return mouseX >= layout.columnX(column)
                && mouseX < layout.columnX(column) + layout.columnW(column);
    }

    /**
     * 输入/输出列的多规则 tooltip。
     *
     * <p>最多列 10 条，每条显示资源名、面、速率；超出部分只显示剩余数量，
     * 防止 tooltip 高度超出屏幕。</p>
     *
     * @param idleLabel 产线暂停/睡眠时的中性文案；运行中传 {@code null}
     */
    private void drawRuleTooltip(GuiGraphics graphics, String title,
            List<ResourceSpec> rules, List<Long> rates, List<IoResult.Reason> reasons,
            int mouseX, int mouseY, @Nullable String idleLabel) {
        if (rules.isEmpty()) {
            return;
        }
        List<Component> lines = new java.util.ArrayList<>();
        GuiTheme.Tooltip tt = this.theme.tooltip();
        lines.add(FactoryLabels.colored(title, tt.title()));
        int shown = Math.min(rules.size(), 10);
        for (int i = 0; i < shown; i++) {
            ResourceSpec spec = rules.get(i);
            long rate = i < rates.size() ? rates.get(i) : 0L;
            IoResult.Reason reason = i < reasons.size()
                    ? reasons.get(i) : IoResult.Reason.NO_ITEM;
            IoResult.Reason effective = idleLabel != null ? IoResult.Reason.NO_ITEM
                    : FactoryLabels.effectiveReason(rate, reason);
            lines.add(Component.empty()
                    .append(FactoryLabels.colored(FactoryLabels.name(spec.key()), tt.title()))
                    .append(FactoryLabels.colored(" · ", tt.label()))
                    .append(FactoryLabels.coloredBold(faceName(spec), tt.face()))
                    .append(FactoryLabels.colored(" · ", tt.label()))
                    .append(FactoryLabels.rate(rate, spec.key().kind(), effective, this.theme)));
        }
        if (rules.size() > shown) {
            lines.add(Component.empty()
                    .append(FactoryLabels.colored("还有 ", tt.label()))
                    .append(FactoryLabels.coloredBold(
                            Integer.toString(rules.size() - shown), tt.value()))
                    .append(FactoryLabels.colored(" 条", tt.label())));
        }
        if (idleLabel != null) {
            lines.add(FactoryLabels.coloredBold(idleLabel, tt.idle()));
        }
        graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
    }

    /** 机器库卡片的 tooltip：名称、总状态、坐标、产线和规则数。 */
    private void drawMachineTooltip(GuiGraphics graphics,
            FactorySnapshotBuilder.MachineView machine, int mouseX, int mouseY) {
        List<Component> lines = new java.util.ArrayList<>();
        GuiTheme.Tooltip tt = this.theme.tooltip();
        lines.add(FactoryLabels.colored(FactoryLabels.blockName(machine.blockId()), tt.title()));
        lines.add(FactoryLabels.coloredBold(statusText(machine), statusColor(machine)));
        lines.add(FactoryLabels.colored(machine.ref().dimension().location()
                + " " + machine.ref().pos().toShortString(), tt.label()));
        lines.add(machine.lineNames().isEmpty()
                ? FactoryLabels.colored("未编线", tt.idle())
                : FactoryLabels.labelValue("产线 ",
                        String.join("/", machine.lineNames()), this.theme));
        lines.add(Component.empty()
                .append(FactoryLabels.colored("规则 ", tt.label()))
                .append(FactoryLabels.coloredBold(
                        Integer.toString(machine.ruleCount()), tt.value()))
                .append(FactoryLabels.colored(" 条", tt.label())));
        graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
    }

    /**
     * 机器库：按行画深色卡片，每行一台机器。
     *
     * <p>滚动位置由 {@code machineFirstRow} 控制，行高固定为
     * {@link FactoryLayout#ROW_H}；卡片内部再按名称/产线/状态/规则四块排文字。</p>
     */
    private void drawMachineLibrary(GuiGraphics graphics) {
        FactoryLayout l = this.layout;
        int width = FactoryLayout.GRID_COLS * FactoryLayout.CELL;
        int height = l.gridRows * FactoryLayout.CELL;
        int rows = machineRowsVisible();
        for (int i = 0; i < rows; i++) {
            int index = this.machineFirstRow + i;
            if (index >= this.filteredMachines.size()) {
                break;
            }
            FactorySnapshotBuilder.MachineView machine = this.filteredMachines.get(index);
            int y = l.gridY + i * FactoryLayout.ROW_H;
            drawMachineCard(graphics, machine, l.gridX, y, width);
        }
        if (this.machines.isEmpty()) {
            graphics.drawString(this.font, "没有机器", l.gridX,
                    l.gridY + height / 2 - 4, this.theme.textDim(), false);
        } else if (this.filteredMachines.isEmpty()) {
            graphics.drawString(this.font, "没有匹配", l.gridX,
                    l.gridY + height / 2 - 4, this.theme.textDim(), false);
        }
    }

    /**
     * 单张机器卡片。
     *
     * <p>卡片是深钢灰底 + 右下 2px 硬阴影，左侧 2px 状态条，机器图标放在独立凹槽里。
     * 文字分两行：第一行机器名和状态（状态灯 + 彩色文字），第二行产线（青色）和
     * 规则数（灰蓝）。名称和产线会按可用宽度截断，不能压到状态或规则数上。</p>
     */
    private void drawMachineCard(GuiGraphics graphics,
            FactorySnapshotBuilder.MachineView machine, int x, int y, int width) {
        int cardX = x + 2;
        int cardY = y + 1;
        int cardW = width - 4;
        int cardH = FactoryLayout.ROW_H - 3;

        // 右下阴影 + 深钢灰卡片，做出卡片浮在面板上的层次。
        NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK,
                cardX + 2, cardY + 2, cardW, cardH);
        NineSlice.machineCard(graphics, cardX, cardY, cardW, cardH);

        Status status = statusOf(machine);
        NineSlice.fill(graphics, status.strip(), cardX + 1, cardY + 2,
                2, cardH - 4);

        int iconSlotX = cardX + 4;
        int iconSlotY = cardY + 2;
        NineSlice.slot(graphics, iconSlotX, iconSlotY,
                FactoryLayout.CELL, FactoryLayout.CELL);
        ItemStack icon = machine.machineMissing()
                ? new ItemStack(Items.BARRIER)
                : FactoryLabels.blockStack(machine.blockId());
        graphics.renderItem(icon.isEmpty() ? new ItemStack(Items.BARRIER) : icon,
                iconSlotX + 1, iconSlotY + 1);

        int textX = iconSlotX + FactoryLayout.CELL + 5;
        int right = cardX + cardW - 5;
        int nameY = cardY + 3;
        int lineY = cardY + 13;

        String statusText = status.text();
        int statusWidth = this.font.width(statusText);
        int statusX = right - statusWidth;
        int lampX = Math.max(textX, statusX - 7);
        NineSlice.icon(graphics, status.lamp(), lampX, nameY + 2,
                GuiTextures.STATUS_LAMP_SIZE);

        int nameWidth = Math.max(18, lampX - textX - 4);
        String cardName = machine.machineMissing()
                ? "已失联" : FactoryLabels.blockName(machine.blockId());
        graphics.drawString(this.font,
                this.font.plainSubstrByWidth(cardName, nameWidth),
                textX, nameY, CARD_NAME, false);
        graphics.drawString(this.font, statusText, statusX, nameY, status.color(), false);

        String lines = machine.lineNames().isEmpty()
                ? "未编线" : String.join(" / ", machine.lineNames());
        String rulesLabel = "规则 ";
        String rulesValue = Integer.toString(machine.ruleCount());
        int rulesWidth = this.font.width(rulesLabel) + this.font.width(rulesValue);
        int lineWidth = Math.max(12, right - rulesWidth - 6 - textX);
        graphics.drawString(this.font, this.font.plainSubstrByWidth(lines, lineWidth),
                textX, lineY,
                machine.lineNames().isEmpty()
                        ? this.theme.textDim() : this.theme.textValue(), false);
        int rulesX = right - rulesWidth;
        graphics.drawString(this.font, rulesLabel, rulesX, lineY, CARD_META, false);
        graphics.drawString(this.font, rulesValue,
                rulesX + this.font.width(rulesLabel), lineY,
                this.theme.textValue(), false);

        if (this.editController != null && this.editController.isDragSource(machine.ref())) {
            graphics.fill(cardX, cardY, cardX + cardW, cardY + cardH, 0x66000000);
        }
    }

    // ------------------------------------------------------------------
    // 槽位
    // ------------------------------------------------------------------

    /**
     * 玩家背包槽位的底图。
     *
     * <p>悬停时换成浅蓝 {@code slot_hover}，其余时候用普通凹槽；物品和数量仍由
     * {@code super.renderSlot} 画在上面，不能在这里覆盖。</p>
     */
    @Override
    protected void renderSlot(GuiGraphics graphics, Slot slot) {
        if (isHovering(slot.x, slot.y, 16, 16, this.renderMouseX, this.renderMouseY)) {
            NineSlice.icon(graphics, GuiTextures.SLOT_HOVER, slot.x - 1, slot.y - 1,
                    GuiTextures.SLOT_HOVER_SIZE);
        } else {
            NineSlice.slot(graphics, slot.x - 1, slot.y - 1,
                    FactoryLayout.CELL, FactoryLayout.CELL);
        }
        super.renderSlot(graphics, slot);
    }

    @Override
    protected void renderSlotHighlight(GuiGraphics graphics, Slot slot, int mouseX, int mouseY,
            float partialTick) {
        // 悬停底色已经在 renderSlot 里由 slot_hover 接管，不再叠原版白色高亮。
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 原版 GUI 的标题和「物品栏」文字由本类自己的标题/标签绘制替代。
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    /**
     * 当前鼠标位置对应的仓库条目。
     *
     * @return 条目；空气格、非仓库 tab 或不在网格内时为 {@code null}
     */
    @Nullable
    FactoryStorageS2C.Entry storageEntryAt(double mouseX, double mouseY) {
        FactoryLayout l = this.layout;
        if (this.activeTab != TAB_STORAGE
                || mouseX < l.gridX
                || mouseX >= l.gridX + FactoryLayout.GRID_COLS * FactoryLayout.CELL
                || mouseY < l.gridY
                || mouseY >= l.gridY + l.gridRows * FactoryLayout.CELL) {
            return null;
        }
        int col = (int) ((mouseX - l.gridX) / FactoryLayout.CELL);
        int row = (int) ((mouseY - l.gridY) / FactoryLayout.CELL);
        int index = (this.storageFirstRow + row) * FactoryLayout.GRID_COLS + col;
        return index >= 0 && index < this.filteredStorageEntries.size()
                ? this.filteredStorageEntries.get(index)
                : null;
    }

    /**
     * 把一个仓库手势发给服务端。
     *
     * <p>客户端只传资源 key 和手势，不传数量；服务端会重新判断背包、仓库空间和
     * carried，避免客户端伪造数量。</p>
     */
    private void sendStorageGesture(@Nullable ResourceKey key, Gesture gesture) {
        PacketDistributor.sendToServer(new FactoryStorageActionC2S(
                this.menu.containerId, key, gesture));
    }

    /**
     * 统一处理鼠标点击。
     *
     * <p>优先级：编辑浮层/产线下拉 → 顶栏按钮/关闭 → tab → 搜索清空 → 左表展开按钮 →
     * 仓库格手势 → 原版 slot 点击。仓库格点击必须走自定义 C2S，不能交给原版
     * 容器逻辑，否则会落到 36 个玩家槽位上。</p>
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        FactoryLayout l = this.layout;
        if (this.searchBox != null && this.searchBox.isFocused()
                && !this.searchBox.isMouseOver(mouseX, mouseY)) {
            // 点击别处先退出搜索焦点，否则 R/U 会被 EditBox 当成输入吞掉。
            this.searchBox.setFocused(false);
        }
        boolean onLineButton = button == 0
                && mouseY >= l.headerY + 3
                && mouseY < l.headerY + 3 + FactoryLayout.TAB_H
                && mouseX >= lineButtonX()
                && mouseX < lineButtonX() + lineButtonW();
        boolean lineMenuWasOpen = this.editController != null
                && this.editController.isLineMenuOpen();
        // 关闭按钮和 drawHeader 使用同一坐标：贴着 tab 条左边，不放在面板最右缘。
        int closeX = l.rightX - 6 - CLOSE_SIZE;
        boolean onCloseButton = mouseX >= closeX && mouseX < closeX + CLOSE_SIZE
                && mouseY >= l.headerY + 4 && mouseY < l.headerY + 4 + CLOSE_SIZE;
        boolean onTabBar = isTabBar(mouseX, mouseY);
        int sortButton = isSortButtonAt(mouseX, mouseY);

        if (this.editController != null
                && this.editController.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (sortButton >= 0) {
            if (this.sortButtons != null && (button == 0 || button == 1)) {
                this.sortButtons.click(sortButton, this.activeTab == TAB_STORAGE, button == 1);
            }
            return true;
        }
        if (onLineButton) {
            // 下拉打开时，控制器已经把这次“点外部”的点击吃掉并关闭了下拉；
            // 这里不能再 toggle 回去。
            if (!lineMenuWasOpen && this.editController != null) {
                this.editController.toggleLineMenu(lineButtonX(),
                        l.headerY + FactoryLayout.HEADER_H + 2);
            }
            return true;
        }
        if (onCloseButton) {
            this.onClose();
            return true;
        }
        int tabWidth = l.rightW / FactoryLayout.TAB_COUNT;
        if (onTabBar) {
            int tab = Math.min(FactoryLayout.TAB_COUNT - 1,
                    (int) ((mouseX - l.rightX) / tabWidth));
            this.activeTab = tab;
            if (tab == TAB_STORAGE) {
                requestStorage();
            }
            return true;
        }
        if (button == 0 && this.searchBox != null
                && !this.searchBox.getValue().isEmpty()
                && mouseX >= clearButtonX() - 1
                && mouseX < clearButtonX() + GuiTextures.SEARCH_CLEAR_SIZE + 1
                && mouseY >= l.searchY && mouseY < l.searchY + FactoryLayout.SEARCH_H) {
            this.searchBox.setValue("");
            this.searchBox.setFocused(true);
            return true;
        }
        if (button == 0 && this.editController != null) {
            FactorySnapshotBuilder.MachineView deleteTarget =
                    deleteButtonAt(mouseX, mouseY);
            if (deleteTarget != null) {
                this.editController.confirmDeleteMachine(deleteTarget);
                return true;
            }
        }
        if (clickMachineExpand(mouseX, mouseY)) {
            return true;
        }
        if (handleRuleStripMouse(mouseX, mouseY, button)) {
            return true;
        }
        TableHit ioHit = tableHitAt(mouseY);
        if (ioHit != null && ioHit.detailHeight() > 0
                && mouseY >= ioHit.y() + FactoryLayout.ROW_H) {
            IoDetailLayout.Card card = detailCardAt(ioHit, mouseX, mouseY);
            if (card != null) {
                handleIoCardMouse(ioHit.machine(), card, mouseX, mouseY, button);
                return true;
            }
        }
        if (this.activeTab == TAB_STORAGE
                && mouseX >= l.gridX
                && mouseX < l.gridX + FactoryLayout.GRID_COLS * FactoryLayout.CELL
                && mouseY >= l.gridY
                && mouseY < l.gridY + l.gridRows * FactoryLayout.CELL) {
            FactoryStorageS2C.Entry entry = storageEntryAt(mouseX, mouseY);
            ResourceKey key = entry == null ? null : entry.key();
            if (button == 0 || button == 1) {
                if (key != null && key.kind() != ResourceKind.ITEM) {
                    return true;
                }
                if (key == null && this.menu.getCarried().isEmpty()) {
                    return true;
                }
                Gesture gesture;
                if (hasShiftDown()) {
                    gesture = button == 0 ? Gesture.SHIFT_LEFT : Gesture.SHIFT_RIGHT;
                } else {
                    gesture = button == 0 ? Gesture.LEFT : Gesture.RIGHT;
                }
                sendStorageGesture(key, gesture);
                return true;
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * 鼠标落在第几个排序按钮上。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 0 排序键、1 方向、2 范围；不在按钮内返回 -1
     */
    private int isSortButtonAt(double mouseX, double mouseY) {
        FactoryLayout l = this.layout;
        for (int index = 0; index < FactoryLayout.SORT_BTN_COUNT; index++) {
            int x = l.sortButtonX(index);
            if (mouseX >= x && mouseX < x + FactoryLayout.SORT_BTN_SIZE
                    && mouseY >= l.searchY
                    && mouseY < l.searchY + FactoryLayout.SORT_BTN_SIZE) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 鼠标是否落在右区 tab 条上。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 在 tab 条内为 {@code true}
     */
    private boolean isTabBar(double mouseX, double mouseY) {
        FactoryLayout l = this.layout;
        return mouseY >= l.tabY && mouseY < l.tabY + FactoryLayout.HEADER_H
                && mouseX >= l.rightX && mouseX < l.rightX + l.rightW;
    }

    private int clearButtonX() {
        return this.layout.rightX + 4 + this.layout.searchWidth() - 14;
    }

    /**
     * 搜索框底板：纯色填充 + 1px 外框 + 1px 内凹阴影。
     *
     * <p>这里不用 NineSlice.field：4px 高的框会把 6×6 贴图的内斜边拉成不规则色块。
     * 所有边都用 1×1 纯色贴图拉伸，保证任何 GUI 缩放下边框始终是 1px。</p>
     */
    private void drawSearchField(GuiGraphics graphics) {
        FactoryLayout l = this.layout;
        int x = l.rightX + 4;
        int y = l.searchY;
        int w = l.searchWidth();
        int h = FactoryLayout.SEARCH_H;

        NineSlice.fill(graphics, GuiTextures.SEARCH_BG, x + 2, y + 2, w - 4, h - 4);

        // 外框
        NineSlice.fill(graphics, GuiTextures.SEARCH_BORDER, x, y, w, 1);
        NineSlice.fill(graphics, GuiTextures.SEARCH_BORDER, x, y + h - 1, w, 1);
        NineSlice.fill(graphics, GuiTextures.SEARCH_BORDER, x, y, 1, h);
        NineSlice.fill(graphics, GuiTextures.SEARCH_BORDER, x + w - 1, y, 1, h);

        // 内凹：上/左用阴影，下/右用高光，形成凹进面板的搜索框。
        NineSlice.fill(graphics, GuiTextures.SEARCH_SHADOW, x + 1, y + 1, w - 2, 1);
        NineSlice.fill(graphics, GuiTextures.SEARCH_SHADOW, x + 1, y + 1, 1, h - 2);
        NineSlice.fill(graphics, GuiTextures.SEARCH_HIGHLIGHT, x + 1, y + h - 2, w - 2, 1);
        NineSlice.fill(graphics, GuiTextures.SEARCH_HIGHLIGHT, x + w - 2, y + 1, 1, h - 2);
    }

    /**
     * 搜索行右侧的三个排序按钮：键、方向、范围。
     *
     * <p>蓝灰底 + 白色单色图标；鼠标悬停提亮，不可用（默认顺序的方向、机器 tab 的范围）
     * 压暗但保留占位，避免切换 tab 时搜索框宽度跳动。</p>
     */
    private void drawSortButtons(GuiGraphics graphics) {
        FactoryLayout l = this.layout;
        FactorySortButtons buttons = this.sortButtons;
        if (buttons == null) {
            return;
        }
        boolean storageTab = this.activeTab == TAB_STORAGE;
        int hovered = isSortButtonAt(this.renderMouseX, this.renderMouseY);
        for (int index = 0; index < FactoryLayout.SORT_BTN_COUNT; index++) {
            int x = l.sortButtonX(index);
            int y = l.searchY;
            int size = FactoryLayout.SORT_BTN_SIZE;
            NineSlice.sortButton(graphics, x, y, size, size);
            NineSlice.icon(graphics, buttons.icon(index, storageTab), x + 1, y + 1,
                    GuiTextures.SORT_ICON_SIZE);
            if (hovered == index) {
                graphics.fill(x, y, x + size, y + size, 0x22FFFFFF);
            }
            if (!buttons.usable(index, storageTab)) {
                graphics.fill(x + 1, y + 1, x + size - 1, y + size - 1, 0x66000000);
            }
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.editController != null
                && this.editController.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (this.searchBox != null && this.searchBox.isFocused()) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                this.searchBox.setFocused(false);
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER) {
                this.searchBox.setFocused(false);
                return true;
            }
            // 编辑键（退格、方向、Ctrl+A/C/V 等）交给 EditBox；其余键在搜索框
            // 聚焦时必须吞掉，否则 E 会被当成关闭容器，Q/数字键会丢物品或切快捷栏。
            if (this.searchBox.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * 搜索框为空时吞掉开头的空格。
     *
     * <p>空格是搜索语法里的「与」连接符；如果在空搜索框里先输入空格，查询会变得
     * 没有意义。非空后仍交给 EditBox，允许用户用空格分隔多个条件。</p>
     */
    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.editController != null
                && this.editController.charTyped(codePoint, modifiers)) {
            return true;
        }
        if (this.searchBox != null && this.searchBox.isFocused()
                && codePoint == ' ' && this.searchBox.getValue().isEmpty()) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    /**
     * 关闭界面时保存搜索词和产线选择。
     *
     * <p>这两项是纯客户端状态，跨打开保留；产线还会在 acceptTable 里校验，
     * 如果服务端已经没有该产线就自动清空。</p>
     */
    @Override
    public void removed() {
        if (this.searchBox != null) {
            rememberedSearch = this.searchBox.getValue();
        }
        rememberedLineId = this.selectedLineId;
        if (this.editController != null) {
            this.editController.closeAll();
        }
        super.removed();
    }

    /**
     * 鼠标拖动：优先交给编辑控制器做机器/资源拖拽。
     *
     * @param mouseX     屏幕 x
     * @param mouseY     屏幕 y
     * @param button     鼠标键
     * @param dragX      水平拖动量
     * @param dragY      垂直拖动量
     * @return 是否消费
     */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX,
            double dragY) {
        if (this.editController != null
                && this.editController.mouseDragged(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    /**
     * 鼠标松开：优先交给编辑控制器结算拖放。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @param button 鼠标键
     * @return 是否消费
     */
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.editController != null
                && this.editController.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /**
     * 滚轮：仓库格上的 Shift+滚轮是「移动一个」，其余位置是列表滚动。
     *
     * <p>Shift 只在仓库格里接管，避免玩家想滚动列表时误触发存取。左表、仓库、
     * 机器库的滚动都只改各自的 firstRow，不直接改数据。</p>
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.editController != null
                && this.editController.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        if (ioScrollAt(mouseX, mouseY, scrollY)) {
            return true;
        }
        FactoryLayout l = this.layout;
        if (this.activeTab == TAB_STORAGE
                && hasShiftDown()
                && mouseX >= l.gridX
                && mouseX < l.gridX + FactoryLayout.GRID_COLS * FactoryLayout.CELL
                && mouseY >= l.gridY
                && mouseY < l.gridY + l.gridRows * FactoryLayout.CELL) {
            FactoryStorageS2C.Entry entry = storageEntryAt(mouseX, mouseY);
            if (entry != null && entry.key().kind() == ResourceKind.ITEM) {
                sendStorageGesture(entry.key(),
                        scrollY > 0 ? Gesture.SCROLL_UP : Gesture.SCROLL_DOWN);
                return true;
            }
        }
        if (mouseX >= l.leftX && mouseX < l.rightX - FactoryLayout.GAP
                && mouseY >= l.rowTop && mouseY < l.footerY) {
            int max = maxFirstRow();
            if (max > 0) {
                int step = scrollY > 0 ? -1 : 1;
                this.firstRow = Math.max(0, Math.min(max, this.firstRow + step));
                return true;
            }
        }
        if (mouseX >= l.gridX
                && mouseX < l.gridX + FactoryLayout.GRID_COLS * FactoryLayout.CELL
                && mouseY >= l.gridY
                && mouseY < l.gridY + l.gridRows * FactoryLayout.CELL) {
            int step = scrollY > 0 ? -1 : 1;
            if (this.activeTab == TAB_STORAGE) {
                int max = maxStorageFirstRow();
                if (max > 0) {
                    this.storageFirstRow = Math.max(0, Math.min(max,
                            this.storageFirstRow + step));
                    return true;
                }
            } else {
                int max = maxMachineFirstRow();
                if (max > 0) {
                    this.machineFirstRow = Math.max(0, Math.min(max,
                            this.machineFirstRow + step));
                    return true;
                }
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /**
     * JEI R/U 查询目标：一个物品和它在屏幕上的格子区域。
     *
     * @param stack 物品
     * @param area  可点击区域
     */
    public record JeiTarget(ItemStack stack, Rect2i area) {
    }

    /**
     * 当前鼠标下的 JEI 查询目标。
     *
     * <p>覆盖左表机器/输入/输出、仓库格、机器库卡片和玩家背包槽位。只返回界面
     * 上真实画出来的物品；流体按桶图标、能量按红石图标处理，和绘制使用同一份
     * {@link #iconStack}。JEI 插件拿到这个目标后会自己创建 clickable ingredient。</p>
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 目标；鼠标下没有物品时为 {@code null}
     */
    @Nullable
    public JeiTarget jeiTargetAt(double mouseX, double mouseY) {
        if (this.layout == null) {
            return null;
        }
        if (this.editController != null && this.editController.isOverlayActive()) {
            return null;
        }
        FactoryLayout l = this.layout;

        for (Slot slot : this.menu.slots) {
            if (slot.hasItem()
                    && isHovering(slot.x, slot.y, 16, 16, mouseX, mouseY)) {
                return new JeiTarget(slot.getItem(),
                        new Rect2i(this.leftPos + slot.x - 1,
                                this.topPos + slot.y - 1, FactoryLayout.CELL,
                                FactoryLayout.CELL));
            }
        }

        if (this.activeTab == TAB_STORAGE
                && mouseX >= l.gridX
                && mouseX < l.gridX + FactoryLayout.GRID_COLS * FactoryLayout.CELL
                && mouseY >= l.gridY
                && mouseY < l.gridY + l.gridRows * FactoryLayout.CELL) {
            FactoryStorageS2C.Entry entry = storageEntryAt(mouseX, mouseY);
            if (entry != null) {
                ItemStack stack = iconStack(entry.key());
                if (!stack.isEmpty()) {
                    int col = (int) ((mouseX - l.gridX) / FactoryLayout.CELL);
                    int row = (int) ((mouseY - l.gridY) / FactoryLayout.CELL);
                    return new JeiTarget(stack,
                            new Rect2i(l.gridX + col * FactoryLayout.CELL,
                                    l.gridY + row * FactoryLayout.CELL,
                                    FactoryLayout.CELL, FactoryLayout.CELL));
                }
            }
        }

        if (this.activeTab == TAB_MACHINES
                && mouseX >= l.gridX
                && mouseX < l.gridX + FactoryLayout.GRID_COLS * FactoryLayout.CELL
                && mouseY >= l.gridY
                && mouseY < l.gridY + l.gridRows * FactoryLayout.CELL) {
            int row = (int) ((mouseY - l.gridY) / FactoryLayout.ROW_H);
            int index = this.machineFirstRow + row;
            if (row < machineRowsVisible()
                    && index >= 0 && index < this.filteredMachines.size()) {
                int cardY = l.gridY + row * FactoryLayout.ROW_H;
                int iconX = l.gridX + 6;
                int iconY = cardY + 3;
                if (inside(mouseX, mouseY, iconX, iconY,
                        FactoryLayout.CELL, FactoryLayout.CELL)) {
                    FactorySnapshotBuilder.MachineView machine =
                            this.filteredMachines.get(index);
                    ItemStack stack = FactoryLabels.blockStack(machine.blockId());
                    if (!stack.isEmpty()) {
                        return new JeiTarget(stack, new Rect2i(iconX, iconY,
                                FactoryLayout.CELL, FactoryLayout.CELL));
                    }
                }
            }
        }

        if (mouseX >= l.leftX && mouseX < l.rightX - FactoryLayout.GAP
                && mouseY >= l.rowTop && mouseY < l.footerY) {
            TableHit hit = tableHitAt(mouseY);
            if (hit != null) {
                FactorySnapshotBuilder.MachineView machine = hit.machine();
                int y = hit.y();

                // 展开按钮先返回空，避免被输出列识别成资源。
                if (inExpandButton(hit, mouseX, mouseY)) {
                    return null;
                }

                // 展开区配置卡：只有左上角资源槽是 JEI 目标；面/匹配/删除按钮不返回。
                IoDetailLayout.Card card = detailCardAt(hit, mouseX, mouseY);
                if (card != null) {
                    if (!card.empty()
                            && inside(mouseX, mouseY, card.x() + 3, card.y() + 3,
                                    FactoryLayout.CELL, FactoryLayout.CELL)) {
                        List<ResourceSpec> rules = card.input()
                                ? machine.inputs() : machine.outputs();
                        if (card.ruleIndex() < rules.size()) {
                            ItemStack stack = iconStack(rules.get(card.ruleIndex()).key());
                            if (!stack.isEmpty()) {
                                return new JeiTarget(stack, new Rect2i(
                                        card.x() + 4, card.y() + 4,
                                        FactoryLayout.CELL, FactoryLayout.CELL));
                            }
                        }
                    }
                    return null;
                }

                // 列命中只对基础行有效，展开区不再回落到输入/输出列。
                if (mouseY >= y + FactoryLayout.ROW_H) {
                    return null;
                }

                int iconX = l.columnX(FactoryLayout.COL_NAME) + 4;
                if (inside(mouseX, mouseY, iconX, y + 4,
                        FactoryLayout.CELL, FactoryLayout.CELL)) {
                    ItemStack stack = FactoryLabels.blockStack(machine.blockId());
                    if (!stack.isEmpty()) {
                        return new JeiTarget(stack, new Rect2i(iconX, y + 4,
                                FactoryLayout.CELL, FactoryLayout.CELL));
                    }
                }
                if (l.hasColumn(FactoryLayout.COL_INPUT)) {
                    JeiTarget target = ruleTarget(machine.inputs(), machine.inputRates(),
                            l.columnX(FactoryLayout.COL_INPUT) + 2, y + 4,
                            l.columnW(FactoryLayout.COL_INPUT) - 4, mouseX, mouseY);
                    if (target != null) {
                        return target;
                    }
                }
                if (l.hasColumn(FactoryLayout.COL_OUTPUT)) {
                    JeiTarget target = ruleTarget(machine.outputs(), machine.outputRates(),
                            l.columnX(FactoryLayout.COL_OUTPUT) + 2, y + 4,
                            l.columnW(FactoryLayout.COL_OUTPUT) - 4
                                    - FactoryLayout.EXPAND_BUTTON - 2,
                            mouseX, mouseY);
                    if (target != null) {
                        return target;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 在输入/输出列里找当前鼠标命中的规则图标。
     *
     * <p>位置计算必须和 {@link #drawRuleIcons} 完全一致：每条包含 18px 图标和
     * 速率文字，最多 3 条；绘制时如果第二条会超出 maxWidth 就停止，这里同样停止。</p>
     */
    @Nullable
    private JeiTarget ruleTarget(List<ResourceSpec> rules, List<Long> rates,
            int x, int y, int maxWidth, double mouseX, double mouseY) {
        if (rules.isEmpty()) {
            return null;
        }
        int cursor = x;
        int shown = 0;
        int limit = Math.min(rules.size(), 3);
        while (shown < limit) {
            String rate = FactoryLabels.rateText(shown < rates.size() ? rates.get(shown) : 0L,
                    rules.get(shown).key().kind());
            int entryWidth = FactoryLayout.CELL + 2 + this.font.width(rate) + 6;
            if (shown > 0 && cursor + entryWidth > x + maxWidth) {
                break;
            }
            if (inside(mouseX, mouseY, cursor, y,
                    FactoryLayout.CELL, FactoryLayout.CELL)) {
                ResourceKey key = rules.get(shown).key();
                ItemStack stack = iconStack(key);
                if (!stack.isEmpty()) {
                    return new JeiTarget(stack, new Rect2i(cursor, y,
                            FactoryLayout.CELL, FactoryLayout.CELL));
                }
            }
            cursor += entryWidth;
            shown++;
        }
        return null;
    }

    /** 鼠标是否落在给定矩形内；所有自定义格子的命中都走这里。 */
    private static boolean inside(double mouseX, double mouseY,
            int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width
                && mouseY >= y && mouseY < y + height;
    }

    /**
     * 当前布局。
     *
     * <p>保留给调试、测试和后续阶段读取；正常绘制不应从外部修改布局字段。</p>
     */
    public FactoryLayout layout() {
        return this.layout;
    }
}

