package io.github.crafteve.idlefactory.client.screen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.client.ClientPacketHandler;
import io.github.crafteve.idlefactory.client.theme.GuiTheme;
import io.github.crafteve.idlefactory.client.widget.ItemGrid;
import io.github.crafteve.idlefactory.client.widget.SearchEditBox;
import io.github.crafteve.idlefactory.menu.FactoryMenu;
import io.github.crafteve.idlefactory.network.payload.StorageSnapshotS2C;

import appeng.api.config.SortDir;
import appeng.api.config.SortOrder;
import appeng.api.config.ViewItems;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.client.gui.me.common.Repo;
import appeng.client.gui.me.common.RepoSlot;
import appeng.client.gui.widgets.IScrollSource;
import appeng.client.gui.widgets.ISortSource;
import appeng.menu.me.common.GridInventoryEntry;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.Mth;

/**
 * 工厂工作台界面。
 *
 * <p>面板尺寸不写死，按当前 GUI 逻辑尺寸自适应：占满可用区域，四周只留 {@link #MARGIN} 的边距。
 * 这样 GUI scale 调大（逻辑尺寸变小）时不会溢出，调小（逻辑尺寸变大）时也不会缩成一小块。</p>
 *
 * <p>右侧是 176px 宽的 AE2 终端风格存储栏：搜索框、奇点/网络状态、9 列物品网格、底部玩家背包。
 * 存储网格直接用 AE2 的 {@link Repo} + {@link RepoSlot}（客户端只读虚拟槽位），
 * 所以 tooltip、JEI R/U、数量/耐久装饰都由原版/ AE2 逻辑免费提供；背包是真实 {@link Slot}。</p>
 *
 * <p>所有颜色来自 {@link GuiTheme}，切换主题时这里不用改代码。</p>
 */
public class FactoryScreen extends AbstractContainerScreen<FactoryMenu> implements IScrollSource, ISortSource {
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
    /** 滚动条宽度。 */
    private static final int SCROLLBAR_WIDTH = 3;

    /** 当前主题；默认跟随 {@link GuiTheme#current()}（M1 是 DARK）。 */
    private GuiTheme theme = GuiTheme.current();

    /** 搜索框；每次 {@code init()} 重建（尺寸变化）。 */
    @Nullable
    private SearchEditBox searchBox;

    /** AE2 的客户端仓库：负责排序、搜索、滚动偏移和条目视图。 */
    private final Repo repo;

    /** key → 稳定 serial：服务端快照每次全量下发，用稳定 serial 让 Repo 能正确合并/去重。 */
    private final Map<AEKey, Long> serials = new HashMap<>();
    private long nextSerial = 1L;

    /** 最近一次服务端库存快照。 */
    private StorageSnapshotS2C snapshot = ClientPacketHandler.snapshot();

    /** 当前滚动行（{@link IScrollSource}）。 */
    private int scrollRow;
    private boolean draggingScrollbar;

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

        this.repo = new Repo(this, this);
        this.repo.setRowSize(ItemGrid.COLUMNS);
        this.repo.setEnabled(true);
        this.repo.setUpdateViewListener(this::clampScroll);
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

