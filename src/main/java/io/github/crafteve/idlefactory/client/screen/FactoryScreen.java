package io.github.crafteve.idlefactory.client.screen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.client.ClientPacketHandler;
import io.github.crafteve.idlefactory.client.theme.GuiTheme;
import io.github.crafteve.idlefactory.client.widget.ItemGrid;
import io.github.crafteve.idlefactory.client.widget.SearchEditBox;
import io.github.crafteve.idlefactory.menu.FactoryMenu;
import io.github.crafteve.idlefactory.network.payload.StorageSnapshotS2C;

import appeng.api.stacks.AEKey;
import appeng.client.gui.me.search.RepoSearch;
import appeng.menu.me.common.GridInventoryEntry;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 工厂工作台界面。
 *
 * <p>面板尺寸不写死，按当前 GUI 逻辑尺寸自适应：占满可用区域，四周只留 {@link #MARGIN} 的边距。
 * 这样 GUI scale 调大（逻辑尺寸变小）时不会溢出，调小（逻辑尺寸变大）时也不会缩成一小块。</p>
 *
 * <p>右侧是 176px 宽的 AE2 终端风格存储栏：顶部搜索框（复用 AE2 的 {@link RepoSearch}）、
 * 奇点/网络状态、9 列物品网格，底部是玩家背包（3×9 主背包 + 1×9 快捷栏，真实 {@link Slot}）。
 * 库存数据来自服务端的 {@link StorageSnapshotS2C}，客户端只做展示与过滤。</p>
 *
 * <p>所有颜色来自 {@link GuiTheme}，切换主题时这里不用改代码。</p>
 */
public class FactoryScreen extends AbstractContainerScreen<FactoryMenu> {
    /** 面板四周留白（GUI 逻辑像素）。 */
    private static final int MARGIN = 8;
    /** 最小面板宽度，防止极端小窗口下面板消失。 */
    private static final int MIN_WIDTH = 320;
    /** 最小面板高度。 */
    private static final int MIN_HEIGHT = 240;

    /** 标题左侧标记的 x 偏移。 */
    private static final int TITLE_X = 20;
    /** 标题的 y 偏移。 */
    private static final int TITLE_Y = 8;
    /** 标题栏高度，下方画分隔线。 */
    private static final int HEADER_HEIGHT = 28;

    /** 右侧存储栏宽度。 */
    private static final int STORAGE_WIDTH = 176;
    /** 存储栏与面板右/下边缘的距离。 */
    private static final int STORAGE_MARGIN = 12;
    /** 存储栏左内边距（网格 + 滚动条整体居中后的左侧留白）。 */
    private static final int STORAGE_INSET = 4;
    /** 搜索框距存储栏顶部的距离。 */
    private static final int SEARCH_TOP = 8;
    /** 搜索框高度。 */
    private static final int SEARCH_HEIGHT = 14;
    /** 状态文字距存储栏顶部的距离。 */
    private static final int STATUS_TOP = 26;
    /** 网格距存储栏顶部的距离。 */
    private static final int GRID_TOP = 38;
    /** 背包距存储栏底部的距离。 */
    private static final int INVENTORY_BOTTOM_PADDING = 9;
    /** 主背包与快捷栏之间、网格与背包之间的间距。 */
    private static final int INVENTORY_GAP = 4;

    /** 当前主题；默认跟随 {@link GuiTheme#current()}（M1 是 DARK）。 */
    private GuiTheme theme = GuiTheme.current();

    /** 搜索框；每次 {@code init()} 重建（尺寸变化）。 */
    @Nullable
    private SearchEditBox searchBox;

    /** 物品网格（滚动 / 悬停 / 选中都在里面）。 */
    private final ItemGrid itemGrid = new ItemGrid();

    /** AE2 搜索语法解析器（@mod / #tag / *id / 拼音模组适配都继承自它）。 */
    private final RepoSearch repoSearch = new RepoSearch();
    /** key → 稳定 serial，避免过滤时 GridInventoryEntry 的缓存串味。 */
    private final Map<AEKey, Long> serials = new HashMap<>();
    private long nextSerial = 1L;

    /** 最近一次服务端库存快照。 */
    private StorageSnapshotS2C snapshot = ClientPacketHandler.snapshot();
    /** 上一次过滤用的搜索词。 */
    private String lastQuery = "";

    /** 当前鼠标位置（renderSlot 里判断悬停用）。 */
    private int mouseX;
    private int mouseY;

    public FactoryScreen(FactoryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        // 先给一个默认值；真正的尺寸在 init() 里按屏幕算。
        this.imageWidth = 480;
        this.imageHeight = 300;
    }

    @Override
    protected void init() {
        // 自适应：占满屏幕逻辑尺寸，只留边距。
        this.imageWidth = Math.max(MIN_WIDTH, this.width - MARGIN * 2);
        this.imageHeight = Math.max(MIN_HEIGHT, this.height - MARGIN * 2);
        // 父类会用新的 imageWidth/imageHeight 重算 leftPos/topPos。
        super.init();

        // 重建搜索框（resize 时旧控件已被 clearWidgets 移除），保留关键字
        String query = this.searchBox != null ? this.searchBox.getValue() : this.lastQuery;
        this.searchBox = this.addRenderableWidget(new SearchEditBox(
                this.font,
                searchBoxTextX(), searchBoxTextY(), searchBoxTextWidth(), 10,
                this.theme,
                Component.translatable("screen.idlefactory.search_hint")));
        this.searchBox.setValue(query);
        this.lastQuery = query;

        this.itemGrid.setBounds(gridX(), gridTop(), gridRows());
        layoutSlots();
        refreshFilter();
    }

    /**
     * 按当前面板高度重排背包槽位。
     *
     * <p>1.21.1 的 {@link Slot#x}/{@link Slot#y} 是 final，只能按新坐标重建 Slot；这里只替换
     * 客户端菜单里的槽位对象，容器与容器索引保持一致，服务端槽位完全不受影响。</p>
     */
    private void layoutSlots() {
        int count = Math.min(FactoryMenu.PLAYER_SLOTS, this.menu.slots.size());
        for (int i = 0; i < count; i++) {
            Slot old = this.menu.slots.get(i);
            int x;
            int y;
            if (i < 9) {
                x = gridX() + i * ItemGrid.CELL_SIZE + 1;
                y = hotbarY() + 1;
            } else {
                int index = i - 9;
                x = gridX() + index % 9 * ItemGrid.CELL_SIZE + 1;
                y = mainInvY() + index / 9 * ItemGrid.CELL_SIZE + 1;
            }

            Slot slot = new Slot(old.container, old.getContainerSlot(), x, y);
            slot.index = i;
            this.menu.slots.set(i, slot);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;

        // 搜索词变化时重新过滤（输入法/粘贴/退格都走这条路）
        if (this.searchBox != null && !this.searchBox.getValue().equals(this.lastQuery)) {
            refreshFilter();
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // 存储网格是自绘的，tooltip 自己画；背包槽位的 tooltip 交给父类
        ItemStack hoveredStorage = getHoveredStorageStack(mouseX, mouseY);
        if (hoveredStorage != null) {
            guiGraphics.renderTooltip(this.font, hoveredStorage, mouseX, mouseY);
        }
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        int w = this.imageWidth;
        int h = this.imageHeight;

        // 面板底色 + 1px 细边框
        guiGraphics.fill(x, y, x + w, y + h, this.theme.panelBg());
        guiGraphics.fill(x, y, x + w, y + 1, this.theme.panelBorder());
        guiGraphics.fill(x, y + h - 1, x + w, y + h, this.theme.panelBorder());
        guiGraphics.fill(x, y, x + 1, y + h, this.theme.panelBorder());
        guiGraphics.fill(x + w - 1, y, x + w, y + h, this.theme.panelBorder());

        // 标题栏分隔线
        guiGraphics.fill(x + 1, y + HEADER_HEIGHT, x + w - 1, y + HEADER_HEIGHT + 1, this.theme.panelBorder());

        // 标题左侧的强调色竖条
        guiGraphics.fill(x + TITLE_X, y + TITLE_Y, x + TITLE_X + 2, y + TITLE_Y + 9, this.theme.accent());

        renderStoragePanel(guiGraphics);

        // 存放在背景层画：这样玩家拖动物品时，悬浮物品会盖在网格上方
        renderStorageContents(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // 不调用父类：父类会画菜单标题和玩家物品栏标题，这版都用自定义布局。
        // 这里的坐标相对面板左上角。
        guiGraphics.drawString(this.font, "Idle Factory", TITLE_X + 8, TITLE_Y + 1, this.theme.accent(), false);
    }

    /**
     * 完全接管槽位绘制：主题色内凹格子 + 物品 + 数量/耐久。
     *
     * <p>物品不会被父类再画一遍。悬停直接用当前鼠标位置判断（父类的 {@code hoveredSlot}
     * 是渲染完当前槽位之后才赋值的，用它会有 1 帧延迟）。</p>
     */
    @Override
    protected void renderSlot(GuiGraphics guiGraphics, Slot slot) {
        boolean hovered = this.isHovering(slot.x, slot.y, 16, 16, this.mouseX, this.mouseY);
        ItemGrid.drawSlotBackground(guiGraphics, this.theme, slot.x - 1, slot.y - 1, hovered);

        ItemStack stack = slot.getItem();
        if (!stack.isEmpty()) {
            guiGraphics.renderItem(stack, slot.x, slot.y);
            guiGraphics.renderItemDecorations(this.font, stack, slot.x, slot.y);
        }
    }

    @Override
    protected void renderSlotHighlight(GuiGraphics guiGraphics, Slot slot, int mouseX, int mouseY, float partialTick) {
        // 悬停高亮已由 renderSlot 用主题色接管，不要再叠一层原版白框
    }

    /** 收到服务端库存快照时刷新界面。 */
    public void applySnapshot(StorageSnapshotS2C payload) {
        this.snapshot = payload;
        refreshFilter();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 搜索框有焦点时吞掉所有按键（ESC 除外），否则输入 e 会触发原版「关闭容器」
        if (this.searchBox != null && this.searchBox.isFocused() && keyCode != 256) {
            if (keyCode == 257) {
                this.searchBox.setFocused(false);
                return true;
            }
            String before = this.searchBox.getValue();
            this.searchBox.keyPressed(keyCode, scanCode, modifiers);
            if (!before.equals(this.searchBox.getValue())) {
                refreshFilter();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.searchBox != null && !this.searchBox.isMouseOver(mouseX, mouseY)) {
            this.searchBox.setFocused(false);
        }
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return this.itemGrid.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.itemGrid.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.itemGrid.mouseDragged(mouseY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.itemGrid.mouseReleased()) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    // ------------------------------------------------------------------
    // 存储栏布局
    // ------------------------------------------------------------------

    private int storageX() {
        return this.leftPos + this.imageWidth - STORAGE_WIDTH - STORAGE_MARGIN;
    }

    private int storageY() {
        return this.topPos + HEADER_HEIGHT + 10;
    }

    private int storageHeight() {
        return this.imageHeight - HEADER_HEIGHT - 10 - STORAGE_MARGIN;
    }

    private int gridX() {
        return storageX() + STORAGE_INSET;
    }

    private int gridTop() {
        return storageY() + GRID_TOP;
    }

    private int hotbarY() {
        return storageY() + storageHeight() - INVENTORY_BOTTOM_PADDING - ItemGrid.CELL_SIZE;
    }

    private int mainInvY() {
        return hotbarY() - INVENTORY_GAP - 3 * ItemGrid.CELL_SIZE;
    }

    private int gridRows() {
        return Math.max(1, (mainInvY() - INVENTORY_GAP - gridTop()) / ItemGrid.CELL_SIZE);
    }

    private int searchBoxTextX() {
        return gridX() + 3;
    }

    private int searchBoxTextY() {
        return storageY() + SEARCH_TOP + 3;
    }

    private int searchBoxTextWidth() {
        return ItemGrid.COLUMNS * ItemGrid.CELL_SIZE - 6;
    }

    // ------------------------------------------------------------------
    // 存储栏绘制
    // ------------------------------------------------------------------

    private void renderStoragePanel(GuiGraphics guiGraphics) {
        int x = storageX();
        int y = storageY();
        int w = STORAGE_WIDTH;
        int h = storageHeight();

        // 存储栏用面板底色，让深一档的格子（slotBg）显出层次
        guiGraphics.fill(x, y, x + w, y + h, this.theme.panelBg());
        guiGraphics.fill(x, y, x + w, y + 1, this.theme.panelBorder());
        guiGraphics.fill(x, y + h - 1, x + w, y + h, this.theme.panelBorder());
        guiGraphics.fill(x, y, x + 1, y + h, this.theme.panelBorder());
        guiGraphics.fill(x + w - 1, y, x + w, y + h, this.theme.panelBorder());

        // 搜索框背景（与网格同宽）
        int searchX = gridX();
        int searchY = y + SEARCH_TOP;
        int searchW = ItemGrid.COLUMNS * ItemGrid.CELL_SIZE;
        int searchH = SEARCH_HEIGHT;
        guiGraphics.fill(searchX, searchY, searchX + searchW, searchY + searchH, this.theme.slotBg());

        boolean focused = this.searchBox != null && this.searchBox.isFocused();
        int searchBorder = focused ? this.theme.accent() : this.theme.panelBorder();
        guiGraphics.fill(searchX, searchY, searchX + searchW, searchY + 1, searchBorder);
        guiGraphics.fill(searchX, searchY + searchH - 1, searchX + searchW, searchY + searchH, searchBorder);
        guiGraphics.fill(searchX, searchY, searchX + 1, searchY + searchH, searchBorder);
        guiGraphics.fill(searchX + searchW - 1, searchY, searchX + searchW, searchY + searchH, searchBorder);

        // 背包上方分隔线
        int dividerY = mainInvY() - INVENTORY_GAP;
        if (dividerY > gridTop()) {
            guiGraphics.fill(x + 1, dividerY, x + w - 1, dividerY + 1, this.theme.panelBorder());
        }
    }

    private void renderStorageContents(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int x = storageX();
        int y = storageY();

        guiGraphics.drawString(this.font, statusText(), gridX(), y + STATUS_TOP, statusColor(), false);

        this.itemGrid.render(guiGraphics, this.font, this.theme, mouseX, mouseY);

        if (this.itemGrid.isEmpty()) {
            int centerX = gridX() + this.itemGrid.width() / 2;
            int centerY = gridTop() + this.itemGrid.height() / 2 - 4;
            guiGraphics.drawCenteredString(this.font, emptyText(), centerX, centerY, this.theme.textDim());
        }
    }

    // ------------------------------------------------------------------
    // 过滤 / 状态
    // ------------------------------------------------------------------

    private void refreshFilter() {
        if (this.searchBox == null) {
            return;
        }
        this.lastQuery = this.searchBox.getValue();
        this.repoSearch.setSearchString(this.lastQuery);

        List<StorageSnapshotS2C.Entry> filtered = new ArrayList<>();
        for (StorageSnapshotS2C.Entry entry : this.snapshot.entries()) {
            long serial = this.serials.computeIfAbsent(entry.key(), key -> this.nextSerial++);
            GridInventoryEntry candidate = new GridInventoryEntry(serial, entry.key(), entry.amount(), 0, false);
            if (this.repoSearch.matches(candidate)) {
                filtered.add(entry);
            }
        }
        this.itemGrid.setEntries(List.copyOf(filtered));
    }

    private Component statusText() {
        if (!this.snapshot.equipped()) {
            return Component.translatable("screen.idlefactory.status.not_equipped");
        }
        if (!this.snapshot.bound()) {
            return Component.translatable("screen.idlefactory.status.no_network");
        }
        return Component.translatable("screen.idlefactory.status.equipped");
    }

    private int statusColor() {
        if (!this.snapshot.equipped()) {
            return this.theme.textDim();
        }
        return this.snapshot.bound() ? this.theme.accent() : this.theme.error();
    }

    private Component emptyText() {
        if (!this.snapshot.equipped()) {
            return Component.translatable("screen.idlefactory.empty.not_equipped");
        }
        if (!this.snapshot.bound()) {
            return Component.translatable("screen.idlefactory.empty.no_network");
        }
        if (this.snapshot.entries().isEmpty()) {
            return Component.translatable("screen.idlefactory.empty.no_stock");
        }
        return Component.translatable("screen.idlefactory.empty.no_match");
    }

    /**
     * 当前鼠标下的存储物品；没有时为 null。背包里的物品由原版 {@link Slot} + tooltip 处理。
     *
     * <p>供界面自身与 JEI 的 {@code IGuiContainerHandler} 复用。</p>
     */
    @Nullable
    public ItemStack getHoveredStorageStack(double mouseX, double mouseY) {
        StorageSnapshotS2C.Entry entry = this.itemGrid.hoveredEntry(mouseX, mouseY);
        return entry == null ? null : ItemGrid.toDisplayStack(entry.key());
    }

    /**
     * 当前鼠标下存储格子的屏幕矩形；没有时为 null。给 JEI 的 clickable ingredient 定位用。
     */
    @Nullable
    public Rect2i getHoveredStorageArea(double mouseX, double mouseY) {
        return this.itemGrid.hoveredArea(mouseX, mouseY);
    }
}
