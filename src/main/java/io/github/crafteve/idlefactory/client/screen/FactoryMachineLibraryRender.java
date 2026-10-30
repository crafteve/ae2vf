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
     * 机器库：按组画深色卡片，一组一行。
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
            if (index >= state.libraryStacks.size()) {
                break;
            }
            FactoryMachineStack stack = state.libraryStacks.get(index);
            int y = l.gridY + i * FactoryLayout.ROW_H;
            drawMachineCard(graphics, stack, l.gridX, y, width);
        }
        if (state.machines.isEmpty()) {
            drawMachineLibraryEmpty(graphics, "没有机器");
        } else if (state.libraryStacks.isEmpty()) {
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
     * 单张机器卡片；一组机器只画一张。
     *
     * @param graphics 画布
     * @param stack    机器组
     * @param x        卡片行左边缘
     * @param y        卡片行顶部
     * @param width    卡片行总宽
     */
    private void drawMachineCard(GuiGraphics graphics, FactoryMachineStack stack, int x, int y,
            int width) {
        FactorySnapshotBuilder.MachineView anchor = stack.anchor();
        int cardX = x + 2;
        int cardY = y + 1;
        int cardW = width - 4;
        int cardH = FactoryLayout.ROW_H - 3;

        // 右下阴影 + 深钢灰卡片，做出卡片浮在面板上的层次。
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.DIVIDER_DARK,
                cardX + 2, cardY + 2, cardW, cardH);
        FactoryNineSlice.machineCard(graphics, cardX, cardY, cardW, cardH);

        FactoryStatus status = stack.status(this.context.theme());
        FactoryNineSlice.fill(graphics, status.strip(), cardX + 1, cardY + 2,
                2, cardH - 4);

        int iconSlotX = cardX + 4;
        int iconSlotY = cardY + 2;
        FactoryNineSlice.slot(graphics, iconSlotX, iconSlotY,
                FactoryLayout.CELL, FactoryLayout.CELL);
        ItemStack icon = anchor.machineMissing()
                ? new ItemStack(Items.BARRIER)
                : FactoryLabels.blockStack(anchor.blockId());
        graphics.renderItem(icon.isEmpty() ? new ItemStack(Items.BARRIER) : icon,
                iconSlotX + 1, iconSlotY + 1);
        drawCountBadge(graphics, stack, iconSlotX + 1, iconSlotY + 1);

        int textX = iconSlotX + FactoryLayout.CELL + 5;
        int right = cardX + cardW - 5;
        int nameY = cardY + 3;
        int lineY = cardY + 13;

        String statusText = stack.statusText(this.context.theme());
        int statusWidth = this.context.font().width(statusText);
        int statusX = right - statusWidth;
        int lampX = Math.max(textX, statusX - 7);
        FactoryNineSlice.icon(graphics, status.lamp(), lampX, nameY + 2,
                FactoryGuiTextures.STATUS_LAMP_SIZE);

        int nameWidth = Math.max(18, lampX - textX - 4);
        String cardName = anchor.machineMissing()
                ? "已失联" : FactoryLabels.blockName(anchor.blockId());
        graphics.drawString(this.context.font(),
                this.context.font().plainSubstrByWidth(cardName, nameWidth),
                textX, nameY, CARD_NAME, false);
        graphics.drawString(this.context.font(), statusText, statusX, nameY,
                status.color(), false);

        String lines = anchor.lineNames().isEmpty()
                ? "未编线" : String.join(" / ", anchor.lineNames());
        String rulesLabel = "规则 ";
        String rulesValue = Integer.toString(anchor.ruleCount());
        int rulesWidth = this.context.font().width(rulesLabel)
                + this.context.font().width(rulesValue);
        int deleteX = cardX + cardW - 12;
        int rulesX = deleteX - 6 - rulesWidth;
        int lineWidth = Math.max(12, rulesX - 6 - textX);
        graphics.drawString(this.context.font(),
                this.context.font().plainSubstrByWidth(lines, lineWidth),
                textX, lineY,
                anchor.lineNames().isEmpty()
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
        if (interaction != null && interaction.isDragSource(anchor.ref())) {
            graphics.fill(cardX, cardY, cardX + cardW, cardY + cardH, 0x66000000);
        }
    }

    /**
     * 在图标右下角画整组台数，样式跟原版物品数量一致。
     *
     * @param graphics 画布
     * @param stack    机器组
     * @param iconX    图标左边缘
     * @param iconY    图标上边缘
     */
    private void drawCountBadge(GuiGraphics graphics, FactoryMachineStack stack, int iconX,
            int iconY) {
        int count = this.context.state().displayCount(stack);
        if (count <= 1) {
            return;
        }
        String label = "×" + count;
        int textWidth = this.context.font().width(label);
        // 图标画在 ITEM(150) 层，这里必须抬到 GRID_COUNT(300)，否则数量会被图标盖住。
        graphics.pose().pushPose();
        graphics.pose().translate(iconX + FactoryLayout.CELL - 2 - textWidth,
                iconY + FactoryLayout.CELL - 8, FactoryGuiLayer.GRID_COUNT.z());
        graphics.drawString(this.context.font(), label, 0, 0, 0xFFFFFFFF, true);
        graphics.pose().popPose();
    }
}
