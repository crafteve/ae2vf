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
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 工厂工作台界面。
 *
 * <p>面板尺寸不写死，按当前 GUI 逻辑尺寸自适应：占满可用区域，四周只留 {@link #MARGIN} 的边距。
 * 这样 GUI scale 调大（逻辑尺寸变小）时不会溢出，调小（逻辑尺寸变大）时也不会缩成一小块。</p>
 *
 * <p>右侧是 190px 宽的 AE2 终端风格存储栏：顶部搜索框（复用 AE2 的 {@link RepoSearch}）、
 * 奇点/网络状态、9 列物品网格，底部是玩家背包（3×9 主背包 + 1×9 快捷栏）。
 * 库存数据全部来自服务端的 {@link StorageSnapshotS2C}，客户端只做展示与过滤。</p>
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
    private static final int TITLE_Y = 16;
    /** 标题栏高度，下方画分隔线。 */
    private static final int HEADER_HEIGHT = 44;

    /** 右侧存储栏宽度。 */
    private static final int STORAGE_WIDTH = 190;
    /** 存储栏与面板右/下边缘的距离。 */
    private static final int STORAGE_MARGIN = 12;
    /** 存储栏左右内边距。 */
    private static final int STORAGE_PADDING = 9;
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
    private final ItemGrid grid = new ItemGrid();

    /** AE2 搜索语法解析器（@mod / #tag / *id / 拼音模组适配都继承自它）。 */
    private final RepoSearch repoSearch = new RepoSearch();
    /** key → 稳定 serial，避免过滤时 GridInventoryEntry 的缓存串味。 */
    private final Map<AEKey, Long> serials = new HashMap<>();
    private long nextSerial = 1L;

    /** 最近一次服务端库存快照。 */
    private StorageSnapshotS2C snapshot = ClientPacketHandler.snapshot();
    /** 上一次过滤用的搜索词。 */
    private String lastQuery = "";

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

        this.grid.setBounds(gridX(), gridTop(), gridRows());
        refreshFilter();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 搜索词变化时重新过滤（输入法/粘贴/退格都走这条路）
        if (this.searchBox != null && !this.searchBox.getValue().equals(this.lastQuery)) {
            refreshFilter();
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderStorageContents(guiGraphics, mouseX, mouseY);

        ItemStack hovered = hoveredStack(mouseX, mouseY);
        if (!hovered.isEmpty()) {
            guiGraphics.renderTooltip(this.font, hovered, mouseX, mouseY);
        }
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
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // 不调用父类：父类会画菜单标题和玩家物品栏标题，这版都用自定义布局。
        // 这里的坐标相对面板左上角。
        guiGraphics.drawString(this.font, "Idle Factory", TITLE_X + 8, TITLE_Y + 1, this.theme.accent(), false);
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
        return this.grid.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.grid.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.grid.mouseDragged(mouseY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.grid.mouseReleased()) {
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
        return storageX() + STORAGE_PADDING;
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
        return storageX() + STORAGE_PADDING + 3;
    }

    private int searchBoxTextY() {
        return storageY() + SEARCH_TOP + 3;
    }

    private int searchBoxTextWidth() {
        return STORAGE_WIDTH - STORAGE_PADDING * 2 - 6;
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

        // 搜索框用格子底色，与存储栏底色区分
        int searchX = x + STORAGE_PADDING;
        int searchY = y + SEARCH_TOP;
        int searchW = STORAGE_WIDTH - STORAGE_PADDING * 2;
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

        guiGraphics.drawString(this.font, statusText(), x + STORAGE_PADDING, y + STATUS_TOP,
                statusColor(), false);

        this.grid.render(guiGraphics, this.font, this.theme, mouseX, mouseY);
        renderPlayerInventory(guiGraphics);

        if (this.grid.isEmpty()) {
            int centerX = gridX() + this.grid.width() / 2;
            int centerY = gridTop() + this.grid.height() / 2 - 4;
            guiGraphics.drawCenteredString(this.font, emptyText(), centerX, centerY, this.theme.textDim());
        }
    }

    private void renderPlayerInventory(GuiGraphics guiGraphics) {
        Player player = this.minecraft.player;
        if (player == null) {
            return;
        }
        Inventory inventory = player.getInventory();

        int invX = gridX();
        int mainY = mainInvY();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                drawInventorySlot(guiGraphics, inventory.getItem(9 + row * 9 + col),
                        invX + col * ItemGrid.CELL_SIZE, mainY + row * ItemGrid.CELL_SIZE);
            }
        }

        int hotY = hotbarY();
        for (int col = 0; col < 9; col++) {
            drawInventorySlot(guiGraphics, inventory.getItem(col),
                    invX + col * ItemGrid.CELL_SIZE, hotY);
        }
    }

    private void drawInventorySlot(GuiGraphics guiGraphics, ItemStack stack, int x, int y) {
        ItemGrid.drawSlotBackground(guiGraphics, this.theme, x, y);
        if (!stack.isEmpty()) {
            guiGraphics.renderItem(stack, x + 1, y + 1);
            guiGraphics.renderItemDecorations(this.font, stack, x + 1, y + 1);
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
        this.grid.setEntries(List.copyOf(filtered));
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

    private ItemStack hoveredStack(int mouseX, int mouseY) {
        StorageSnapshotS2C.Entry entry = this.grid.hoveredEntry(mouseX, mouseY);
        if (entry != null) {
            return ItemGrid.toDisplayStack(entry.key());
        }

        Player player = this.minecraft.player;
        if (player == null) {
            return ItemStack.EMPTY;
        }
        Inventory inventory = player.getInventory();
        int invX = gridX();

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                if (isInside(mouseX, mouseY, invX + col * ItemGrid.CELL_SIZE,
                        mainInvY() + row * ItemGrid.CELL_SIZE)) {
                    return inventory.getItem(9 + row * 9 + col);
                }
            }
        }
        for (int col = 0; col < 9; col++) {
            if (isInside(mouseX, mouseY, invX + col * ItemGrid.CELL_SIZE, hotbarY())) {
                return inventory.getItem(col);
            }
        }
        return ItemStack.EMPTY;
    }

    private static boolean isInside(double mouseX, double mouseY, int x, int y) {
        return mouseX >= x && mouseX < x + ItemGrid.CELL_SIZE
                && mouseY >= y && mouseY < y + ItemGrid.CELL_SIZE;
    }
}