        this.repo.setSearchString(query);
        layoutSlots();
        layoutRepoSlots();
        pushSnapshot();
        clampScroll();
    }

    /**
     * 重排玩家背包槽位。
     *
     * <p>1.21.1 的 {@link Slot#x}/{@link Slot#y} 是 final，只能按新坐标重建 Slot；这里只替换
     * 客户端菜单里的槽位对象，容器与容器索引保持一致，服务端槽位完全不受影响。
     * 槽位坐标是相对面板左上角的（{@code AbstractContainerScreen} 渲染槽位时会平移）。</p>
     */
    private void layoutSlots() {
        int relGridX = gridX() - this.leftPos;
        int relHotbarY = hotbarY() - this.topPos;
        int relMainY = mainInvY() - this.topPos;

        int count = Math.min(FactoryMenu.PLAYER_SLOTS, this.menu.slots.size());
        for (int i = 0; i < count; i++) {
            Slot old = this.menu.slots.get(i);
            int x;
            int y;
            if (i < 9) {
                x = relGridX + i * ItemGrid.CELL_SIZE + 1;
                y = relHotbarY + 1;
            } else {
                int index = i - 9;
                x = relGridX + index % 9 * ItemGrid.CELL_SIZE + 1;
                y = relMainY + index / 9 * ItemGrid.CELL_SIZE + 1;
            }

            Slot slot = new Slot(old.container, old.getContainerSlot(), x, y);
            slot.index = i;
            this.menu.slots.set(i, slot);
        }
    }

    /**
     * 客户端专用：按当前行数重建存储网格的 {@link RepoSlot}。服务端没有这些槽位，
     * 点击它们会被 {@link #slotClicked} 拦下，不会发给服务端。
     */
    private void layoutRepoSlots() {
        List<Slot> slots = this.menu.slots;
        slots.removeIf(slot -> slot instanceof RepoSlot);

        int relGridX = gridX() - this.leftPos;
        int relGridTop = gridTop() - this.topPos;

        int repoIndex = 0;
        for (int row = 0; row < gridRows(); row++) {
            for (int col = 0; col < ItemGrid.COLUMNS; col++) {
                RepoSlot repoSlot = new RepoSlot(this.repo, repoIndex++,
                        relGridX + col * ItemGrid.CELL_SIZE + 1,
                        relGridTop + row * ItemGrid.CELL_SIZE + 1);
                repoSlot.index = slots.size();
                slots.add(repoSlot);
            }
        }
    }

    /** 把当前快照转成 {@link GridInventoryEntry} 并推给 Repo。 */
    private void pushSnapshot() {
        List<GridInventoryEntry> entries = new ArrayList<>(this.snapshot.entries().size());
        for (StorageSnapshotS2C.Entry entry : this.snapshot.entries()) {
            long serial = this.serials.computeIfAbsent(entry.key(), key -> this.nextSerial++);
            entries.add(new GridInventoryEntry(serial, entry.key(), entry.amount(), 0, false));
        }
        this.repo.handleUpdate(true, entries);
        clampScroll();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;

        // 搜索词变化时更新 Repo（输入法/粘贴/退格/右键清空都走这里）
        if (this.searchBox != null && !this.searchBox.getValue().equals(this.lastQuery)) {
            this.lastQuery = this.searchBox.getValue();
            this.repo.setSearchString(this.lastQuery);
            this.repo.updateView();
            this.scrollRow = 0;
            clampScroll();
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);

        if (this.repo.size() == 0) {
            int centerX = gridX() + ItemGrid.COLUMNS * ItemGrid.CELL_SIZE / 2;
            int centerY = gridTop() + gridRows() * ItemGrid.CELL_SIZE / 2 - 4;
            guiGraphics.drawCenteredString(this.font, emptyText(), centerX, centerY, this.theme.textDim());
        }

        // 背包 Slot 与 RepoSlot 的 tooltip 都由父类统一处理
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
        renderStorageHeader(guiGraphics);
        renderScrollbar(guiGraphics);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // 不调用父类：父类会画菜单标题和玩家物品栏标题，这版都用自定义布局。
        // 这里的坐标相对面板左上角。
        guiGraphics.drawString(this.font, "Idle Factory", TITLE_X + 8, TITLE_Y + 1, this.theme.accent(), false);
    }

    /**
     * 完全接管槽位绘制：主题色内凹格子背景，然后交给父类画物品、数量、耐久等装饰。
     *
     * <p>{@link RepoSlot} 的 {@code getItem()} 只是数量为 1 的展示栈，库存数量需要自己补一行字。</p>
     */
    @Override
    protected void renderSlot(GuiGraphics guiGraphics, Slot slot) {
        boolean hovered = this.isHovering(slot.x, slot.y, 16, 16, this.mouseX, this.mouseY);
        ItemGrid.drawSlotBackground(guiGraphics, this.theme, slot.x - 1, slot.y - 1, hovered);

        super.renderSlot(guiGraphics, slot);

        if (slot instanceof RepoSlot repoSlot) {
            long amount = repoSlot.getStoredAmount();
            if (amount > 0) {
                String text = formatAmount(amount);
                guiGraphics.drawString(this.font, text,
                        slot.x + 17 - this.font.width(text), slot.y + 9,
                        this.theme.textPrimary(), true);
            }
        }
    }

    @Override
    protected void renderSlotHighlight(GuiGraphics guiGraphics, Slot slot, int mouseX, int mouseY, float partialTick) {
        // 悬停高亮已由 renderSlot 用主题色接管，不要再叠一层原版白框
    }

    /**
     * {@link RepoSlot} 是客户端只读虚拟槽位，点击不能发给服务端（服务端没有这个索引）。
     */
    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType type) {
        if (slot instanceof RepoSlot) {
            return;
        }
        super.slotClicked(slot, slotId, mouseButton, type);
    }

    /** 收到服务端库存快照时刷新界面。 */
    public void applySnapshot(StorageSnapshotS2C payload) {
        this.snapshot = payload;
        this.scrollRow = 0;
        pushSnapshot();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 搜索框有焦点时吞掉所有按键（ESC 除外），否则输入 e 会触发原版「关闭容器」
        if (this.searchBox != null && this.searchBox.isFocused() && keyCode != 256) {
            if (keyCode == 257) {
                this.searchBox.setFocused(false);
                return true;
            }
            this.searchBox.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.searchBox != null && !this.searchBox.isMouseOver(mouseX, mouseY)) {
            this.searchBox.setFocused(false);
        }
        if (button == 0 && beginScrollbarDrag(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isOverStorageGridArea(mouseX, mouseY) || isOverScrollbar(mouseX, mouseY)) {
            int maxScroll = maxScrollRow();
            if (maxScroll > 0) {
                int delta = scrollY > 0 ? -1 : 1;
                this.scrollRow = Mth.clamp(this.scrollRow + delta, 0, maxScroll);
                this.repo.updateView();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.draggingScrollbar) {
            scrollToMouse(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.draggingScrollbar) {
            this.draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    // ------------------------------------------------------------------
    // IScrollSource / ISortSource
    // ------------------------------------------------------------------

    @Override
    public int getCurrentScroll() {
        return this.scrollRow;
    }

    @Override
    public SortOrder getSortBy() {
        return SortOrder.NAME;
    }

    @Override
    public SortDir getSortDir() {
        return SortDir.ASCENDING;
    }

    @Override
    public ViewItems getSortDisplay() {
        return ViewItems.ALL;
    }

    @Override
    public Set<AEKeyType> getSortKeyTypes() {
        // 只展示物品（服务端快照本身也只发物品）
        return Set.of(AEKeyType.items());
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

    private int gridWidth() {
        return ItemGrid.COLUMNS * ItemGrid.CELL_SIZE;
    }

    private int searchBoxTextX() {
        return gridX() + 3;
    }

    private int searchBoxTextY() {
        return storageY() + SEARCH_TOP + 3;
    }

    private int searchBoxTextWidth() {
        return gridWidth() - 6;
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
        int searchW = gridWidth();
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

    private void renderStorageHeader(GuiGraphics guiGraphics) {
        guiGraphics.drawString(this.font, statusText(), gridX(), storageY() + STATUS_TOP, statusColor(), false);
    }

    private void renderScrollbar(GuiGraphics guiGraphics) {
        int trackX = scrollbarX();
        int trackY = gridTop();
        int trackHeight = gridRows() * ItemGrid.CELL_SIZE;
        guiGraphics.fill(trackX, trackY, trackX + SCROLLBAR_WIDTH, trackY + trackHeight, this.theme.slotBorderDark());

        int maxScroll = maxScrollRow();
        if (maxScroll <= 0) {
            return;
        }

        int thumbHeight = thumbHeight(trackHeight, maxScroll);
        int travel = trackHeight - thumbHeight;
        int thumbY = trackY + travel * this.scrollRow / maxScroll;
        boolean active = this.draggingScrollbar || isOverScrollbar(this.mouseX, this.mouseY);
        guiGraphics.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbHeight,
                active ? this.theme.accent() : this.theme.textDim());
    }

    // ------------------------------------------------------------------
    // 滚动条
    // ------------------------------------------------------------------

    private int scrollbarX() {
        return gridX() + gridWidth() + 3;
    }

    private boolean isOverScrollbar(double mouseX, double mouseY) {
        int x = scrollbarX();
        int y = gridTop();
        return mouseX >= x - 2 && mouseX < x + SCROLLBAR_WIDTH + 2
                && mouseY >= y && mouseY < y + gridRows() * ItemGrid.CELL_SIZE;
    }

    private boolean isOverStorageGridArea(double mouseX, double mouseY) {
        return mouseX >= gridX() && mouseX < gridX() + gridWidth()
                && mouseY >= gridTop() && mouseY < gridTop() + gridRows() * ItemGrid.CELL_SIZE;
    }

    private boolean beginScrollbarDrag(double mouseX, double mouseY) {
        if (!isOverScrollbar(mouseX, mouseY) || maxScrollRow() <= 0) {
            return false;
        }
        this.draggingScrollbar = true;
        scrollToMouse(mouseY);
        return true;
    }

    private void scrollToMouse(double mouseY) {
        int maxScroll = maxScrollRow();
        if (maxScroll <= 0) {
            this.scrollRow = 0;
            return;
        }
        int trackHeight = gridRows() * ItemGrid.CELL_SIZE;
        int thumbHeight = thumbHeight(trackHeight, maxScroll);
        int travel = trackHeight - thumbHeight;
        if (travel <= 0) {
            this.scrollRow = 0;
            return;
        }
        double ratio = (mouseY - gridTop() - thumbHeight / 2.0) / travel;
        this.scrollRow = Mth.clamp((int) Math.round(ratio * maxScroll), 0, maxScroll);
        this.repo.updateView();
    }

    private int thumbHeight(int trackHeight, int maxScroll) {
        int usedRows = maxScroll + gridRows();
        return Math.max(12, trackHeight * gridRows() / usedRows);
    }

    private int maxScrollRow() {
        int totalRows = (this.repo.size() + ItemGrid.COLUMNS - 1) / ItemGrid.COLUMNS;
        if (this.repo.hasPinnedRow()) {
            totalRows++;
        }
        return Math.max(0, totalRows - gridRows());
    }

    private void clampScroll() {
        this.scrollRow = Mth.clamp(this.scrollRow, 0, maxScrollRow());
    }

    // ------------------------------------------------------------------
    // 状态 / 工具
    // ------------------------------------------------------------------

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

    /** 数量简写：<1000 原样；1000–9999 千分位；再大用 k / M / B。 */
    private static String formatAmount(long amount) {
        if (amount < 1_000L) {
            return Long.toString(amount);
        }
        if (amount < 10_000L) {
            return String.format(Locale.ROOT, "%,d", amount);
        }
        if (amount < 1_000_000L) {
            return compact(amount / 1_000.0) + "k";
        }
        if (amount < 1_000_000_000L) {
            return compact(amount / 1_000_000.0) + "M";
        }
        return compact(amount / 1_000_000_000.0) + "B";
    }

    private static String compact(double value) {
        String text = String.format(Locale.ROOT, "%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }
}
