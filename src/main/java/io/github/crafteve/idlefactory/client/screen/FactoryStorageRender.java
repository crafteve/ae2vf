package io.github.crafteve.idlefactory.client.screen;

import appeng.api.stacks.AEKey;
import io.github.crafteve.idlefactory.factory.capability.CapabilityKind;
import io.github.crafteve.idlefactory.network.payload.FactoryStorageS2C;

import net.minecraft.client.gui.GuiGraphics;
import io.github.crafteve.idlefactory.factory.storage.AeKeySupport;

/**
 * 仓库格的绘制：悬停底图、1px 描边、资源图标与右下角数量。
 *
 * <p>只读 {@link FactoryScreenState} 的可见条目和滚动位置；点击/手势发包在输入层，
 * 这里不处理。描边单独放在图标之后画，避免不透明的流体贴图把高亮盖掉。</p>
 */
final class FactoryStorageRender {
    private final FactoryScreenContext context;

    /**
     * @param context 界面只读视图
     */
    FactoryStorageRender(FactoryScreenContext context) {
        this.context = context;
    }

    /**
     * 画仓库 tab 的动态内容。
     *
     * @param graphics 画布
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     */
    void draw(GuiGraphics graphics, int mouseX, int mouseY) {
        drawStorageHover(graphics, mouseX, mouseY);
        drawStorageEntries(graphics);
        // 描边放在图标之后：流体贴图是不透明的，画在下面会被完全盖住。
        drawStorageHoverOutline(graphics, mouseX, mouseY);
    }

    /**
     * 仓库格的悬停底图。
     *
     * @param graphics 画布
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     */
    private void drawStorageHover(GuiGraphics graphics, int mouseX, int mouseY) {
        FactoryLayout l = this.context.layout();
        if (mouseX < l.gridX
                || mouseX >= l.gridX + FactoryLayout.GRID_COLS * FactoryLayout.CELL
                || mouseY < l.gridY
                || mouseY >= l.gridY + l.gridRows * FactoryLayout.CELL) {
            return;
        }
        int col = (mouseX - l.gridX) / FactoryLayout.CELL;
        int row = (mouseY - l.gridY) / FactoryLayout.CELL;
        FactoryNineSlice.icon(graphics, FactoryGuiTextures.SLOT_HOVER,
                l.gridX + col * FactoryLayout.CELL,
                l.gridY + row * FactoryLayout.CELL,
                FactoryGuiTextures.SLOT_HOVER_SIZE);
    }

    /**
     * 悬停格的 1px 描边。
     *
     * @param graphics 画布
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     */
    private void drawStorageHoverOutline(GuiGraphics graphics, int mouseX, int mouseY) {
        FactoryLayout l = this.context.layout();
        if (mouseX < l.gridX
                || mouseX >= l.gridX + FactoryLayout.GRID_COLS * FactoryLayout.CELL
                || mouseY < l.gridY
                || mouseY >= l.gridY + l.gridRows * FactoryLayout.CELL) {
            return;
        }
        int col = (mouseX - l.gridX) / FactoryLayout.CELL;
        int row = (mouseY - l.gridY) / FactoryLayout.CELL;
        int x = l.gridX + col * FactoryLayout.CELL;
        int y = l.gridY + row * FactoryLayout.CELL;
        int right = x + FactoryLayout.CELL;
        int bottom = y + FactoryLayout.CELL;
        int color = this.context.theme().hoverOutline();
        graphics.fill(x, y, right, y + 1, color);
        graphics.fill(x, bottom - 1, right, bottom, color);
        graphics.fill(x, y, x + 1, bottom, color);
        graphics.fill(right - 1, y, right, bottom, color);
    }

    /**
     * 画当前可见的仓库格。
     *
     * @param graphics 画布
     */
    private void drawStorageEntries(GuiGraphics graphics) {
        FactoryScreenState state = this.context.state();
        FactoryLayout l = this.context.layout();
        if (!state.storageReceived || !state.storageOnline) {
            return;
        }
        if (state.storageRepo.all().isEmpty() || state.filteredStorageEntries.isEmpty()) {
            return;
        }

        for (int row = 0; row < l.gridRows; row++) {
            for (int col = 0; col < FactoryLayout.GRID_COLS; col++) {
                int index = (state.storageFirstRow + row) * FactoryLayout.GRID_COLS + col;
                if (index >= state.filteredStorageEntries.size()) {
                    return;
                }
                FactoryStorageS2C.Entry entry = state.filteredStorageEntries.get(index);
                int x = l.gridX + col * FactoryLayout.CELL;
                int y = l.gridY + row * FactoryLayout.CELL;
                drawStorageEntry(graphics, entry, x, y);
            }
        }
    }

    /**
     * 画一个仓库格的图标和右下角数量。
     *
     * @param graphics 画布
     * @param entry    仓库条目
     * @param x        格子左上角 x
     * @param y        格子左上角 y
     */
    private void drawStorageEntry(GuiGraphics graphics, FactoryStorageS2C.Entry entry,
            int x, int y) {
        drawResourceIcon(graphics, entry.key(), x + 1, y + 1);
        drawCount(graphics, entry.count(), AeKeySupport.kind(entry.key()),
                x + FactoryLayout.CELL - 1, y + FactoryLayout.CELL - 1);
    }

    /**
     * 画一个资源图标。
     *
     * @param graphics 画布
     * @param key      资源
     * @param x        左上角 x
     * @param y        左上角 y
     */
    private void drawResourceIcon(GuiGraphics graphics, AEKey key, int x, int y) {
        FactoryResourceIconRender.draw(graphics, this.context.font(), key, x, y);
    }

    /**
     * 画仓库格右下角的数量。
     *
     * <p>先按 {@link FactoryLabels#gridCount} 压缩成 4–5 个字符，再按格子内宽缩放；
     * 缩放只影响这里，tooltip 里仍是完整精度。</p>
     *
     * @param graphics 画布
     * @param count    数量
     * @param kind     资源类型
     * @param right    格子右边缘
     * @param bottom   格子下边缘
     */
    private void drawCount(GuiGraphics graphics, long count, CapabilityKind kind,
            int right, int bottom) {
        String text = FactoryLabels.gridCount(count, kind);
        float scale = 0.666F;
        float textWidth = this.context.font().width(text);
        if (textWidth * scale > FactoryLayout.CELL - 2) {
            scale = Math.max(0.5F, (FactoryLayout.CELL - 2) / textWidth);
        }
        float width = textWidth * scale;
        float height = this.context.font().lineHeight * scale;
        graphics.pose().pushPose();
        graphics.pose().translate(right - width - 1.0F, bottom - height,
                FactoryGuiLayer.GRID_COUNT.z());
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(this.context.font(), text, 0, 0, 0xFFFFFFFF, true);
        graphics.pose().popPose();
    }
}
