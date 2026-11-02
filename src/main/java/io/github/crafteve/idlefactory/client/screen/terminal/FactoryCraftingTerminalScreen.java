package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.List;

import appeng.client.gui.Icon;
import appeng.client.gui.me.items.CraftingTermScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.api.stacks.AEKey;

import io.github.crafteve.idlefactory.client.screen.FactoryScreen;

import io.github.crafteve.idlefactory.menu.FactoryCraftingTerminalMenu;
import io.github.crafteve.idlefactory.network.payload.FactoryEditFeedbackS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTableS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTerminalSwitchC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryTerminalTargetS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTerminalTargetsS2C;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 合成视图的屏幕：右半是 AE2 合成终端，左半是工厂被动 IO 面板。
 *
 * <p>AE2 的终端控件按「屏幕原点」定位，按钮栏还会每帧按 init 时缓存的绝对原点重排；
 * 所以不能开完界面再平移。做法是在 {@code super.init()} 期间临时把 {@code imageWidth}
 * 设成 {@code -TOOLBAR_RESERVE}，让 {@code AbstractContainerScreen} 把原点右移半个按钮栏
 * 宽度，整个「按钮栏 + 面板 + 终端」窗口仍然居中，随后恢复真实宽度。
 * 这样槽位、搜索框、滚动条、按钮栏与升级面板一次对齐，没有事后补丁。</p>
 */
public final class FactoryCraftingTerminalScreen extends CraftingTermScreen<FactoryCraftingTerminalMenu>
        implements FactoryTerminalDisplay, FactoryTerminalJeiSource {
    /** 左面板。 */
    private final FactoryIoPanel panel;

    /**
     * @param menu            菜单
     * @param inventory       玩家物品栏
     * @param title           标题
     * @param style           AE2 终端样式
     */
    public FactoryCraftingTerminalScreen(FactoryCraftingTerminalMenu menu, Inventory inventory,
            Component title, ScreenStyle style) {
        super(menu, inventory, title, style);
        this.panel = new FactoryIoPanel(menu.containerId);
        this.panel.setFocusSink(() -> this.setFocused(null));
        // 切换按钮走 AE2 的左侧按钮栏，和排序/设置按钮同一条；插到栏首。
        FactoryTerminalViewButton viewButton = new FactoryTerminalViewButton(
                Icon.PATTERN_TERMINAL_VISIBLE, "样板视图",
                pressed -> PacketDistributor.sendToServer(
                        new FactoryTerminalSwitchC2S(menu.containerId)));
        this.addToLeftToolbar(viewButton);
        FactoryTerminalToolbar.moveToTop(this, viewButton);
    }

    @Override
    public void init() {
        // 见类注释：只在 super.init() 期间用负宽度把原点右移到整个窗口的中线。
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
            // 左面板在 AE2 的 GUI 矩形外：只要事件漏到原版，就会被当成「点击 GUI 外侧」，
            // 把光标上的物品丢出去。面板内一律吞掉，不再看 panel 的返回值。
            this.panel.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.panel.isMouseOver(mouseX, mouseY)) {
            // 同上：释放也要吞掉，否则原版会在这里完成「拖到外部丢弃」。
            this.panel.mouseReleased(mouseX, mouseY, button);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX,
            double dragY) {
        if (this.panel.isMouseOver(mouseX, mouseY)) {
            // 拖到左面板上同样不下传，避免原版把物品拖出 GUI 外丢掉。
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
        // 合成视图没有主动目标。
    }

    @Override
    public void acceptTargetDetail(FactoryTerminalTargetS2C payload) {
        // 合成视图没有主动目标。
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
