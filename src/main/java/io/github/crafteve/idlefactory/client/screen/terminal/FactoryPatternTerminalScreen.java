package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.List;

import appeng.client.gui.Icon;
import appeng.client.gui.me.items.PatternEncodingTermScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.api.stacks.AEKey;

import io.github.crafteve.idlefactory.client.screen.FactoryScreen;
import appeng.menu.slot.AppEngSlot;

import io.github.crafteve.idlefactory.menu.FactoryPatternSlots;
import io.github.crafteve.idlefactory.menu.FactoryPatternTerminalMenu;
import io.github.crafteve.idlefactory.network.payload.FactoryEditFeedbackS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTableS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTerminalSwitchC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryTerminalTargetS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTerminalTargetsS2C;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 样板视图的屏幕：右半是 AE2 样板编码终端，左半是主动目标面板。
 *
 * <p>右移策略与合成视图相同：{@code super.init()} 期间把 {@code imageWidth} 设成
 * {@code -TOOLBAR_RESERVE}，让终端原点右移半个按钮栏宽度，整个窗口保持居中。
 */
public final class FactoryPatternTerminalScreen
        extends PatternEncodingTermScreen<FactoryPatternTerminalMenu>
        implements FactoryTerminalDisplay, FactoryTerminalJeiSource {
    /** 左面板。 */
    private final FactoryPatternPanel panel;

    /**
     * @param menu      菜单
     * @param inventory 玩家物品栏
     * @param title     标题
     * @param style     AE2 终端样式
     */
    public FactoryPatternTerminalScreen(FactoryPatternTerminalMenu menu, Inventory inventory,
            Component title, ScreenStyle style) {
        super(menu, inventory, title, style);
        this.panel = new FactoryPatternPanel(menu.containerId);
        this.panel.setFocusSink(() -> this.setFocused(null));
        // 切换按钮走 AE2 的左侧按钮栏，和排序/设置按钮同一条；插到栏首。
        FactoryTerminalViewButton viewButton = new FactoryTerminalViewButton(
                Icon.TAB_CRAFTING, "合成视图",
                pressed -> PacketDistributor.sendToServer(
                        new FactoryTerminalSwitchC2S(menu.containerId)));
        this.addToLeftToolbar(viewButton);
        FactoryTerminalToolbar.moveToTop(this, viewButton);
    }

    @Override
    public void init() {
        int terminalWidth = this.imageWidth;
        try {
            this.imageWidth = FactoryTerminalGeometry.centeringWidth();
            super.init();
        } finally {
            this.imageWidth = terminalWidth;
        }
        this.panel.bind(FactoryTerminalGeometry.panelLeft(this.leftPos), this.topPos,
                FactoryTerminalGeometry.PANEL_WIDTH, this.imageHeight);
        FactoryTerminalToolbar.moveToWindowLeft(this);
        this.positionPatternSlots();
        this.updatePatternSlotState();
    }

    /**
     * 没选目标时禁用 9 个样板槽：空槽还能点开会让人以为能往里放。
     */
    private void updatePatternSlotState() {
        boolean enabled = this.panel.hasSelection();
        for (Slot slot : this.menu.getSlots(FactoryPatternSlots.PATTERN_SLOT)) {
            if (slot instanceof AppEngSlot appEngSlot) {
                appEngSlot.setSlotEnabled(enabled);
            }
        }
    }

    /**
     * 把菜单里的 9 个样板槽摆到左面板的 3×3 格子上。
     *
     * <p>槽位坐标是相对 {@code leftPos} 的，而左面板在 {@code leftPos} 左边，所以这里会得到
     * 负的 x；渲染时 {@code AbstractContainerScreen} 统一加上 {@code leftPos}，位置正好落回
     * 面板内。</p>
     */
    private void positionPatternSlots() {
        List<Slot> slots = this.menu.getSlots(FactoryPatternSlots.PATTERN_SLOT);
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            slot.x = this.panel.patternSlotX(i) - this.leftPos;
            slot.y = this.panel.patternSlotY(i) - this.topPos;
        }
    }

    @Override
    public void drawBG(GuiGraphics graphics, int offsetX, int offsetY, int mouseX, int mouseY,
            float partialTicks) {
        this.panel.renderBackground(graphics, mouseX, mouseY);
        super.drawBG(graphics, offsetX, offsetY, mouseX, mouseY, partialTicks);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        this.panel.renderForeground(graphics, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 样板格是真实槽位，点在格子上时必须交给容器菜单；其余面板区域一律吞掉，
        // 因为左面板在 AE2 的 GUI 矩形外，漏到原版会被当成「点外部」丢物品。
        if (this.panel.isMouseOver(mouseX, mouseY) && !this.panel.inPatternGrid(mouseX, mouseY)) {
            this.panel.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.panel.isMouseOver(mouseX, mouseY) && !this.panel.inPatternGrid(mouseX, mouseY)) {
            this.panel.mouseReleased(mouseX, mouseY, button);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX,
            double dragY) {
        if (this.panel.isMouseOver(mouseX, mouseY) && !this.panel.inPatternGrid(mouseX, mouseY)) {
            this.panel.mouseDragged(mouseX, mouseY, button, dragX, dragY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (this.panel.isMouseOver(mouseX, mouseY)) {
            this.panel.mouseScrolled(mouseX, mouseY, deltaY);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.panel.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.panel.charTyped(codePoint, modifiers)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public void containerTick() {
        super.containerTick();
        this.panel.tick();
        this.updatePatternSlotState();
    }

    @Override
    public int containerId() {
        return this.menu.containerId;
    }

    @Override
    public void acceptTable(FactoryTableS2C payload) {
        this.panel.acceptTable(payload);
    }

    @Override
    public void acceptEditFeedback(FactoryEditFeedbackS2C payload) {
        this.panel.acceptEditFeedback(payload);
    }

    @Override
    public void acceptTargets(FactoryTerminalTargetsS2C payload) {
        this.panel.acceptTargets(payload);
    }

    @Override
    public void acceptTargetDetail(FactoryTerminalTargetS2C payload) {
        this.panel.acceptTargetDetail(payload);
    }
    @Override
    @Nullable
    public FactoryScreen.JeiTarget jeiTargetAt(double mouseX, double mouseY) {
        return this.panel.jeiTargetAt(mouseX, mouseY);
    }

    @Override
    public boolean dropJeiKey(AEKey key, double mouseX, double mouseY) {
        return this.panel.dropJeiKey(key, mouseX, mouseY);
    }

    @Override
    public List<FactoryTerminalDropArea> jeiDropAreas() {
        return this.panel.jeiDropAreas();
    }
}
