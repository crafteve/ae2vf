package io.github.crafteve.idlefactory.client.screen.terminal;

import appeng.client.gui.me.items.PatternEncodingTermScreen;
import appeng.client.gui.style.ScreenStyle;

import io.github.crafteve.idlefactory.menu.FactoryPatternTerminalMenu;
import io.github.crafteve.idlefactory.network.payload.FactoryEditFeedbackS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTableS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTerminalTargetS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTerminalTargetsS2C;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 样板视图的屏幕：右半是 AE2 样板编码终端，左半是主动目标面板。
 *
 * <p>右移策略与合成视图相同：{@code super.init()} 期间把 {@code imageWidth} 当作 0，
 * 让 AE2 的终端原点直接落在屏幕中线，随后恢复真实宽度。两个视图必须共用这套定位，
 * 否则切换模式后左右两半会错位。</p>
 */
public final class FactoryPatternTerminalScreen
        extends PatternEncodingTermScreen<FactoryPatternTerminalMenu>
        implements FactoryTerminalDisplay {
    /** 左面板。 */
    private final FactoryPatternPanel panel = new FactoryPatternPanel();

    /**
     * @param menu      菜单
     * @param inventory 玩家物品栏
     * @param title     标题
     * @param style     AE2 终端样式
     */
    public FactoryPatternTerminalScreen(FactoryPatternTerminalMenu menu, Inventory inventory,
            Component title, ScreenStyle style) {
        super(menu, inventory, title, style);
    }

    @Override
    public void init() {
        int terminalWidth = this.imageWidth;
        try {
            this.imageWidth = 0;
            super.init();
        } finally {
            this.imageWidth = terminalWidth;
        }
        this.panel.bind(FactoryTerminalGeometry.panelLeft(this.leftPos), this.topPos,
                FactoryTerminalGeometry.PANEL_WIDTH, this.imageHeight);
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
        if (this.panel.isMouseOver(mouseX, mouseY)) {
            this.panel.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.panel.isMouseOver(mouseX, mouseY)) {
            this.panel.mouseReleased(mouseX, mouseY, button);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX,
            double dragY) {
        if (this.panel.isMouseOver(mouseX, mouseY)) {
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
}
