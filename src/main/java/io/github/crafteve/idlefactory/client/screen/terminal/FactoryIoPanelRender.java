package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.client.screen.FactoryGuiTextures;
import io.github.crafteve.idlefactory.client.screen.FactoryLabels;
import io.github.crafteve.idlefactory.client.screen.FactoryNineSlice;
import io.github.crafteve.idlefactory.client.screen.FactoryResourceIconRender;
import io.github.crafteve.idlefactory.client.screen.FactoryStatus;
import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.factory.io.IoSide;
import io.github.crafteve.idlefactory.factory.machine.ResourceSpec;

import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 被动 IO 面板的绘制与几何：搜索在顶栏，机器按样板管理终端的样式铺开。
 *
 * <p>每台机器一行标题（图标 + 名字），下面固定是输入槽位行和输出槽位行，槽位左边一个
 * 小圆点区分输入 / 输出；一行 9 格，规则放满自动加行。不展开、不画卡片，状态与速率
 * 全在 tooltip。中键槽位弹配置框。</p>
 */
final class FactoryIoPanelRender {
    /** 机器标题行高。 */
    static final int MACHINE_H = 14;
    /** 槽位边长。 */
    static final int SLOT = 18;
    /** 一行槽位数。 */
    static final int COLS = 9;
    /** 输入 / 输出行之间的间距。 */
    static final int GROUP_GAP = 2;
    /** 机器之间的间距。 */
    static final int MACHINE_GAP = 6;
    /** 配置框宽度。 */
    static final int POPUP_W = 126;
    /** 配置框行高。 */
    static final int POPUP_ROW = 16;

    /** 槽位命中。 */
    record SlotHit(int machineIndex, boolean input, int ruleIndex, boolean empty, int x, int y) {
    }

    /** 一台机器的纵向布局。 */
    private record MachineRow(FactorySnapshotBuilder.MachineView machine, int index, int headerY,
            int inputY, int inputLines, int outputY, int outputLines, int endY) {
    }

    private final FactoryIoPanel panel;

    /**
     * @param panel 所属面板
     */
    FactoryIoPanelRender(FactoryIoPanel panel) {
        this.panel = panel;
    }

