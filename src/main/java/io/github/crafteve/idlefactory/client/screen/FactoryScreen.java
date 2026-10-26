package io.github.crafteve.idlefactory.client.screen;

import java.util.List;

import io.github.crafteve.idlefactory.client.screen.DemoRows.Row;
import io.github.crafteve.idlefactory.client.screen.DemoRows.State;
import io.github.crafteve.idlefactory.client.theme.GuiTheme;
import io.github.crafteve.idlefactory.client.widget.GuiTextures;
import io.github.crafteve.idlefactory.client.widget.NineSlice;
import io.github.crafteve.idlefactory.menu.FactoryMenu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 工厂工作台。
 *
 * <p><b>P0 阶段</b>：只画骨架，不连数据、不做交互（除了关闭）。目的是进游戏量三件事：
 * 面板占屏比例、两侧给配方查看器留的宽度够不够、MC 真实字体下中文和数字会不会挤。</p>
 *
 * <p>静态外观全部来自贴图（{@link NineSlice}），这里不画边框和斜边；纯色块用
 * {@link NineSlice#fill} 把 1×1 贴图拉伸。</p>
 *
 * <p>几何全在 {@link FactoryLayout}，本类只负责「按坐标画」。以后调尺寸只改布局类。</p>
 */
public class FactoryScreen extends AbstractContainerScreen<FactoryMenu> {
    /** 标题与下拉之间的间距。 */
    private static final int TITLE_GAP = 10;
    /** 状态点边长。 */
    private static final int DOT = 6;
    /** 状态色轨宽度。 */
    private static final int TRACK_W = 2;
    /** 关闭按钮边长。 */
    private static final int CLOSE_SIZE = 14;
    /** 图标之间的水平间距。 */
    private static final int ICON_STEP = FactoryLayout.CELL + 2;

    private final GuiTheme theme = GuiTheme.current();
    private final List<Row> rows = DemoRows.rows();
    private FactoryLayout layout;

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
        // 父类按 imageWidth/imageHeight 居中，正好等于 layout 里算的 panelX/panelY
        super.init();
        positionSlots();
    }

    /**
     * 把 36 个玩家槽位摆到右区底部。
     *
     * <p>{@code Slot.x/y} 在 1.21.1 是 {@code final}，改不了坐标，只能按新坐标重建槽位对象。
     * 这里只替换客户端菜单里的槽位，容器与容器索引不变，服务端完全不受影响（它不需要坐标）。</p>
     *
     * <p>槽位坐标相对面板左上角（父类渲染时会平移），所以全部减掉 leftPos/topPos。</p>
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
        super.render(graphics, mouseX, mouseY, partialTick);
        drawHeader(graphics);
        drawTable(graphics);
        drawRightPanel(graphics);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        FactoryLayout l = this.layout;

        NineSlice.panel(graphics, this.leftPos, this.topPos, this.imageWidth, this.imageHeight);

        // 顶栏下沿
        NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK,
                l.headerX, l.headerY + FactoryLayout.HEADER_H, l.headerW, 1);

        // 左右两区分隔线
        NineSlice.fill(graphics, GuiTextures.DIVIDER,
                l.rightX - FactoryLayout.GAP / 2, l.bodyY, 1, l.bodyH);

        // 表头底色
        NineSlice.fill(graphics, GuiTextures.HEADER_BG,
                l.leftX + 1, l.tableHeaderY, l.leftW - 2, FactoryLayout.TABLE_HEADER_H);

        // 隔行底色与行分隔线
        int shown = Math.min(l.rowsVisible, this.rows.size());
        for (int i = 0; i < shown; i++) {
            if (i % 2 == 1) {
                NineSlice.fill(graphics, GuiTextures.ROW_ALT,
                        l.leftX + 1, l.rowY(i), l.leftW - 2, FactoryLayout.ROW_H - 1);
            }
            NineSlice.fill(graphics, GuiTextures.DIVIDER,
                    l.tableX, l.rowY(i) + FactoryLayout.ROW_H - 1, l.tableW, 1);
        }

        // 统计行上沿
        NineSlice.fill(graphics, GuiTextures.DIVIDER, l.tableX, l.footerY, l.tableW, 1);

        drawRightChrome(graphics);
    }

    /** 右区的静态部分：tab、搜索框、物品网格、合成区。 */
    private void drawRightChrome(GuiGraphics graphics) {
        FactoryLayout l = this.layout;

        int tabWidth = l.rightW / FactoryLayout.TAB_COUNT;
        for (int i = 0; i < FactoryLayout.TAB_COUNT; i++) {
            int x = l.rightX + i * tabWidth;
            int w = i == FactoryLayout.TAB_COUNT - 1 ? l.rightW - i * tabWidth : tabWidth;
            NineSlice.button(graphics, x, l.tabY, w, FactoryLayout.TAB_H);
        }
        // 选中 tab 的上沿强调
        NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK,
                l.rightX, l.tabY, tabWidth, 2);

        NineSlice.field(graphics, l.rightX + 4, l.searchY, l.rightW - 8, FactoryLayout.SEARCH_H);

        drawGrid(graphics, l.gridX, l.gridY, FactoryLayout.GRID_COLS, l.gridRows,
                FactoryLayout.CELL);
        drawGrid(graphics, l.craftX, l.craftY, FactoryLayout.CRAFT, FactoryLayout.CRAFT,
                FactoryLayout.CRAFT_CELL);

        // 合成箭头：3×3 与结果格之间的一条细杆 + 箭头块
        int arrowY = l.resultY + FactoryLayout.CRAFT_RESULT / 2;
        NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK,
                l.craftX + FactoryLayout.CRAFT * FactoryLayout.CRAFT_CELL + 4, arrowY,
                FactoryLayout.CRAFT_ARROW - 8, 1);

        NineSlice.slot(graphics, l.resultX, l.resultY,
                FactoryLayout.CRAFT_RESULT, FactoryLayout.CRAFT_RESULT);
    }

    private void drawGrid(GuiGraphics graphics, int x, int y, int columns, int rows, int cell) {
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                NineSlice.slot(graphics, x + col * cell, y + row * cell, cell, cell);
            }
        }
    }

    /** 顶栏。 */
    private void drawHeader(GuiGraphics graphics) {
        FactoryLayout l = this.layout;
        int x = l.headerX + 6;
        int textY = l.headerY + (FactoryLayout.HEADER_H - 9) / 2;

        NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK, x, textY + 2, DOT, DOT);
        x += DOT + 6;

        String title = "放置工厂";
        graphics.drawString(this.font, title, x, textY, this.theme.accent(), false);
        x += this.font.width(title) + TITLE_GAP;

        String line = "铁线";
        int buttonW = this.font.width(line) + 24;
        NineSlice.button(graphics, x, l.headerY + 3, buttonW, FactoryLayout.TAB_H);
        graphics.drawString(this.font, line, x + 8, textY, this.theme.textPrimary(), false);
        x += buttonW + 8;

        NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK, x, textY + 2, DOT, DOT);
        graphics.drawString(this.font, "运行", x + DOT + 4, textY, this.theme.statusRun(), false);

        String stats = "12 台 · 3 线 · 仓库在线";
        int closeX = l.headerX + l.headerW - 6 - CLOSE_SIZE;
        int statsX = closeX - 8 - this.font.width(stats);
        graphics.drawString(this.font, stats, statsX, textY, this.theme.textDim(), false);

        NineSlice.button(graphics, closeX, l.headerY + 4, CLOSE_SIZE, CLOSE_SIZE);
        graphics.drawString(this.font, "x",
                closeX + (CLOSE_SIZE - this.font.width("x")) / 2, textY,
                this.theme.textPrimary(), false);
    }

    /** 左区表格。 */
    private void drawTable(GuiGraphics graphics) {
        FactoryLayout l = this.layout;
        int headerY = l.tableHeaderY + (FactoryLayout.TABLE_HEADER_H - 9) / 2;
        String[] headers = { "机器", "状态", "输入", "输出", "速率" };
        for (int i = 0; i < headers.length; i++) {
            graphics.drawString(this.font, headers[i], l.columnX(i) + 4, headerY,
                    this.theme.textDim(), false);
        }

        int shown = Math.min(l.rowsVisible, this.rows.size());
        for (int i = 0; i < shown; i++) {
            drawRow(graphics, this.rows.get(i), i);
        }

        long ok = this.rows.stream().filter(r -> r.state() == State.OK).count();
        long warn = this.rows.stream().filter(r -> r.state() == State.WARN).count();
        long idle = this.rows.stream().filter(r -> r.state() == State.IDLE).count();
        long error = this.rows.stream().filter(r -> r.state() == State.ERROR).count();
        String footer = "共 " + this.rows.size() + " 台 · 就绪 " + ok + " · 缺料 " + warn
                + " · 未配 " + idle + " · 失联 " + error;
        graphics.drawString(this.font, footer, l.tableX, l.footerY + 3, this.theme.textDim(), false);
    }

    private void drawRow(GuiGraphics graphics, Row row, int index) {
        FactoryLayout l = this.layout;
        int y = l.rowY(index);
        int textY = y + (FactoryLayout.ROW_H - 9) / 2;

        NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK,
                l.leftX + 1, y, TRACK_W, FactoryLayout.ROW_H - 2);

        int iconX = l.columnX(0) + 4;
        graphics.renderItem(new ItemStack(Items.BLAST_FURNACE), iconX, y + 5);
        graphics.drawString(this.font, row.name(), iconX + FactoryLayout.CELL + 2, textY,
                this.theme.textPrimary(), false);

        int dotX = l.columnX(1) + 4;
        NineSlice.fill(graphics, GuiTextures.DIVIDER_DARK, dotX, textY + 2, DOT, DOT);
        graphics.drawString(this.font, row.detail(), dotX + DOT + 4, textY, colorOf(row.state()), false);

        drawIcons(graphics, row.inputs(), l.columnX(2) + 2, y + 5, l.columnW(2) - 6);
        drawIcons(graphics, row.outputs(), l.columnX(3) + 2, y + 5, l.columnW(3) - 6);

        graphics.drawString(this.font, row.rate(), l.columnX(4) + 4, textY,
                "—".equals(row.rate()) ? this.theme.textDim() : this.theme.textPrimary(), false);
    }

    private void drawIcons(GuiGraphics graphics, List<ItemStack> stacks, int x, int y, int maxWidth) {
        if (stacks.isEmpty()) {
            graphics.drawString(this.font, "—", x, y + 4, this.theme.textDim(), false);
            return;
        }
        int fit = Math.max(1, maxWidth / ICON_STEP);
        int shown = Math.min(stacks.size(), fit);
        for (int i = 0; i < shown; i++) {
            graphics.renderItem(stacks.get(i), x + i * ICON_STEP, y);
        }
        if (stacks.size() > shown) {
            graphics.drawString(this.font, "+" + (stacks.size() - shown),
                    x + shown * ICON_STEP, y + 4, this.theme.textDim(), false);
        }
    }

    /** 右区文字。 */
    private void drawRightPanel(GuiGraphics graphics) {
        FactoryLayout l = this.layout;
        String[] tabs = { "仓库", "机器" };
        int tabWidth = l.rightW / FactoryLayout.TAB_COUNT;
        for (int i = 0; i < tabs.length; i++) {
            int x = l.rightX + i * tabWidth;
            graphics.drawString(this.font, tabs[i],
                    x + (tabWidth - this.font.width(tabs[i])) / 2,
                    l.tabY + (FactoryLayout.TAB_H - 9) / 2,
                    i == 0 ? this.theme.textPrimary() : this.theme.textDim(), false);
        }

        graphics.drawString(this.font, "搜索…", l.rightX + 8,
                l.searchY + (FactoryLayout.SEARCH_H - 9) / 2 + 1, this.theme.textDim(), false);

        graphics.drawString(this.font, "合成", l.craftX, l.craftLabelY, this.theme.textDim(), false);
        graphics.drawString(this.font, "背包", l.invX, l.invLabelY, this.theme.textDim(), false);
    }

    private int colorOf(State state) {
        return switch (state) {
            case OK -> this.theme.statusRun();
            case WARN -> this.theme.statusWarn();
            case IDLE -> this.theme.statusIdle();
            case ERROR -> this.theme.error();
        };
    }

    // ------------------------------------------------------------------
    // 槽位
    // ------------------------------------------------------------------

    @Override
    protected void renderSlot(GuiGraphics graphics, Slot slot) {
        NineSlice.slot(graphics, slot.x - 1, slot.y - 1, FactoryLayout.CELL, FactoryLayout.CELL);
        super.renderSlot(graphics, slot);
    }

    @Override
    protected void renderSlotHighlight(GuiGraphics graphics, Slot slot, int mouseX, int mouseY,
            float partialTick) {
        // 悬停高亮由贴图接管，不要叠一层原版白框
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 不画原版标题与「物品栏」字样
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        FactoryLayout l = this.layout;
        int closeX = l.headerX + l.headerW - 6 - CLOSE_SIZE;
        if (mouseX >= closeX && mouseX < closeX + CLOSE_SIZE
                && mouseY >= l.headerY + 4 && mouseY < l.headerY + 4 + CLOSE_SIZE) {
            this.onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 当前布局（调试与后续阶段用）。 */
    public FactoryLayout layout() {
        return this.layout;
    }
}
