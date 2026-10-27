package io.github.crafteve.idlefactory.client.screen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.storage.FactoryStorageRepo;
import io.github.crafteve.idlefactory.client.theme.GuiTheme;
import io.github.crafteve.idlefactory.client.widget.GuiTextures;
import io.github.crafteve.idlefactory.client.widget.NineSlice;
import io.github.crafteve.idlefactory.client.widget.SearchEditBox;
import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.factory.FactoryState;
import io.github.crafteve.idlefactory.factory.machine.MachineRef;
import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
import io.github.crafteve.idlefactory.factory.resource.ResourceKind;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;
import io.github.crafteve.idlefactory.integration.jecharacters.JecharactersCompat;
import io.github.crafteve.idlefactory.menu.FactoryMenu;
import io.github.crafteve.idlefactory.network.payload.FactoryStorageActionC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryStorageActionC2S.Gesture;
import io.github.crafteve.idlefactory.network.payload.FactoryStorageRequestC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryStorageS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTableRequestC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryTableS2C;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
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
 * 工厂工作台。
 *
 * <p>左表接服务端机器库视图，数据复用 {@link FactorySnapshotBuilder.MachineView}。
 * 右区仓库。{@link FactoryStorageRepo}，点击手势通过 {@code FactoryStorageActionC2S}
 * 交给服务端处理。/p>
 *
 * <p>静态外观全部来自贴图（{@link NineSlice}），这里不画边框和斜边；纯色块用
 * {@link NineSlice#fill} 。1×1 贴图拉伸。/p>
 *
 * <p>几何全在 {@link FactoryLayout}，本类只负责「按坐标画」。以后调尺寸只改布局类。/p>
 */
public class FactoryScreen extends AbstractContainerScreen<FactoryMenu> {
    /** 标题与下拉之间的间距。*/
    private static final int TITLE_GAP = 10;
    /** 关闭按钮边长。*/
    private static final int CLOSE_SIZE = 14;
    /** 机器行展开后额外高度：两行规则。*/
    private static final int EXPANDED_H = 46;
    /** 行尾展开按钮边长。*/
    private static final int EXPAND_BUTTON = 12;
    /** 机器卡片上的文字色：名称、产线、规则。*/
    private static final int CARD_NAME = 0xFFE8ECF5;
    private static final int CARD_LINE = 0xFF8FB7C7;
    private static final int CARD_META = 0xFF98A2B8;

    /** P1 第一小步：每多少 tick 请求一次机器表。*/
    private static final int REQUEST_INTERVAL = 20;
    /** 仓库 tab 打开时每多少 tick 请求一次仓库。*/
    private static final int STORAGE_REQUEST_INTERVAL = 100;
    /** tab 下标：仓库。*/
    private static final int TAB_STORAGE = 0;
    /** tab 下标：机器。*/
    private static final int TAB_MACHINES = 1;

    private final GuiTheme theme = GuiTheme.current();
    private FactoryLayout layout;
    private SearchEditBox searchBox;
    /** 服务端机器库视图；直接复。{@link FactorySnapshotBuilder.MachineView}。*/
    private List<FactorySnapshotBuilder.MachineView> machines = List.of();
    private boolean hasFactory;
    private FactoryState factoryState = FactoryState.CREATED;
    private boolean storageOnline;
    private int lineCount;
    private int runningLineCount;
    /** 左表第一条可见机器。*/
    private int firstRow;
    private int requestTicker;

    /** 当前右区 tab。 仓库。 机器。*/
    private int activeTab = TAB_STORAGE;
    /** 左表产线筛选；null 表示还没选择产线。*/
    private String lineFilter;
    private boolean lineMenuOpen;
    private final FactoryStorageRepo storageRepo = new FactoryStorageRepo();
    private boolean storageReceived;
    private boolean storageTruncated;
    private int storageFirstRow;
    private int machineFirstRow;
    private int storageRequestTicker;
    /** 已展开的机器；按机器坐标作为稳定身份。*/
    private final Set<MachineRef> expandedMachines = new HashSet<>();

    private FactorySearchQuery searchQuery = FactorySearchQuery.EMPTY;
    private List<FactoryStorageS2C.Entry> filteredStorageEntries = List.of();
    private List<FactorySnapshotBuilder.MachineView> filteredMachines = List.of();
    private final Map<ResourceKey, FactorySearchQuery.Fields> storageSearchFields =
            new HashMap<>();
    private static String rememberedSearch = "";
    private static String rememberedLineFilter;
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
        // 父类。imageWidth/imageHeight 居中，正好等。layout 里算。panelX/panelY
        super.init();
        if (this.lineFilter == null) {
            this.lineFilter = rememberedLineFilter;
        }
        positionSlots();
        String previousQuery = this.searchBox == null
                ? rememberedSearch
                : this.searchBox.getValue();
        int searchX = this.layout.rightX + 4;
        int searchW = this.layout.rightW - 8;
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
     * 收到服务端机器表。
     *
     * <p>。{@code ClientFactoryTableHandler} 调用；这里只存数据，不自己算世界状态。/p>
     *
     * @param payload 机器。
     */
    public void acceptTable(FactoryTableS2C payload) {
        this.hasFactory = payload.hasFactory();
        this.factoryState = payload.state();
        this.storageOnline = payload.storageOnline();
        this.lineCount = payload.lineCount();
        this.runningLineCount = payload.runningLineCount();
        this.machines = payload.machines();
        Set<MachineRef> currentRefs = new HashSet<>();
        for (FactorySnapshotBuilder.MachineView machine : this.machines) {
            currentRefs.add(machine.ref());
        }
        this.expandedMachines.retainAll(currentRefs);
        if (this.lineFilter != null && !lineOptions().contains(this.lineFilter)) {
            this.lineFilter = null;
        }
        rememberedLineFilter = this.lineFilter;
        this.firstRow = Math.max(0, Math.min(this.firstRow, maxFirstRow()));
        if (!this.hasFactory) {
            this.storageRepo.clear();
            this.storageReceived = false;
        }
        rebuildFiltered();
        updateColumnWidths();
    }

    /**
     * 收到服务端仓库摘要。
     *
     * @param payload 仓库摘要
     */
    public void acceptStorage(FactoryStorageS2C payload) {
        this.storageRepo.replace(payload.entries());
        this.storageOnline = payload.online();
        this.storageTruncated = payload.truncated();
        this.storageReceived = true;
        rebuildFiltered();
    }

    private void rebuildFiltered() {
        if (this.searchQuery.isEmpty()) {
            this.storageRepo.setFilter(null);
            this.filteredMachines = this.machines;
        } else {
            this.storageRepo.setFilter(entry ->
                    this.searchQuery.matches(storageSearchFields(entry.key())));

            List<FactorySnapshotBuilder.MachineView> machines = new ArrayList<>();
            for (FactorySnapshotBuilder.MachineView machine : this.machines) {
                if (this.searchQuery.matches(machineSearchFields(machine))) {
                    machines.add(machine);
                }
            }
            this.filteredMachines = List.copyOf(machines);
        }
        this.filteredStorageEntries = this.storageRepo.view();
        this.storageFirstRow = Math.max(0,
                Math.min(this.storageFirstRow, maxStorageFirstRow()));
        this.machineFirstRow = Math.max(0,
                Math.min(this.machineFirstRow, maxMachineFirstRow()));
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
        String name = blockName(machine.blockId());
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
        // 行高可变，最后一台机器可以滚到顶部；旧实现按固定 rowsVisible 算会卡住。
        return Math.max(0, tableMachines().size() - 1);
    }

    private List<FactorySnapshotBuilder.MachineView> tableMachines() {
        if (this.lineFilter == null) {
            return List.of();
        }
        List<FactorySnapshotBuilder.MachineView> filtered = new ArrayList<>();
        for (FactorySnapshotBuilder.MachineView machine : this.machines) {
            if (machine.lineNames().contains(this.lineFilter)) {
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
                    this.font.width(blockName(machine.blockId())) + FactoryLayout.CELL + 12);
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
            String rate = countText(i < rates.size() ? rates.get(i) : 0L) + "/s";
            width += FactoryLayout.CELL + 2 + this.font.width(rate) + 6;
        }
        return Math.max(72, width);
    }

    private List<String> lineOptions() {
        List<String> names = new ArrayList<>();
        for (FactorySnapshotBuilder.MachineView machine : this.machines) {
            for (String name : machine.lineNames()) {
                if (!names.contains(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private String lineButtonText() {
        return this.lineFilter == null ? "选择产线" : this.lineFilter;
    }

    private int lineButtonX() {
        return this.layout.headerX + 6 + this.font.width("放置工厂")
                + TITLE_GAP;
    }

    private int lineButtonW() {
        return this.font.width(lineButtonText()) + 24;
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
                Item item = BuiltInRegistries.ITEM.get(key.id());
                if (item != Items.AIR) {
                    new ItemStack(item).getTags()
                            .forEach(tag -> tags.add(tag.location().toString()));
                }
            }
            case FLUID -> {
                var fluid = BuiltInRegistries.FLUID.get(key.id());
                BuiltInRegistries.FLUID.wrapAsHolder(fluid).tags()
                        .forEach(tag -> tags.add(tag.location().toString()));
            }
            default -> {
                // 能量/化学品没有标。
            }
        }
        return tags;
    }

    private static ItemStack resourceStack(ResourceKey key) {
        return switch (key.kind()) {
            case ITEM -> {
                Item item = BuiltInRegistries.ITEM.get(key.id());
                yield item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
            }
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
     * <p>尺寸问题（挤压配方查看器、列被裁掉）只有在具体分辨率 + GUI 缩放下才复现得出来，
     * 而从截图里反推缩放很不可靠。所以直接把这一组数字打出来，让排障只需要看一行日志。/p>
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
     * 。36 个玩家槽位摆到右区底部。
     *
     * <p>{@code Slot.x/y} 。1.21.1 。{@code final}，改不了坐标，只能按新坐标重建槽位对象。
     * 这里只替换客户端菜单里的槽位，容器与容器索引不变，服务端完全不受影响（它不需要坐标）。/p>
     *
     * <p>槽位坐标相对面板左上角（父类渲染时会平移），所以全部减。leftPos/topPos。/p>
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
        drawLineMenu(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        FactoryLayout l = this.layout;

        NineSlice.panel(graphics, this.leftPos, this.topPos, this.imageWidth, this.imageHeight);

        // 顶栏下沿：画到右区左边缘为止，右边让 tab 条自己收。
        NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK,
                l.headerX, l.headerY + FactoryLayout.HEADER_H, l.rightX - l.headerX, 1);

        // 左右两区分隔。
        NineSlice.fill(graphics, GuiTextures.DIVIDER,
                l.rightX - FactoryLayout.GAP / 2, l.bodyY, 1, l.bodyH);

        // 表头/行卡片整体缩。2px 坐在框里；表框在 drawTable 末尾最后画。
        NineSlice.header(graphics, l.leftX + 2, l.tableHeaderY + 2,
                l.leftW - 4, FactoryLayout.TABLE_HEADER_H - 2);

        // 统计行上。
        NineSlice.fill(graphics, GuiTextures.DIVIDER, l.tableX, l.footerY, l.tableW, 1);

        drawRightChrome(graphics);
    }

    /** 右区的静态部分：tab、搜索框、物品网格、合成区。*/
    private void drawRightChrome(GuiGraphics graphics) {
        FactoryLayout l = this.layout;

        // tab 条：顶栏右端，和面板顶边齐平，宽度正好等于右。
        int tabWidth = l.rightW / FactoryLayout.TAB_COUNT;
        for (int i = 0; i < FactoryLayout.TAB_COUNT; i++) {
            int x = l.rightX + i * tabWidth;
            int w = i == FactoryLayout.TAB_COUNT - 1 ? l.rightW - i * tabWidth : tabWidth;
            NineSlice.button(graphics, x, l.tabY, w, FactoryLayout.HEADER_H);
        }
        // 选中 tab 的上沿强。
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
        if (this.activeTab == TAB_STORAGE) {
            drawGrid(graphics, l.gridX, l.gridY, FactoryLayout.GRID_COLS, l.gridRows,
                    FactoryLayout.CELL);
        }

        // 合成占位保持 P0 原样：两。tab 都画
        drawGrid(graphics, l.craftX, l.craftY, FactoryLayout.CRAFT, FactoryLayout.CRAFT,
                FactoryLayout.CRAFT_CELL);

        // 合成箭头。×3 与结果格之间的一条细。+ 箭头。
        int arrowY = l.resultY + FactoryLayout.CRAFT_RESULT / 2;
        NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK,
                l.craftX + FactoryLayout.CRAFT * FactoryLayout.CRAFT_CELL + 4, arrowY,
                FactoryLayout.CRAFT_ARROW - 8, 1);

        NineSlice.slot(graphics, l.resultX, l.resultY,
                FactoryLayout.CRAFT_RESULT, FactoryLayout.CRAFT_RESULT);
    }

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

    private void drawGrid(GuiGraphics graphics, int x, int y, int columns, int rows, int cell) {
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                NineSlice.slot(graphics, x + col * cell, y + row * cell, cell, cell);
            }
        }
    }

    /** 顶栏。*/
    private void drawHeader(GuiGraphics graphics) {
        FactoryLayout l = this.layout;
        int x = l.headerX + 6;
        int textY = l.headerY + (FactoryLayout.HEADER_H - 9) / 2;

        String title = "放置工厂";
        graphics.drawString(this.font, title, x, textY, this.theme.accent(), false);
        x += this.font.width(title) + TITLE_GAP;

        String line = lineButtonText();
        int buttonW = lineButtonW();
        NineSlice.button(graphics, x, l.headerY + 3, buttonW, FactoryLayout.TAB_H);
        graphics.drawString(this.font, line, x + 8, textY, this.theme.textPrimary(), false);
        x += buttonW + 8;

        String status;
        int statusColor;
        if (!this.hasFactory) {
            status = "未创建";
            statusColor = this.theme.statusIdle();
        } else {
            switch (this.factoryState) {
                case RUNNING -> {
                    status = "运行中";
                    statusColor = this.theme.statusRun();
                }
                case PAUSED -> {
                    status = this.storageOnline ? "已暂停" : "仓库离线";
                    statusColor = this.storageOnline ? this.theme.statusWarn() : this.theme.error();
                }
                case STOPPED -> {
                    status = "已停止";
                    statusColor = this.theme.statusIdle();
                }
                default -> {
                    status = "未绑仓库";
                    statusColor = this.theme.statusIdle();
                }
            }
        }
        graphics.drawString(this.font, status, x + 4, textY, statusColor, false);
        int statusEnd = x + 4 + this.font.width(status);

        // 右侧统计与关闭按钮：关闭按钮贴着 tab 条左边，统计只在放得下时才画。
        // 窄屏。40 逻辑宽）下顶栏要同时塞标题、下拉、状态、统计、关闭和两个 tab。
        // 统计是最不重要的一项，优先让它让位，而不是让文字压在一起。
        String stats = this.machines.size() + " 台 · " + this.lineCount + " 线 · 仓库"
                + (this.storageOnline ? "在线" : "离线");
        int closeX = l.rightX - 6 - CLOSE_SIZE;
        int statsX = closeX - 8 - this.font.width(stats);
        if (statsX > statusEnd + 8) {
            graphics.drawString(this.font, stats, statsX, textY, this.theme.textDim(), false);
        }

        NineSlice.button(graphics, closeX, l.headerY + 4, CLOSE_SIZE, CLOSE_SIZE);
        graphics.drawString(this.font, "x",
                closeX + (CLOSE_SIZE - this.font.width("x")) / 2, textY,
                this.theme.textPrimary(), false);
    }

    private void drawLineMenu(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!this.lineMenuOpen) {
            return;
        }
        List<String> options = lineOptions();
        int x = lineButtonX();
        int y = this.layout.headerY + FactoryLayout.HEADER_H + 2;
        int width = lineMenuWidth(options);
        int rowH = 16;
        int height = options.size() * rowH + 6;

        // 右下阴影，让菜单真的浮起。
        NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK, x + 2, y + 2, width, height);
        // 外层深色。+ 白色内框
        NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK, x, y, width, 1);
        NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK, x, y + height - 1, width, 1);
        NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK, x, y, 1, height);
        NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK, x + width - 1, y, 1, height);
        NineSlice.fill(graphics, GuiTextures.TABLE_LIGHT, x + 1, y + 1, width - 2, 1);
        NineSlice.fill(graphics, GuiTextures.TABLE_LIGHT, x + 1, y + 1, 1, height - 2);
        NineSlice.fill(graphics, GuiTextures.TABLE_LIGHT, x + 1, y + height - 2, width - 2, 1);
        NineSlice.fill(graphics, GuiTextures.TABLE_LIGHT, x + width - 2, y + 1, 1, height - 2);
        NineSlice.fill(graphics, GuiTextures.HEADER_BG, x + 2, y + 2, width - 4, height - 4);

        for (int i = 0; i < options.size(); i++) {
            int rowY = y + 3 + i * rowH;
            NineSlice.button(graphics, x + 3, rowY, width - 6, rowH - 2);
            boolean selected = options.get(i).equals(this.lineFilter);
            boolean hovered = mouseX >= x + 3 && mouseX < x + width - 3
                    && mouseY >= rowY && mouseY < rowY + rowH - 2;
            if (selected || hovered) {
                NineSlice.fill(graphics, GuiTextures.ROW_ALT, x + 4, rowY + 1,
                        width - 8, rowH - 4);
            }
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(options.get(i), width - 16),
                    x + 7, rowY + 3,
                    hovered ? this.theme.textPrimary() : this.theme.textDim(), false);
        }
    }

    private int lineMenuWidth(List<String> options) {
        int max = this.font.width("选择产线");
        for (String option : options) {
            max = Math.max(max, this.font.width(option));
        }
        return Math.max(lineButtonW(), max + 24);
    }

    private boolean clickLineMenu(double mouseX, double mouseY) {
        if (!this.lineMenuOpen) {
            return false;
        }
        List<String> options = lineOptions();
        int x = lineButtonX();
        int y = this.layout.headerY + FactoryLayout.HEADER_H + 2;
        int width = lineMenuWidth(options);
        int rowH = 16;
        if (mouseX >= x && mouseX < x + width
                && mouseY >= y && mouseY < y + 3 + options.size() * rowH) {
            int index = (int) ((mouseY - y - 3) / rowH);
            if (index >= 0 && index < options.size()) {
                String selected = options.get(index);
                this.lineFilter = selected;
                rememberedLineFilter = selected;
                this.firstRow = 0;
                updateColumnWidths();
            }
            this.lineMenuOpen = false;
            return true;
        }
        this.lineMenuOpen = false;
        return false;
    }

    /** 左区表格。*/
    private void drawTable(GuiGraphics graphics) {
        FactoryLayout l = this.layout;
        int headerY = l.tableHeaderY + (FactoryLayout.TABLE_HEADER_H - 9) / 2 + 1;
        String[] headers = { "机器", "状态", "输入", "输出" };
        // 按「显示顺。。逻辑列下标」取表头，不能假设显示的就是。N 列：
        // 窄屏时丢的是输入/输出，第三列可能是速率。
        for (int slot = 0; slot < l.columnCount; slot++) {
            int id = l.columnId(slot);
            graphics.drawString(this.font, headers[id], l.columnX(id) + 4, headerY,
                    this.theme.textDim(), false);
        }

        List<FactorySnapshotBuilder.MachineView> table = tableMachines();
        int total = table.size();
        int shown = 0;
        int y = l.rowTop;
        for (int i = this.firstRow; i < total; i++) {
            FactorySnapshotBuilder.MachineView machine = table.get(i);
            int rowHeight = rowHeight(machine);
            if (y + rowHeight > l.footerY) {
                break;
            }
            drawRow(graphics, machine, y, isExpanded(machine));
            y += rowHeight;
            shown++;
        }

        if (total == 0) {
            String message;
            if (this.lineFilter != null) {
                message = "该产线没有机器";
            } else {
                message = "先选择一条产线";
            }
            graphics.drawString(this.font, message, l.tableX, l.footerY + 3,
                    this.theme.textDim(), false);
            drawTableFrame(graphics);
            return;
        }

        int ready = 0;
        int unloaded = 0;
        int missing = 0;
        int binned = 0;
        for (FactorySnapshotBuilder.MachineView machine : table) {
            if (machine.binned()) {
                binned++;
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
        String footer = "共 " + total + " 台 · 就绪 " + ready + " · 未载 " + unloaded
                + " · 失联 " + missing + " · 回收站 " + binned;
        if (this.maxFirstRow() > 0) {
            footer += " · " + (this.firstRow + 1) + "-" + (this.firstRow + shown);
        }
        graphics.drawString(this.font, footer, l.tableX, l.footerY + 3, this.theme.textDim(), false);
        drawTableFrame(graphics);
    }

    private void drawTableFrame(GuiGraphics graphics) {
        FactoryLayout l = this.layout;
        int tableBottom = l.footerY + FactoryLayout.FOOTER_H;
        drawTableFrame(graphics, l.leftX, l.tableHeaderY, l.leftW,
                tableBottom - l.tableHeaderY);
    }

    private void drawRow(GuiGraphics graphics, FactorySnapshotBuilder.MachineView machine,
            int y, boolean expanded) {
        FactoryLayout l = this.layout;
        int textY = y + (FactoryLayout.ROW_H - 9) / 2;
        Status status = statusOf(machine);

        NineSlice.card(graphics, l.leftX + 2, y, l.leftW - 4,
                FactoryLayout.ROW_H - 1);
        // 1px 状态条：从卡片顶部内缘贯穿到底部内。
        NineSlice.fill(graphics, status.strip(), l.leftX + 3, y + 1, 1,
                FactoryLayout.ROW_H - 2);

        int iconX = l.columnX(FactoryLayout.COL_NAME) + 5;
        NineSlice.slot(graphics, iconX - 1, y + 4, FactoryLayout.CELL,
                FactoryLayout.CELL);
        ItemStack icon = blockStack(machine.blockId());
        graphics.renderItem(icon.isEmpty() ? new ItemStack(Items.BARRIER) : icon,
                iconX, y + 5);

        String name = blockName(machine.blockId());
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
                this.theme.textDim(), false);

        int stateX = l.columnX(FactoryLayout.COL_STATE) + 4;
        NineSlice.icon(graphics, status.lamp(), stateX, textY + 2,
                GuiTextures.STATUS_LAMP_SIZE);
        String statusLabel = status.text();
        int statusWidth = l.columnW(FactoryLayout.COL_STATE) - 14;
        graphics.drawString(this.font,
                this.font.plainSubstrByWidth(statusLabel, statusWidth),
                stateX + 9, textY, status.color(), false);

        if (l.hasColumn(FactoryLayout.COL_INPUT)) {
            drawRuleIcons(graphics, machine.inputs(), machine.inputRates(),
                    l.columnX(FactoryLayout.COL_INPUT) + 2, y + 4,
                    l.columnW(FactoryLayout.COL_INPUT) - 4);
        }
        if (l.hasColumn(FactoryLayout.COL_OUTPUT)) {
            drawRuleIcons(graphics, machine.outputs(), machine.outputRates(),
                    l.columnX(FactoryLayout.COL_OUTPUT) + 2, y + 4,
                    l.columnW(FactoryLayout.COL_OUTPUT) - 4 - EXPAND_BUTTON - 2);
        }
        drawExpandButton(graphics, y, expanded);
        if (expanded) {
            drawExpandedRules(graphics, machine, y);
        }
    }

    private int rowHeight(FactorySnapshotBuilder.MachineView machine) {
        return FactoryLayout.ROW_H + (isExpanded(machine) ? EXPANDED_H : 0);
    }

    private boolean isExpanded(FactorySnapshotBuilder.MachineView machine) {
        return this.expandedMachines.contains(machine.ref());
    }

    private void drawExpandButton(GuiGraphics graphics, int y, boolean expanded) {
        FactoryLayout l = this.layout;
        int x = l.leftX + l.leftW - EXPAND_BUTTON - 4;
        int buttonY = y + 6;
        NineSlice.button(graphics, x, buttonY, EXPAND_BUTTON, EXPAND_BUTTON);
        int cx = x + EXPAND_BUTTON / 2;
        int cy = buttonY + EXPAND_BUTTON / 2;
        if (expanded) {
            for (int i = 0; i < 3; i++) {
                NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK,
                        cx - 2 + i, cy - 2 + i, 5 - i * 2, 1);
            }
        } else {
            for (int i = 0; i < 3; i++) {
                NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK,
                        cx - 2 + i, cy - 2 + i, 1, 5 - i * 2);
            }
        }
    }

    private void drawExpandedRules(GuiGraphics graphics,
            FactorySnapshotBuilder.MachineView machine, int y) {
        FactoryLayout l = this.layout;
        int x = l.leftX + 2;
        int top = y + FactoryLayout.ROW_H - 1;
        int width = l.leftW - 4;
        NineSlice.fill(graphics, GuiTextures.ROW_ALT, x, top, width, EXPANDED_H);
        NineSlice.fill(graphics, GuiTextures.DIVIDER, x, top, width, 1);
        NineSlice.fill(graphics, GuiTextures.DIVIDER, x, top + EXPANDED_H - 1, width, 1);

        int labelX = l.tableX + 4;
        int slotX = labelX + 34;
        int rowOne = top + 5;
        int rowTwo = top + 25;
        drawExpandedRuleRow(graphics, "输入", machine.inputs(), labelX, slotX, rowOne);
        drawExpandedRuleRow(graphics, "输出", machine.outputs(), labelX, slotX, rowTwo);
    }

    private void drawExpandedRuleRow(GuiGraphics graphics, String label,
            List<ResourceSpec> rules, int labelX, int slotX, int y) {
        graphics.drawString(this.font, label, labelX, y + 4,
                this.theme.textDim(), false);
        int shown = Math.min(3, rules.size());
        for (int i = 0; i < 3; i++) {
            int x = slotX + i * 20;
            NineSlice.slot(graphics, x, y, FactoryLayout.CELL, FactoryLayout.CELL);
            if (i < shown) {
                drawResourceIcon(graphics, rules.get(i).key(), x + 1, y + 1);
            }
        }
        if (rules.size() > 3) {
            graphics.drawString(this.font, "+" + (rules.size() - 3),
                    slotX + 3 * 20 + 2, y + 4, this.theme.textDim(), false);
        }
    }

    private record TableHit(FactorySnapshotBuilder.MachineView machine, int y, int height) {
    }

    @Nullable
    private TableHit tableHitAt(double mouseY) {
        FactoryLayout l = this.layout;
        if (mouseY < l.rowTop || mouseY >= l.footerY) {
            return null;
        }
        List<FactorySnapshotBuilder.MachineView> table = tableMachines();
        int y = l.rowTop;
        for (int i = this.firstRow; i < table.size(); i++) {
            FactorySnapshotBuilder.MachineView machine = table.get(i);
            int height = rowHeight(machine);
            if (y + height > l.footerY) {
                return null;
            }
            if (mouseY >= y && mouseY < y + height) {
                return new TableHit(machine, y, height);
            }
            y += height;
        }
        return null;
    }

    private boolean clickMachineExpand(double mouseX, double mouseY) {
        FactoryLayout l = this.layout;
        int buttonX = l.leftX + l.leftW - EXPAND_BUTTON - 4;
        if (mouseX < buttonX || mouseX >= buttonX + EXPAND_BUTTON) {
            return false;
        }
        TableHit hit = tableHitAt(mouseY);
        if (hit == null
                || mouseY < hit.y() + 6
                || mouseY >= hit.y() + 6 + EXPAND_BUTTON) {
            return false;
        }
        MachineRef ref = hit.machine().ref();
        if (!this.expandedMachines.remove(ref)) {
            this.expandedMachines.add(ref);
        }
        this.firstRow = Math.max(0, Math.min(this.firstRow, maxFirstRow()));
        return true;
    }

    private void drawRuleIcons(GuiGraphics graphics, List<ResourceSpec> rules,
            List<Long> rates, int x, int y, int maxWidth) {
        if (rules.isEmpty()) {
            NineSlice.slot(graphics, x, y, FactoryLayout.CELL, FactoryLayout.CELL);
            graphics.drawString(this.font, "—", x + 6, y + 4,
                    this.theme.textDim(), false);
            return;
        }
        int cursor = x;
        int shown = 0;
        int limit = Math.min(rules.size(), 3);
        while (shown < limit) {
            String rateText = countText(shown < rates.size() ? rates.get(shown) : 0L) + "/s";
            int entryWidth = FactoryLayout.CELL + 2 + this.font.width(rateText) + 6;
            if (shown > 0 && cursor + entryWidth > x + maxWidth) {
                break;
            }
            NineSlice.slot(graphics, cursor, y, FactoryLayout.CELL,
                    FactoryLayout.CELL);
            drawResourceIcon(graphics, rules.get(shown).key(), cursor + 1, y + 1);
            graphics.drawString(this.font, rateText, cursor + FactoryLayout.CELL + 2,
                    y + 5, this.theme.textDim(), false);
            cursor += entryWidth;
            shown++;
        }
        if (rules.size() > shown) {
            graphics.drawString(this.font, "+" + (rules.size() - shown),
                    cursor, y + 4, this.theme.textDim(), false);
        }
    }

    private record Status(String text, int color, ResourceLocation strip,
            ResourceLocation lamp) {
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
            case INPUT_OK -> new Status("可输入", this.theme.statusRun(),
                    GuiTextures.STATUS_STRIP_RUN, GuiTextures.STATUS_LAMP_RUN);
            case INPUT_BLOCKED -> new Status("输入堵塞 "
                    + (machine.sinceInputMoveTicks() < 0L ? "—"
                            : durationText(machine.sinceInputMoveTicks())),
                    this.theme.statusWarn(), GuiTextures.STATUS_STRIP_WARN,
                    GuiTextures.STATUS_LAMP_WARN);
            default -> new Status("未知", this.theme.statusIdle(),
                    GuiTextures.STATUS_STRIP_IDLE, GuiTextures.STATUS_LAMP_IDLE);
        };
    }

    private Status offlineStatus(FactorySnapshotBuilder.MachineView machine) {
        if (machine.detached()) {
            return new Status("回收站", this.theme.statusIdle(),
                    GuiTextures.STATUS_STRIP_IDLE, GuiTextures.STATUS_LAMP_IDLE);
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

    private static String durationText(long ticks) {
        long seconds = Math.max(0L, ticks) / 20L;
        if (seconds < 60L) {
            return seconds + "s";
        }
        long minutes = seconds / 60L;
        if (minutes < 60L) {
            return minutes + "m";
        }
        return (minutes / 60L) + "h";
    }

    private static ItemStack blockStack(ResourceLocation blockId) {
        if (blockId == null) {
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.BLOCK.get(blockId).asItem();
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    private static String blockName(ResourceLocation blockId) {
        if (blockId == null) {
            return "未知机器";
        }
        var block = BuiltInRegistries.BLOCK.get(blockId);
        return block.asItem() == Items.AIR ? blockId.toString() : block.getName().getString();
    }

    private void drawResourceIcon(GuiGraphics graphics, ResourceKey key, int x, int y) {
        graphics.renderItem(iconStack(key), x, y);
    }

    /** 资源对应的界面图标；JEI 查询也复用这一份。*/
    private static ItemStack iconStack(ResourceKey key) {
        return switch (key.kind()) {
            case ITEM -> {
                Item item = BuiltInRegistries.ITEM.get(key.id());
                yield item == Items.AIR ? new ItemStack(Items.BARRIER) : new ItemStack(item);
            }
            case FLUID -> {
                var fluid = BuiltInRegistries.FLUID.get(key.id());
                Item bucket = fluid.getBucket();
                yield new ItemStack(bucket == Items.AIR ? Items.BUCKET : bucket);
            }
            case ENERGY -> new ItemStack(Items.REDSTONE);
            default -> new ItemStack(Items.BARRIER);
        };
    }

    private void drawCount(GuiGraphics graphics, long count, int right, int bottom) {
        String text = countText(count);
        float scale = 0.75F;
        float width = this.font.width(text) * scale;
        float height = this.font.lineHeight * scale;
        graphics.pose().pushPose();
        graphics.pose().translate(right - width - 1.0F, bottom - height, 300.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(this.font, text, 0, 0, 0xFFFFFFFF, true);
        graphics.pose().popPose();
    }

    private static String countText(long count) {
        if (count < 1000L) {
            return Long.toString(count);
        }
        if (count < 1_000_000L) {
            return String.format(java.util.Locale.ROOT, "%.1fk", count / 1000.0D);
        }
        return String.format(java.util.Locale.ROOT, "%.1fM", count / 1_000_000.0D);
    }

    private static String resourceName(ResourceKey key) {
        if (key.kind() == ResourceKind.ENERGY) {
            return "FE";
        }
        if (key.kind() == ResourceKind.FLUID) {
            var fluid = BuiltInRegistries.FLUID.get(key.id());
            Item bucket = fluid.getBucket();
            return bucket == Items.AIR ? key.id().toString()
                    : new ItemStack(bucket).getHoverName().getString();
        }
        if (key.kind() == ResourceKind.ITEM) {
            Item item = BuiltInRegistries.ITEM.get(key.id());
            return item == Items.AIR ? key.id().toString()
                    : new ItemStack(item).getHoverName().getString();
        }
        return key.id().toString();
    }

    private static String unit(ResourceKind kind) {
        return switch (kind) {
            case ITEM -> "个";
            case FLUID -> "mB";
            case ENERGY -> "FE";
            case CHEMICAL -> "";
        };
    }

    private static String faceName(ResourceSpec spec) {
        return spec.face() == null ? "any" : spec.face().getName();
    }

    /** 右区文字与内容。*/
    private void drawRightPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        FactoryLayout l = this.layout;
        String[] tabs = { "仓库", "机器" };
        int tabWidth = l.rightW / FactoryLayout.TAB_COUNT;
        for (int i = 0; i < tabs.length; i++) {
            int x = l.rightX + i * tabWidth;
            graphics.drawString(this.font, tabs[i],
                    x + (tabWidth - this.font.width(tabs[i])) / 2,
                    l.tabY + (FactoryLayout.HEADER_H - 9) / 2,
                    i == this.activeTab ? this.theme.textPrimary() : this.theme.textDim(),
                    false);
        }

        if (this.activeTab == TAB_STORAGE) {
            drawStorageHover(graphics, mouseX, mouseY);
            drawStorageEntries(graphics);
        } else {
            drawMachineLibrary(graphics);
        }

        graphics.drawString(this.font, "合成", l.craftX, l.craftLabelY, this.theme.textDim(), false);
        graphics.drawString(this.font, "背包", l.invX, l.invLabelY, this.theme.textDim(), false);
    }

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

    private void drawStorageEntries(GuiGraphics graphics) {
        FactoryLayout l = this.layout;
        int centerX = l.gridX;
        int centerY = l.gridY + l.gridRows * FactoryLayout.CELL / 2 - 4;
        if (!this.storageReceived) {
            graphics.drawString(this.font, "同步中…", centerX, centerY,
                    this.theme.textDim(), false);
            return;
        }
        if (!this.storageOnline) {
            graphics.drawString(this.font, "仓库离线", centerX, centerY,
                    this.theme.error(), false);
            return;
        }
        if (this.storageRepo.all().isEmpty()) {
            graphics.drawString(this.font, "仓库是空的", centerX, centerY,
                    this.theme.textDim(), false);
            return;
        }
        if (this.filteredStorageEntries.isEmpty()) {
            graphics.drawString(this.font, "没有匹配", centerX, centerY,
                    this.theme.textDim(), false);
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

    private void drawStorageEntry(GuiGraphics graphics, FactoryStorageS2C.Entry entry,
            int x, int y) {
        drawResourceIcon(graphics, entry.key(), x + 1, y + 1);
        drawCount(graphics, entry.count(), x + FactoryLayout.CELL - 1,
                y + FactoryLayout.CELL - 1);
    }



    private void drawTooltips(GuiGraphics graphics, int mouseX, int mouseY) {
        if (this.lineMenuOpen) {
            return;
        }
        FactoryLayout l = this.layout;
        if (this.searchBox != null && !this.searchBox.isFocused()
                && mouseX >= l.rightX + 4 && mouseX < l.rightX + l.rightW - 4
                && mouseY >= l.searchY && mouseY < l.searchY + FactoryLayout.SEARCH_H) {
            graphics.renderComponentTooltip(this.font, List.of(
                    Component.literal("@ 按模组"),
                    Component.literal("# 按标签"),
                    Component.literal("$ 按 tooltip"),
                    Component.literal("* 按注册名"),
                    Component.literal("| 或；空格 与")), mouseX, mouseY);
            return;
        }
        if (this.activeTab == TAB_STORAGE
                && mouseX >= l.gridX && mouseX < l.gridX + FactoryLayout.GRID_COLS * FactoryLayout.CELL
                && mouseY >= l.gridY && mouseY < l.gridY + l.gridRows * FactoryLayout.CELL) {
            int col = (mouseX - l.gridX) / FactoryLayout.CELL;
            int row = (mouseY - l.gridY) / FactoryLayout.CELL;
            int index = (this.storageFirstRow + row) * FactoryLayout.GRID_COLS + col;
            if (index >= 0 && index < this.filteredStorageEntries.size()) {
                FactoryStorageS2C.Entry entry = this.filteredStorageEntries.get(index);
                graphics.renderComponentTooltip(this.font, List.of(
                        Component.literal(resourceName(entry.key())),
                        Component.literal(entry.key().id().toString()),
                        Component.literal(entry.count() + " "
                                + unit(entry.key().kind()))), mouseX, mouseY);
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

        if (mouseX >= l.leftX && mouseX < l.rightX - FactoryLayout.GAP
                && mouseY >= l.rowTop && mouseY < l.footerY) {
            TableHit hit = tableHitAt(mouseY);
            if (hit == null) {
                return;
            }
            FactorySnapshotBuilder.MachineView machine = hit.machine();
            if (l.hasColumn(FactoryLayout.COL_INPUT)
                    && inColumn(l, FactoryLayout.COL_INPUT, mouseX)) {
                drawRuleTooltip(graphics, "输入", machine.inputs(),
                        machine.inputRates(), mouseX, mouseY);
                return;
            }
            if (l.hasColumn(FactoryLayout.COL_OUTPUT)
                    && inColumn(l, FactoryLayout.COL_OUTPUT, mouseX)) {
                drawRuleTooltip(graphics, "输出", machine.outputs(),
                        machine.outputRates(), mouseX, mouseY);
                return;
            }
        }
    }

    private boolean inColumn(FactoryLayout layout, int column, double mouseX) {
        return mouseX >= layout.columnX(column)
                && mouseX < layout.columnX(column) + layout.columnW(column);
    }

    private void drawRuleTooltip(GuiGraphics graphics, String title,
            List<ResourceSpec> rules, List<Long> rates, int mouseX, int mouseY) {
        if (rules.isEmpty()) {
            return;
        }
        List<Component> lines = new java.util.ArrayList<>();
        lines.add(Component.literal(title));
        int shown = Math.min(rules.size(), 10);
        for (int i = 0; i < shown; i++) {
            ResourceSpec spec = rules.get(i);
            long rate = i < rates.size() ? rates.get(i) : 0L;
            lines.add(Component.literal(resourceName(spec.key()) + " · "
                    + faceName(spec) + " · " + countText(rate) + "/s"));
        }
        if (rules.size() > shown) {
            lines.add(Component.literal("还有 " + (rules.size() - shown) + " 条"));
        }
        graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
    }

    private void drawMachineTooltip(GuiGraphics graphics,
            FactorySnapshotBuilder.MachineView machine, int mouseX, int mouseY) {
        List<Component> lines = new java.util.ArrayList<>();
        lines.add(Component.literal(blockName(machine.blockId())));
        lines.add(Component.literal(statusText(machine)));
        lines.add(Component.literal(machine.ref().dimension().location()
                + " " + machine.ref().pos().toShortString()));
        lines.add(Component.literal(machine.lineNames().isEmpty()
                ? "未编线" : "产线 " + String.join("/", machine.lineNames())));
        lines.add(Component.literal("规则 " + machine.ruleCount() + " 条"));
        graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
    }

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
        ItemStack icon = blockStack(machine.blockId());
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
        graphics.drawString(this.font,
                this.font.plainSubstrByWidth(blockName(machine.blockId()), nameWidth),
                textX, nameY, CARD_NAME, false);
        graphics.drawString(this.font, statusText, statusX, nameY, status.color(), false);

        String lines = machine.lineNames().isEmpty()
                ? "未编线" : String.join(" / ", machine.lineNames());
        String rules = "规则 " + machine.ruleCount();
        int rulesWidth = this.font.width(rules);
        int lineWidth = Math.max(12, right - rulesWidth - 6 - textX);
        graphics.drawString(this.font, this.font.plainSubstrByWidth(lines, lineWidth),
                textX, lineY, CARD_LINE, false);
        graphics.drawString(this.font, rules, right - rulesWidth, lineY, CARD_META, false);
    }

    // ------------------------------------------------------------------
    // 槽位
    // ------------------------------------------------------------------

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
        // 悬停高亮由贴图接管，不要叠一层原版白。
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 不画原版标题与「物品栏」字。
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
    private FactoryStorageS2C.Entry storageEntryAt(double mouseX, double mouseY) {
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

    private void sendStorageGesture(@Nullable ResourceKey key, Gesture gesture) {
        PacketDistributor.sendToServer(new FactoryStorageActionC2S(
                this.menu.containerId, key, gesture));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        FactoryLayout l = this.layout;
        if (this.searchBox != null && this.searchBox.isFocused()
                && !this.searchBox.isMouseOver(mouseX, mouseY)) {
            // 点击别处先退出搜索焦点，否则 R/U 会被 EditBox 当成输入吞掉。
            this.searchBox.setFocused(false);
        }
        if (clickLineMenu(mouseX, mouseY)) {
            return true;
        }
        if (button == 0 && mouseY >= l.headerY + 3
                && mouseY < l.headerY + 3 + FactoryLayout.TAB_H
                && mouseX >= lineButtonX()
                && mouseX < lineButtonX() + lineButtonW()) {
            this.lineMenuOpen = !this.lineMenuOpen;
            return true;
        }
        // 。drawHeader 的按钮位置保持一致：关闭按钮。tab 条左边，不在面板右缘。
        int closeX = l.rightX - 6 - CLOSE_SIZE;
        if (mouseX >= closeX && mouseX < closeX + CLOSE_SIZE
                && mouseY >= l.headerY + 4 && mouseY < l.headerY + 4 + CLOSE_SIZE) {
            this.onClose();
            return true;
        }
        int tabWidth = l.rightW / FactoryLayout.TAB_COUNT;
        if (mouseY >= l.tabY && mouseY < l.tabY + FactoryLayout.HEADER_H
                && mouseX >= l.rightX && mouseX < l.rightX + l.rightW) {
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
        if (clickMachineExpand(mouseX, mouseY)) {
            return true;
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

    private int clearButtonX() {
        return this.layout.rightX + this.layout.rightW - 14;
    }

    /**
     * 搜索框：纯色。+ 1px 外框 + 1px 内凹阴影。
     *
     * <p>不用九宫。field。4px 高的框会。6×6 贴图的内斜边拉成不规则色块。这里全部用 1×1
     * 纯色拉伸，边框粗细始终是 1px。/p>
     */
    private void drawSearchField(GuiGraphics graphics) {
        FactoryLayout l = this.layout;
        int x = l.rightX + 4;
        int y = l.searchY;
        int w = l.rightW - 8;
        int h = FactoryLayout.SEARCH_H;

        NineSlice.fill(graphics, GuiTextures.SEARCH_BG, x + 2, y + 2, w - 4, h - 4);

        // 外框
        NineSlice.fill(graphics, GuiTextures.SEARCH_BORDER, x, y, w, 1);
        NineSlice.fill(graphics, GuiTextures.SEARCH_BORDER, x, y + h - 1, w, 1);
        NineSlice.fill(graphics, GuiTextures.SEARCH_BORDER, x, y, 1, h);
        NineSlice.fill(graphics, GuiTextures.SEARCH_BORDER, x + w - 1, y, 1, h);

        // 内凹：上/左阴影，。右高。
        NineSlice.fill(graphics, GuiTextures.SEARCH_SHADOW, x + 1, y + 1, w - 2, 1);
        NineSlice.fill(graphics, GuiTextures.SEARCH_SHADOW, x + 1, y + 1, 1, h - 2);
        NineSlice.fill(graphics, GuiTextures.SEARCH_HIGHLIGHT, x + 1, y + h - 2, w - 2, 1);
        NineSlice.fill(graphics, GuiTextures.SEARCH_HIGHLIGHT, x + w - 2, y + 1, 1, h - 2);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.searchBox != null && this.searchBox.isFocused()) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                this.searchBox.setFocused(false);
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER) {
                this.searchBox.setFocused(false);
                return true;
            }
            // 编辑键（退格、方向、Ctrl+A/C/V 等）交给 EditBox；剩下的一律吞掉。
            // 否则 E 会被 AbstractContainerScreen 当成“关闭容器”，Q/数字键会丢物。切快捷栏。
            if (this.searchBox.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.searchBox != null && this.searchBox.isFocused()
                && codePoint == ' ' && this.searchBox.getValue().isEmpty()) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public void removed() {
        if (this.searchBox != null) {
            rememberedSearch = this.searchBox.getValue();
        }
        rememberedLineFilter = this.lineFilter;
        super.removed();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
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
     * JEI 。R/U 查询目标：一个物品和它在屏幕上的格子区域。
     *
     * @param stack 物品
     * @param area  可点击区。
     */
    public record JeiTarget(ItemStack stack, Rect2i area) {
    }

    /**
     * 当前鼠标下的 JEI 查询目标。
     *
     * <p>覆盖左表机器/输入/输出、仓库格、机器库卡片和玩家背包槽位。只返回物品。
     * 流体图标按桶处理，能量按红石图标处理，和界面图标保持一致。/p>
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
                    ItemStack stack = blockStack(machine.blockId());
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

                int iconX = l.columnX(FactoryLayout.COL_NAME) + 4;
                if (inside(mouseX, mouseY, iconX, y + 4,
                        FactoryLayout.CELL, FactoryLayout.CELL)) {
                    ItemStack stack = blockStack(machine.blockId());
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
                            l.columnW(FactoryLayout.COL_OUTPUT) - 4 - EXPAND_BUTTON - 2,
                            mouseX, mouseY);
                    if (target != null) {
                        return target;
                    }
                }
            }
        }
        return null;
    }

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
            String rate = countText(shown < rates.size() ? rates.get(shown) : 0L) + "/s";
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

    private static boolean inside(double mouseX, double mouseY,
            int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width
                && mouseY >= y && mouseY < y + height;
    }

    /** 当前布局（调试与后续阶段用）。*/
    public FactoryLayout layout() {
        return this.layout;
    }
}