    /**
     * 画顶栏搜索与机器列表。
     *
     * @param graphics 绘制器
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     */
    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        this.renderHeader(graphics);
        if (!this.panel.hasFactory) {
            graphics.drawString(this.panel.font, "还没有工厂", this.contentLeft(),
                    this.listTop(), this.panel.theme.textDim(), false);
            return;
        }
        if (this.panel.visible.isEmpty()) {
            graphics.drawString(this.panel.font, "机器库为空", this.contentLeft(),
                    this.listTop(), this.panel.theme.textDim(), false);
            return;
        }
        graphics.enableScissor(this.contentLeft(), this.listTop(),
                this.contentLeft() + this.contentWidth(), this.listBottom());
        for (MachineRow row : this.machineRows()) {
            this.drawMachine(graphics, row, mouseX, mouseY);
        }
        graphics.disableScissor();
        this.renderFeedback(graphics);
    }

    /**
     * tooltip 与中键配置框。
     *
     * @param graphics 绘制器
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     */
    void renderForeground(GuiGraphics graphics, int mouseX, int mouseY) {
        if (this.panel.popupOpen()) {
            this.drawPopup(graphics);
            return;
        }
        List<Component> lines = this.tooltipAt(mouseX, mouseY);
        if (!lines.isEmpty()) {
            graphics.renderTooltip(this.panel.font,
                    lines.stream().map(Component::getVisualOrderText).toList(), mouseX, mouseY);
        }
    }

    /**
     * 画顶栏搜索框。
     *
     * <p>底板、尺寸、位置和文字色全部照 AE2 终端的搜索框来：同一张
     * {@code text_field.png}、同一个 89×12 尺寸、同一组调色板颜色。标题由
     * {@link FactoryTerminalPanel#renderBackground} 统一画一次，这里不再重复，
     * 否则两处各画一遍会错开 1px 变成虚影。</p>
     *
     * @param graphics 绘制器
     */
    private void renderHeader(GuiGraphics graphics) {
        int x = this.left() + FactoryTerminalGeometry.SEARCH_LEFT;
        int y = this.top() + FactoryTerminalGeometry.SEARCH_TOP;
        FactoryNineSlice.ae2TextField(graphics, x, y, FactoryTerminalGeometry.SEARCH_WIDTH);
        boolean empty = this.panel.searchText.isEmpty() && !this.panel.searchFocused;
        String text = empty ? "搜索" : this.panel.searchText;
        int color = empty ? FactoryTerminalGeometry.SEARCH_PLACEHOLDER
                : FactoryTerminalGeometry.SEARCH_TEXT;
        graphics.drawString(this.panel.font, text, x + 2, y + 2, color, false);
        if (this.panel.searchFocused && (Util.getMillis() / 500L) % 2L == 0L) {
            int caretX = x + 2 + this.panel.font.width(this.panel.searchText);
            graphics.fill(caretX, y + 2, caretX + 1, y + 10, FactoryTerminalGeometry.SEARCH_TEXT);
        }
    }

    private void drawMachine(GuiGraphics graphics, MachineRow row, int mouseX, int mouseY) {
        FactorySnapshotBuilder.MachineView machine = row.machine();
        int x = this.contentLeft();
        int width = this.contentWidth();
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= row.headerY()
                && mouseY < row.headerY() + MACHINE_H;
        if (hovered) {
            graphics.fill(x, row.headerY(), x + width, row.headerY() + MACHINE_H,
                    FactoryTerminalGeometry.ROW_HOVER);
        }
        FactoryNineSlice.slot(graphics, x, row.headerY() - 1, 16, 16);
        ItemStack icon = FactoryLabels.blockStack(machine.blockId());
        if (!icon.isEmpty()) {
            graphics.renderItem(icon, x, row.headerY() - 1);
        }
        FactoryStatus status = FactoryStatus.of(machine, this.panel.theme);
        FactoryNineSlice.icon(graphics, status.lamp(), x + 18, row.headerY() + 3,
                FactoryGuiTextures.STATUS_LAMP_SIZE);
        graphics.drawString(this.panel.font,
                trim(this.panel.font, FactoryLabels.blockName(machine.blockId()), width - 30),
                x + 25, row.headerY() + 1, this.panel.theme.textPrimary(), false);
        this.drawGroup(graphics, row, true, mouseX, mouseY);
        this.drawGroup(graphics, row, false, mouseX, mouseY);
    }

    private void drawGroup(GuiGraphics graphics, MachineRow row, boolean input, int mouseX,
            int mouseY) {
        List<ResourceSpec> rules = input ? row.machine().inputs() : row.machine().outputs();
        int x = this.contentLeft();
        int y = input ? row.inputY() : row.outputY();
        int lines = input ? row.inputLines() : row.outputLines();
        int color = input ? this.panel.theme.accent() : this.panel.theme.statusWarn();
        // 输入 / 输出标识：整组左侧一条竖条，和槽位行同高。
        graphics.fill(x, y, x + 3, y + lines * SLOT, color);
        for (int line = 0; line < lines; line++) {
            for (int col = 0; col < COLS; col++) {
                int index = line * COLS + col;
                int slotX = x + 6 + col * SLOT;
                int slotY = y + line * SLOT;
                FactoryNineSlice.slot(graphics, slotX, slotY, SLOT, SLOT);
                if (index > rules.size()) {
                    continue;
                }
                boolean hover = mouseX >= slotX && mouseX < slotX + SLOT
                        && mouseY >= slotY && mouseY < slotY + SLOT;
                if (hover && index <= rules.size()) {
                    graphics.fill(slotX + 1, slotY + 1, slotX + SLOT - 1, slotY + SLOT - 1,
                            FactoryTerminalGeometry.ROW_HOVER);
                }
                if (index == rules.size()) {
                    graphics.drawString(this.panel.font, "+", slotX + 5, slotY + 5,
                            hover ? this.panel.theme.accent() : this.panel.theme.textDim(), false);
                } else {
                    FactoryResourceIconRender.draw(graphics, this.panel.font,
                            rules.get(index).key(), slotX + 1, slotY + 1);
                }
            }
        }
    }

    private void drawPopup(GuiGraphics graphics) {
        ResourceSpec rule = this.panel.popupRule();
        if (rule == null) {
            return;
        }
        int x = this.panel.popupX();
        int y = this.panel.popupY();
        graphics.fill(x, y, x + POPUP_W, y + POPUP_ROW * 3 + 6, 0xF0101820);
        graphics.renderOutline(x, y, POPUP_W, POPUP_ROW * 3 + 6, 0xFFE8ECF5);
        graphics.drawString(this.panel.font, "面：" + FactoryLabels.face(rule), x + 4, y + 4,
                0xFFE8ECF5, false);
        graphics.drawString(this.panel.font,
                "匹配：" + (rule.mode().name().equals("IGNORE_COMPONENTS") ? "模糊" : "精准"),
                x + 4, y + 4 + POPUP_ROW, 0xFFE8ECF5, false);
        graphics.drawString(this.panel.font, "删除", x + 4, y + 4 + POPUP_ROW * 2,
                this.panel.theme.error(), false);
    }

    private void renderFeedback(GuiGraphics graphics) {
        if (this.panel.feedback == null || Util.getMillis() > this.panel.feedbackUntil) {
            return;
        }
        int color = this.panel.feedbackOk ? this.panel.theme.statusRun() : this.panel.theme.error();
        graphics.drawString(this.panel.font,
                trim(this.panel.font, this.panel.feedback, this.contentWidth() - 4),
                this.contentLeft(), this.panel.bottom() - 12, color, false);
    }

    // ---- 命中 ----

    /**
     * 命中的槽位。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @return 槽位；没命中为 {@code null}
     */
    @Nullable
    SlotHit slotAt(double mouseX, double mouseY) {
        for (MachineRow row : this.machineRows()) {
            for (boolean input : new boolean[] { true, false }) {
                List<ResourceSpec> rules = input ? row.machine().inputs()
                        : row.machine().outputs();
                int y = input ? row.inputY() : row.outputY();
                int lines = input ? row.inputLines() : row.outputLines();
                for (int line = 0; line < lines; line++) {
                    for (int col = 0; col < COLS; col++) {
                        int index = line * COLS + col;
                        if (index > rules.size()) {
                            continue;
                        }
                        int slotX = this.contentLeft() + 6 + col * SLOT;
                        int slotY = y + line * SLOT;
                        if (mouseX >= slotX && mouseX < slotX + SLOT && mouseY >= slotY
                                && mouseY < slotY + SLOT) {
                            return new SlotHit(row.index(), input, index,
                                    index == rules.size(), slotX, slotY);
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 命中的机器标题行。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @return 机器下标；没命中为 {@code -1}
     */
    int machineAt(double mouseX, double mouseY) {
        if (mouseX < this.contentLeft()
                || mouseX >= this.contentLeft() + this.contentWidth()) {
            return -1;
        }
        for (MachineRow row : this.machineRows()) {
            if (mouseY >= row.headerY() && mouseY < row.headerY() + MACHINE_H) {
                return row.index();
            }
        }
        return -1;
    }

    /**
     * 配置框点击结果。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @return 选项下标；外面为 {@code -1}
     */
    int popupHit(double mouseX, double mouseY) {
        if (!this.panel.popupOpen()) {
            return -1;
        }
        int x = this.panel.popupX();
        int y = this.panel.popupY();
        if (mouseX < x || mouseX >= x + POPUP_W || mouseY < y
                || mouseY >= y + POPUP_ROW * 3 + 6) {
            return -1;
        }
        return Math.min(2, (int) ((mouseY - y - 2) / POPUP_ROW));
    }

    boolean inSearch(double mouseX, double mouseY) {
        int x = this.left() + FactoryTerminalGeometry.SEARCH_LEFT;
        int y = this.top() + FactoryTerminalGeometry.SEARCH_TOP;
        return mouseX >= x && mouseX < x + FactoryTerminalGeometry.SEARCH_WIDTH
                && mouseY >= y && mouseY < y + FactoryTerminalGeometry.SEARCH_HEIGHT;
    }

    boolean inList(double mouseX, double mouseY) {
        return mouseX >= this.contentLeft()
                && mouseX < this.contentLeft() + this.contentWidth()
                && mouseY >= this.listTop() && mouseY < this.listBottom();
    }

    /**
     * 列表内容总高，用于滚动夹紧。
     *
     * @return 高度
     */
    int contentHeight() {
        List<MachineRow> rows = this.machineRows();
        if (rows.isEmpty()) {
            return 0;
        }
        return rows.get(rows.size() - 1).endY() - this.listTop() + this.panel.scroll;
    }

    // ---- 几何 ----

    private List<MachineRow> machineRows() {
        List<MachineRow> rows = new ArrayList<>();
        int y = this.listTop() - this.panel.scroll;
        for (int i = 0; i < this.panel.visible.size(); i++) {
            FactorySnapshotBuilder.MachineView machine = this.panel.visible.get(i);
            int headerY = y;
            int inputLines = Math.max(1,
                    (machine.inputs().size() + 1 + COLS - 1) / COLS);
            int inputY = headerY + MACHINE_H + 1;
            int outputLines = Math.max(1,
                    (machine.outputs().size() + 1 + COLS - 1) / COLS);
            int outputY = inputY + inputLines * SLOT + GROUP_GAP;
            int endY = outputY + outputLines * SLOT + MACHINE_GAP;
            rows.add(new MachineRow(machine, i, headerY, inputY, inputLines, outputY,
                    outputLines, endY));
            y = endY;
        }
        return rows;
    }

    private int left() {
        return this.panel.left();
    }

    private int top() {
        return this.panel.top();
    }

    int contentLeft() {
        return this.left() + FactoryTerminalGeometry.CONTENT_PADDING;
    }

    int contentWidth() {
        return FactoryTerminalGeometry.contentWidth();
    }

    int listTop() {
        return this.top() + FactoryTerminalGeometry.HEADER_HEIGHT + 4;
    }

    int listBottom() {
        return this.panel.bottom() - FactoryTerminalGeometry.CONTENT_PADDING;
    }

    private List<Component> tooltipAt(double mouseX, double mouseY) {
        int machineIndex = this.machineAt(mouseX, mouseY);
        if (machineIndex >= 0 && machineIndex < this.panel.visible.size()) {
            FactorySnapshotBuilder.MachineView machine = this.panel.visible.get(machineIndex);
            FactoryStatus status = FactoryStatus.of(machine, this.panel.theme);
            return List.of(
                    Component.literal(FactoryLabels.blockName(machine.blockId())),
                    Component.literal(status.text()),
                    Component.literal("输入 " + machine.inputs().size() + " · 输出 "
                            + machine.outputs().size()),
                    Component.literal(machine.ref().dimension().location() + " "
                            + machine.ref().pos().toShortString()));
        }
        SlotHit hit = this.slotAt(mouseX, mouseY);
        if (hit == null || hit.empty()) {
            return List.of();
        }
        List<ResourceSpec> rules = hit.input() ? this.panel.visible.get(hit.machineIndex()).inputs()
                : this.panel.visible.get(hit.machineIndex()).outputs();
        if (hit.ruleIndex() >= rules.size()) {
            return List.of();
        }
        ResourceSpec rule = rules.get(hit.ruleIndex());
        return List.of(
                Component.literal((hit.input() ? "输入" : "输出") + " · "
                        + FactoryLabels.name(rule.key())),
                Component.literal("面：" + FactoryLabels.face(rule)),
                Component.literal("匹配："
                        + (rule.mode().name().equals("IGNORE_COMPONENTS") ? "模糊" : "精准")),
                Component.literal("中键配置"));
    }

    /**
     * 槽位对应的 JEI 目标矩形。
     *
     * @param hit 槽位
     * @return 矩形
     */
    Rect2i slotArea(SlotHit hit) {
        return new Rect2i(hit.x(), hit.y(), SLOT, SLOT);
    }

    /**
     * 可接收 JEI 拖入的分组区域：每台机器的输入行、输出行各一块。
     *
     * @return 区域
     */
    List<FactoryTerminalDropArea> dropAreas() {
        List<FactoryTerminalDropArea> areas = new ArrayList<>();
        for (MachineRow row : this.machineRows()) {
            int x = this.contentLeft() + 7;
            int width = COLS * SLOT;
            areas.add(new FactoryTerminalDropArea(new Rect2i(x, row.inputY(), width,
                    row.inputLines() * SLOT), IoSide.INPUT));
            areas.add(new FactoryTerminalDropArea(new Rect2i(x, row.outputY(), width,
                    row.outputLines() * SLOT), IoSide.OUTPUT));
        }
        return areas;
    }

    private static String trim(Font font, String text, int width) {
        if (width <= 0) {
            return "";
        }
        if (font.width(text) <= width) {
            return text;
        }
        String ellipsis = "…";
        int budget = width - font.width(ellipsis);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (font.width(builder.toString() + c) > budget) {
                break;
            }
            builder.append(c);
        }
        return builder + ellipsis;
    }
}
