package io.github.crafteve.idlefactory.client.screen;

import java.util.ArrayList;
import java.util.List;

import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 左表渲染：表头、机器行、展开区、底部统计和表框。
 *
 * <p>行高由 {@link FactoryScreenGeometry#detailHeight} 决定，绘制和命中共用同一套计算。
 * 渲染器只读状态/几何，不写滚动位置，也不发包。</p>
 *
 * <p>P1 删除产线后左表逐台铺开：不再有「新增机器」行，也不再有「移出产线」按钮。</p>
 */
final class FactoryTableRender {
    private final FactoryScreenContext context;

    /**
     * @param context 界面只读视图
     */
    FactoryTableRender(FactoryScreenContext context) {
        this.context = context;
    }

    /**
     * 画左侧机器表。
     *
     * @param graphics 画布
     */
    void drawTable(GuiGraphics graphics) {
        FactoryLayout l = this.context.layout();
        FactoryScreenState state = this.context.state();
        int headerY = l.tableHeaderY + (FactoryLayout.TABLE_HEADER_H - 9) / 2 + 1;
        String[] headers = { "机器", "状态", "输入", "输出" };
        // 显示顺序 → 逻辑列下标；窄屏时输入/输出可能被丢，不能直接拿 slot 当列号。
        for (int slot = 0; slot < l.columnCount; slot++) {
            int id = l.columnId(slot);
            int color = id == FactoryLayout.COL_INPUT || id == FactoryLayout.COL_OUTPUT
                    ? this.context.theme().textValue() : this.context.theme().textPrimary();
            graphics.drawString(this.context.font(), headers[id], l.columnX(id) + 4, headerY,
                    color, false);
        }

        List<FactoryMachineStack> table = state.tableStacks;
        int total = 0;
        int shown = 0;
        int y = l.rowTop;
        for (int i = state.firstRow; i < table.size(); i++) {
            if (y + FactoryLayout.ROW_H > l.footerY) {
                break;
            }
            FactoryMachineStack stack = table.get(i);
            int detailHeight = this.context.geometry().detailHeight(stack, y);
            drawRow(graphics, stack, y, detailHeight);
            y += FactoryLayout.ROW_H + detailHeight;
            shown++;
        }
        if (table.isEmpty()) {
            graphics.drawString(this.context.font(),
                    state.hasFactory ? "还没有机器 · 用 /idf factory connect 接入" : "先执行 /idf create",
                    l.tableX, l.footerY + 3, this.context.theme().textDim(), false);
            drawTableFrame(graphics);
            return;
        }

        int ready = 0;
        int unprobed = 0;
        int missing = 0;
        for (FactoryMachineStack stack : table) {
            total += stack.count();
            for (FactorySnapshotBuilder.MachineView machine : stack.members()) {
                if (!machine.probed()) {
                    unprobed++;
                } else if (machine.stale() || !machine.health().isOnline()) {
                    missing++;
                } else {
                    ready++;
                }
            }
        }
        List<FactoryUiText.TextPart> footer = new ArrayList<>();
        footer.add(new FactoryUiText.TextPart("共 ", this.context.theme().textPrimary()));
        footer.add(new FactoryUiText.TextPart(Integer.toString(total),
                this.context.theme().textPrimary()));
        footer.add(new FactoryUiText.TextPart(" 台", this.context.theme().textPrimary()));
        footer.add(new FactoryUiText.TextPart(" · ", this.context.theme().textPrimary()));
        footer.add(new FactoryUiText.TextPart("就绪 ", this.context.theme().statusRun()));
        footer.add(new FactoryUiText.TextPart(Integer.toString(ready),
                this.context.theme().statusRun()));
        footer.add(new FactoryUiText.TextPart(" · ", this.context.theme().textPrimary()));
        footer.add(new FactoryUiText.TextPart("未探测 ", this.context.theme().statusWarn()));
        footer.add(new FactoryUiText.TextPart(Integer.toString(unprobed),
                this.context.theme().statusWarn()));
        footer.add(new FactoryUiText.TextPart(" · ", this.context.theme().textPrimary()));
        footer.add(new FactoryUiText.TextPart("失联 ", this.context.theme().error()));
        footer.add(new FactoryUiText.TextPart(Integer.toString(missing),
                this.context.theme().error()));
        if (state.maxFirstRow() > 0) {
            footer.add(new FactoryUiText.TextPart(" · ", this.context.theme().textPrimary()));
            footer.add(new FactoryUiText.TextPart(Integer.toString(state.firstRow + 1),
                    this.context.theme().textValue()));
            footer.add(new FactoryUiText.TextPart("-", this.context.theme().textPrimary()));
            footer.add(new FactoryUiText.TextPart(Integer.toString(state.firstRow + shown),
                    this.context.theme().textValue()));
        }
        FactoryUiText.drawParts(graphics, this.context.font(), footer, l.tableX, l.footerY + 3);
        drawTableFrame(graphics);
    }

    /**
     * 画一行机器。
     *
     * <p>图标、名字、坐标取锚点；状态和速率是整行聚合；规则条用整行累加速率。</p>
     *
     * @param graphics     画布
     * @param stack        机器行
     * @param y            行顶部
     * @param detailHeight 展开区高度；0 表示未展开
     */
    private void drawRow(GuiGraphics graphics, FactoryMachineStack stack, int y,
            int detailHeight) {
        FactoryLayout l = this.context.layout();
        FactoryScreenState state = this.context.state();
        FactoryScreenInteraction interaction = this.context.interaction();
        FactorySnapshotBuilder.MachineView anchor = stack.anchor();
        int textY = y + (FactoryLayout.ROW_H - 9) / 2;
        FactoryStatus status = stack.status(this.context.theme());

        FactoryNineSlice.card(graphics, l.leftX + 2, y, l.leftW - 4,
                FactoryLayout.ROW_H - 1);
        // 1px 状态条：从卡片顶部内缘一直贯穿到底部内缘，颜色跟随总状态。
        FactoryNineSlice.fill(graphics, status.strip(), l.leftX + 3, y + 1, 1,
                FactoryLayout.ROW_H - 2);

        int iconX = l.columnX(FactoryLayout.COL_NAME) + 5;
        FactoryNineSlice.slot(graphics, iconX - 1, y + 4, FactoryLayout.CELL,
                FactoryLayout.CELL);
        ItemStack icon = !anchor.health().isOnline()
                ? new ItemStack(Items.BARRIER)
                : FactoryLabels.blockStack(anchor.blockId());
        graphics.renderItem(icon.isEmpty() ? new ItemStack(Items.BARRIER) : icon,
                iconX, y + 5);

        String name = !anchor.health().isOnline()
                ? "已失联" : FactoryLabels.blockName(anchor.blockId());
        int nameWidth = l.columnW(FactoryLayout.COL_NAME) - FactoryLayout.CELL - 10;
        graphics.drawString(this.context.font(),
                this.context.font().plainSubstrByWidth(name, nameWidth),
                iconX + FactoryLayout.CELL + 2, y + 4,
                this.context.theme().textPrimary(), false);
        String where = anchor.dimension().location() + " "
                + anchor.pos().toShortString();
        graphics.drawString(this.context.font(),
                this.context.font().plainSubstrByWidth(where, nameWidth),
                iconX + FactoryLayout.CELL + 2, y + 14,
                this.context.theme().textDim(), false);

        int stateX = l.columnX(FactoryLayout.COL_STATE) + 4;
        FactoryNineSlice.icon(graphics, status.lamp(), stateX, textY + 2,
                FactoryGuiTextures.STATUS_LAMP_SIZE);
        String statusLabel = stack.statusText(this.context.theme());
        int statusWidth = l.columnW(FactoryLayout.COL_STATE) - 14;
        graphics.drawString(this.context.font(),
                this.context.font().plainSubstrByWidth(statusLabel, statusWidth),
                stateX + 9, textY, status.color(), false);

        boolean draggingResource = interaction != null && interaction.isDraggingResource();
        if (l.hasColumn(FactoryLayout.COL_INPUT)) {
            int stripX = l.columnX(FactoryLayout.COL_INPUT) + 2;
            FactoryRuleStrip.Layout strip = FactoryRuleStrip.layout(this.context.font(),
                    stack.inputs(), stack.inputRates(), stripX, y + 4,
                    l.columnW(FactoryLayout.COL_INPUT) - 4, true);
            FactoryRuleStrip.draw(graphics, this.context.font(), this.context.theme(), strip,
                    stack.inputs(), stack.inputRates(), anchor.inputReasons(),
                    this.context.mouseX(), this.context.mouseY(), draggingResource, false);
        }
        if (l.hasColumn(FactoryLayout.COL_OUTPUT)) {
            int stripX = l.columnX(FactoryLayout.COL_OUTPUT) + 2;
            FactoryRuleStrip.Layout strip = FactoryRuleStrip.layout(this.context.font(),
                    stack.outputs(), stack.outputRates(), stripX, y + 4,
                    l.columnW(FactoryLayout.COL_OUTPUT) - 4 - FactoryLayout.ROW_ACTIONS_W, true);
            FactoryRuleStrip.draw(graphics, this.context.font(), this.context.theme(), strip,
                    stack.outputs(), stack.outputRates(), anchor.outputReasons(),
                    this.context.mouseX(), this.context.mouseY(), draggingResource, false);
        }
        drawRowActions(graphics, y, this.context.geometry().isOpen(stack));
        if (detailHeight > 0) {
            FactoryIoDetailLayout.Detail detail = FactoryIoDetailLayout.layout(l, anchor,
                    y + FactoryLayout.ROW_H, detailHeight, this.context.font(),
                    state.ioScroll);
            FactoryIoDetailRender.draw(graphics, this.context.font(), this.context.theme(), l,
                    anchor, detail, this.context.mouseX(), this.context.mouseY(),
                    draggingResource, null);
        }
    }

    /**
     * 行尾动作按钮：展开/收起 IO。
     *
     * <p>P1 删除产线后行尾只剩展开按钮：原来「移出产线」依赖产线引用，已随产线删除。</p>
     *
     * @param graphics 画布
     * @param y        行顶部
     * @param open     展开区是否已打开
     */
    private void drawRowActions(GuiGraphics graphics, int y, boolean open) {
        int buttonY = y + FactoryLayout.ROW_ACTION_TOP;
        int expandX = this.context.geometry().expandButtonX();
        boolean expandHovered = FactoryScreenGeometry.inside(this.context.mouseX(),
                this.context.mouseY(), expandX, buttonY,
                FactoryLayout.ROW_ACTION_BUTTON, FactoryLayout.ROW_ACTION_BUTTON);
        drawRowActionButton(graphics, expandX, buttonY,
                open ? FactoryGuiTextures.CHEVRON_DOWN : FactoryGuiTextures.CHEVRON_RIGHT,
                expandHovered);
    }

    /**
     * 画一枚行尾动作按钮：凸起底板 + 7×7 图标。
     *
     * @param graphics 画布
     * @param x        左边缘
     * @param y        上边缘
     * @param icon     7×7 图标
     * @param hovered  是否悬停
     */
    private void drawRowActionButton(GuiGraphics graphics, int x, int y,
            ResourceLocation icon, boolean hovered) {
        if (hovered) {
            FactoryNineSlice.buttonHover(graphics, x, y,
                    FactoryLayout.ROW_ACTION_BUTTON, FactoryLayout.ROW_ACTION_BUTTON);
        } else {
            FactoryNineSlice.button(graphics, x, y,
                    FactoryLayout.ROW_ACTION_BUTTON, FactoryLayout.ROW_ACTION_BUTTON);
        }
        int offset = (FactoryLayout.ROW_ACTION_BUTTON
                - FactoryGuiTextures.ROW_ACTION_ICON_SIZE) / 2;
        FactoryNineSlice.icon(graphics, icon, x + offset, y + offset,
                FactoryGuiTextures.ROW_ACTION_ICON_SIZE);
    }

    /**
     * 用当前布局的位置画整张表的外框。
     *
     * @param graphics 画布
     */
    private void drawTableFrame(GuiGraphics graphics) {
        FactoryLayout l = this.context.layout();
        int tableBottom = l.footerY + FactoryLayout.FOOTER_H;
        drawTableFrame(graphics, l.leftX, l.tableHeaderY, l.leftW,
                tableBottom - l.tableHeaderY);
    }

    /**
     * 画左侧表格的 2px 外框。
     *
     * @param graphics 画布
     * @param x        外框左边缘
     * @param y        外框上边缘
     * @param width    外框宽
     * @param height   外框高
     */
    private void drawTableFrame(GuiGraphics graphics, int x, int y, int width, int height) {
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.DIVIDER_DARK, x, y, width, 1);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.DIVIDER_DARK, x, y + height - 1,
                width, 1);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.DIVIDER_DARK, x, y, 1, height);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.DIVIDER_DARK, x + width - 1, y,
                1, height);

        FactoryNineSlice.fill(graphics, FactoryGuiTextures.TABLE_LIGHT, x + 1, y + 1,
                width - 2, 1);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.TABLE_LIGHT, x + 1, y + 1, 1,
                height - 2);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.TABLE_LIGHT, x + 1, y + height - 2,
                width - 2, 1);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.TABLE_LIGHT, x + width - 2, y + 1,
                1, height - 2);
    }
}
