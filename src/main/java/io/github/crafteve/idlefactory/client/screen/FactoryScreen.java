package io.github.crafteve.idlefactory.client.screen;

import java.util.List;
import java.util.Locale;

import io.github.crafteve.idlefactory.client.ClientPacketHandler;
import io.github.crafteve.idlefactory.menu.FactoryMenu;
import io.github.crafteve.idlefactory.network.payload.StorageSnapshotS2C;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * 工厂工作台界面。
 *
 * <p>面板尺寸不写死，按当前 GUI 逻辑尺寸自适应：占满可用区域，四周只留 {@link #MARGIN} 的边距。
 * 这样 GUI scale 调大（逻辑尺寸变小）时不会溢出，调小（逻辑尺寸变大）时也不会缩成一小块。</p>
 *
 * <p>M1 在右侧加了一条 240px 宽的存储栏：顶部是奇点/网络状态，下面是 18×18 的物品网格。
 * 数据全部来自服务端的 {@link StorageSnapshotS2C}，客户端只读。</p>
 */
public class FactoryScreen extends AbstractContainerScreen<FactoryMenu> {
    /** 面板四周留白（GUI 逻辑像素）。 */
    private static final int MARGIN = 8;
    /** 最小面板宽度，防止极端小窗口下面板消失。 */
    private static final int MIN_WIDTH = 320;
    /** 最小面板高度。 */
    private static final int MIN_HEIGHT = 240;

    /** 深色底 #1a1a1c。 */
    private static final int COLOR_PANEL = 0xFF1A1A1C;
    /** 边框 #2a2a2e。 */
    private static final int COLOR_BORDER = 0xFF2A2A2E;
    /** 青色标题与高亮 #4dd0e1。 */
    private static final int COLOR_ACCENT = 0xFF4DD0E1;
    /** 存储栏底色，比主面板稍暗。 */
    private static final int COLOR_STORAGE_PANEL = 0xFF141416;
    /** 物品格底色。 */
    private static final int COLOR_SLOT = 0xFF212126;
    /** 次要文字。 */
    private static final int COLOR_MUTED = 0xFF6B6B72;
    /** 错误/网络丢失提示色。 */
    private static final int COLOR_ERROR = 0xFFE57373;

    /** 标题左侧标记的 x 偏移。 */
    private static final int TITLE_X = 20;
    /** 标题的 y 偏移。 */
    private static final int TITLE_Y = 16;
    /** 标题栏高度，下方画分隔线。 */
    private static final int HEADER_HEIGHT = 44;

    /** 右侧存储栏宽度。 */
    private static final int STORAGE_WIDTH = 240;
    /** 存储栏与面板右/下边缘的距离。 */
    private static final int STORAGE_MARGIN = 12;
    /** 存储栏文字的内边距。 */
    private static final int STORAGE_PADDING = 10;
    /** 存储栏顶部（标题 + 状态）的高度。 */
    private static final int STORAGE_HEADER_HEIGHT = 40;
    /** 物品格边长。 */
    private static final int CELL_SIZE = 18;
    /** 每行格子数：12 × 18 = 216，正好放进 240 宽。 */
    private static final int COLUMNS = 12;

    /** 最近一次服务端快照；界面创建前到达的包由 {@link ClientPacketHandler} 缓存。 */
    private StorageSnapshotS2C snapshot = ClientPacketHandler.snapshot();
    /** 网格滚动行数。 */
    private int scrollRow;

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
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        // 父类画完背景与标签后，再叠加存储栏内容（物品图标要盖在面板上）
        this.renderStorageContents(guiGraphics);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        int w = this.imageWidth;
        int h = this.imageHeight;

        // 深色面板底色
        guiGraphics.fill(x, y, x + w, y + h, COLOR_PANEL);

        // 1px 细边框（上 / 下 / 左 / 右）
        guiGraphics.fill(x, y, x + w, y + 1, COLOR_BORDER);
        guiGraphics.fill(x, y + h - 1, x + w, y + h, COLOR_BORDER);
        guiGraphics.fill(x, y, x + 1, y + h, COLOR_BORDER);
        guiGraphics.fill(x + w - 1, y, x + w, y + h, COLOR_BORDER);

        // 标题栏分隔线
        guiGraphics.fill(x + 1, y + HEADER_HEIGHT, x + w - 1, y + HEADER_HEIGHT + 1, COLOR_BORDER);

        // 标题左侧的青色竖条
        guiGraphics.fill(x + TITLE_X, y + TITLE_Y, x + TITLE_X + 2, y + TITLE_Y + 9, COLOR_ACCENT);

        // 右侧存储栏
        this.renderStoragePanel(guiGraphics);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // 不调用父类：父类会画菜单标题和玩家物品栏标题，这版都用自定义布局。
        // 这里的坐标相对面板左上角。
        guiGraphics.drawString(this.font, "Idle Factory", TITLE_X + 8, TITLE_Y + 1, COLOR_ACCENT, false);
    }

    /** 收到服务端库存快照时刷新界面。 */
    public void applySnapshot(StorageSnapshotS2C snapshot) {
        this.snapshot = snapshot;
        this.scrollRow = 0;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isOverStorage(mouseX, mouseY)) {
            int maxScroll = maxScrollRow();
            if (maxScroll > 0) {
                int delta = scrollY > 0 ? -1 : (scrollY < 0 ? 1 : 0);
                this.scrollRow = Mth.clamp(this.scrollRow + delta, 0, maxScroll);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
        return storageX() + (STORAGE_WIDTH - COLUMNS * CELL_SIZE) / 2;
    }

    private int gridY() {
        return storageY() + STORAGE_HEADER_HEIGHT;
    }

    private int gridRows() {
        return Math.max(1, (storageHeight() - STORAGE_HEADER_HEIGHT - 8) / CELL_SIZE);
    }

    private int maxScrollRow() {
        int usedRows = (this.snapshot.entries().size() + COLUMNS - 1) / COLUMNS;
        return Math.max(0, usedRows - gridRows());
    }

    private boolean isOverStorage(double mouseX, double mouseY) {
        return mouseX >= storageX() && mouseX < storageX() + STORAGE_WIDTH
                && mouseY >= storageY() && mouseY < storageY() + storageHeight();
    }

    // ------------------------------------------------------------------
    // 存储栏绘制
    // ------------------------------------------------------------------

    private void renderStoragePanel(GuiGraphics guiGraphics) {
        int x = storageX();
        int y = storageY();
        int w = STORAGE_WIDTH;
        int h = storageHeight();

        guiGraphics.fill(x, y, x + w, y + h, COLOR_STORAGE_PANEL);
        guiGraphics.fill(x, y, x + w, y + 1, COLOR_BORDER);
        guiGraphics.fill(x, y + h - 1, x + w, y + h, COLOR_BORDER);
        guiGraphics.fill(x, y, x + 1, y + h, COLOR_BORDER);
        guiGraphics.fill(x + w - 1, y, x + w, y + h, COLOR_BORDER);

        // 标题与状态下方的一条分隔线（网格从 STORAGE_HEADER_HEIGHT 开始）
        guiGraphics.fill(x + 1, y + STORAGE_HEADER_HEIGHT - 6,
                x + w - 1, y + STORAGE_HEADER_HEIGHT - 5, COLOR_BORDER);
    }

    private void renderStorageContents(GuiGraphics guiGraphics) {
        int x = storageX();
        int y = storageY();

        guiGraphics.drawString(this.font, Component.translatable("screen.idlefactory.storage"),
                x + STORAGE_PADDING, y + 10, COLOR_ACCENT, false);
        guiGraphics.drawString(this.font, statusText(),
                x + STORAGE_PADDING, y + 24, statusColor(), false);

        if (this.snapshot.entries().isEmpty()) {
            guiGraphics.drawCenteredString(this.font, emptyText(),
                    x + STORAGE_WIDTH / 2, y + STORAGE_HEADER_HEIGHT + 30, COLOR_MUTED);
        } else {
            renderItemGrid(guiGraphics);
        }
    }

    private void renderItemGrid(GuiGraphics guiGraphics) {
        int gx = gridX();
        int gy = gridY();
        int rows = gridRows();
        int maxScroll = maxScrollRow();
        this.scrollRow = Mth.clamp(this.scrollRow, 0, maxScroll);

        List<StorageSnapshotS2C.Entry> entries = this.snapshot.entries();
        int start = this.scrollRow * COLUMNS;

        guiGraphics.enableScissor(gx, gy, gx + COLUMNS * CELL_SIZE, gy + rows * CELL_SIZE);
        for (int i = 0; i < rows * COLUMNS; i++) {
            int index = start + i;
            if (index >= entries.size()) {
                break;
            }

            int cellX = gx + (i % COLUMNS) * CELL_SIZE;
            int cellY = gy + (i / COLUMNS) * CELL_SIZE;
            StorageSnapshotS2C.Entry entry = entries.get(index);

            guiGraphics.fill(cellX, cellY, cellX + CELL_SIZE, cellY + CELL_SIZE, COLOR_SLOT);
            ItemStack stack = toDisplayStack(entry.key());
            guiGraphics.renderItem(stack, cellX + 1, cellY + 1);
            guiGraphics.renderItemDecorations(this.font, stack, cellX + 1, cellY + 1,
                    formatAmount(entry.amount()));
        }
        guiGraphics.disableScissor();

        // 条目超过一屏时画一个简单滚动条
        if (maxScroll > 0) {
            int trackX = gx + COLUMNS * CELL_SIZE + 2;
            int trackY = gy;
            int trackHeight = rows * CELL_SIZE;
            guiGraphics.fill(trackX, trackY, trackX + 2, trackY + trackHeight, COLOR_BORDER);

            int thumbHeight = Math.max(12, trackHeight * rows / (rows + maxScroll));
            int thumbY = trackY + (trackHeight - thumbHeight) * this.scrollRow / maxScroll;
            guiGraphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight, COLOR_ACCENT);
        }
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
            return COLOR_MUTED;
        }
        return this.snapshot.bound() ? COLOR_ACCENT : COLOR_ERROR;
    }

    private Component emptyText() {
        if (!this.snapshot.equipped()) {
            return Component.translatable("screen.idlefactory.empty.not_equipped");
        }
        if (!this.snapshot.bound()) {
            return Component.translatable("screen.idlefactory.empty.no_network");
        }
        return Component.translatable("screen.idlefactory.empty.no_stock");
    }

    private static ItemStack toDisplayStack(AEKey key) {
        return key instanceof AEItemKey itemKey ? itemKey.toStack(1) : key.wrapForDisplayOrFilter();
    }

    /**
     * 库存数量简写：小于 1000 原样，1000–9999 用千分位，再大用 k / M / B。
     */
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
