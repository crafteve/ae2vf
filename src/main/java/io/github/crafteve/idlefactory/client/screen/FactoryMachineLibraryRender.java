package io.github.crafteve.idlefactory.client.screen;

import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 机器库：深色机器卡片、空状态提示与卡片内部排布。
 *
 * <p>只读当前过滤/排序后的机器视图和滚动位置；卡片内按名称、产线、状态、规则数
 * 四块排文字，所有坐标与命中都由 {@link FactoryScreenGeometry} 给出。</p>
 */
final class FactoryMachineLibraryRender {
    /** 机器卡片第一行文字：机器名，使用接近白色的冷色。 */
    private static final int CARD_NAME = 0xFFE8ECF5;
    /** 机器卡片第二行右侧：规则标签，使用灰蓝色。 */
    private static final int CARD_META = 0xFF98A2B8;

    private final FactoryScreenContext context;

    /**
     * @param context 界面只读视图
     */
    FactoryMachineLibraryRender(FactoryScreenContext context) {
        this.context = context;
    }

    /**
     * 机器库：按行画深色卡片，每行一台机器。
     *
     * @param graphics 画布
     */
    void draw(GuiGraphics graphics) {
        FactoryScreenState state = this.context.state();
        FactoryLayout l = this.context.layout();
        int width = FactoryLayout.GRID_COLS * FactoryLayout.CELL;
        int rows = state.machineRowsVisible();
        for (int i = 0; i < rows; i++) {
            int index = state.machineFirstRow + i;
            if (index >= state.filteredMachines.size()) {
                break;
            }
            FactorySnapshotBuilder.MachineView machine = state.filteredMachines.get(index);
            int y = l.gridY + i * FactoryLayout.ROW_H;
            drawMachineCard(graphics, machine, l.gridX, y, width);
        }
        if (state.machines.isEmpty()) {
            drawMachineLibraryEmpty(graphics, "没有机器");
        } else if (state.filteredMachines.isEmpty()) {
            drawMachineLibraryEmpty(graphics, "没有匹配");
        }
    }

    /**
     * 机器库空提示：水平居中于右区。
     *
     * @param graphics 画布
     * @param text     空状态文字
     */
    private void drawMachineLibraryEmpty(GuiGraphics graphics, String text) {
        FactoryLayout l = this.context.layout();
        int x = l.rightX + (l.rightW - this.context.font().width(text)) / 2;
        int y = l.gridY + l.gridRows * FactoryLayout.CELL / 2 - 4;
        graphics.drawString(this.context.font(), text, x, y,
                this.context.theme().textDim(), false);
    }

    /**
     * 单张机器卡片。
     *
     * @param graphics 画布
     * @param machine  机器视图
     * @param x        卡片行左边缘
     * @param y        卡片行顶部
     * @param width    卡片行总宽
     */
    private void drawMachineCard(GuiGraphics graphics,
            FactorySnapshotBuilder.MachineView machine, int x, int y, int width) {
        int cardX = x + 2;
        int cardY = y + 1;
        int cardW = width - 4;
        int cardH = FactoryLayout.ROW_H - 3;

        // 右下阴影 + 深钢灰卡片，做出卡片浮在面板上的层次。
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.DIVIDER_DARK,
                cardX + 2, cardY + 2, cardW, cardH);
        FactoryNineSlice.machineCard(graphics, cardX, cardY, cardW, cardH);

        FactoryStatus status = FactoryStatus.of(machine, this.context.theme());
        FactoryNineSlice.fill(graphics, status.strip(), cardX + 1, cardY + 2,
                2, cardH - 4);

        int iconSlotX = cardX + 4;
        int iconSlotY = cardY + 2;
        FactoryNineSlice.slot(graphics, iconSlotX, iconSlotY,
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
        int statusWidth = this.context.font().width(statusText);
        int statusX = right - statusWidth;
        int lampX = Math.max(textX, statusX - 7);
        FactoryNineSlice.icon(graphics, status.lamp(), lampX, nameY + 2,
                FactoryGuiTextures.STATUS_LAMP_SIZE);

        int nameWidth = Math.max(18, lampX - textX - 4);
        String cardName = machine.machineMissing()
                ? "已失联" : FactoryLabels.blockName(machine.blockId());
        graphics.drawString(this.context.font(),
                this.context.font().plainSubstrByWidth(cardName, nameWidth),
                textX, nameY, CARD_NAME, false);
        graphics.drawString(this.context.font(), statusText, statusX, nameY,
                status.color(), false);

        String lines = machine.lineNames().isEmpty()
                ? "未编线" : String.join(" / ", machine.lineNames());
        String rulesLabel = "规则 ";
        String rulesValue = Integer.toString(machine.ruleCount());
        int rulesWidth = this.context.font().width(rulesLabel)
                + this.context.font().width(rulesValue);
        int deleteX = cardX + cardW - 12;
        int rulesX = deleteX - 6 - rulesWidth;
        int lineWidth = Math.max(12, rulesX - 6 - textX);
        graphics.drawString(this.context.font(),
                this.context.font().plainSubstrByWidth(lines, lineWidth),
                textX, lineY,
                machine.lineNames().isEmpty()
                        ? this.context.theme().textDim() : this.context.theme().textValue(),
                false);
        graphics.drawString(this.context.font(), rulesLabel, rulesX, lineY, CARD_META, false);
        graphics.drawString(this.context.font(), rulesValue,
                rulesX + this.context.font().width(rulesLabel), lineY,
                this.context.theme().textValue(), false);
        boolean deleteHovered = this.context.mouseX() >= deleteX
                && this.context.mouseX() < deleteX + 10
                && this.context.mouseY() >= lineY - 1
                && this.context.mouseY() < lineY + 9;
        graphics.drawString(this.context.font(), "×", deleteX, lineY,
                deleteHovered ? this.context.theme().error()
                        : this.context.theme().textDim(), false);

        FactoryScreenInteraction interaction = this.context.interaction();
        if (interaction != null && interaction.isDragSource(machine.ref())) {
            graphics.fill(cardX, cardY, cardX + cardW, cardY + cardH, 0x66000000);
        }
    }
}
