package io.github.crafteve.idlefactory.client.screen;

import io.github.crafteve.idlefactory.menu.FactoryMenu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 工厂工作台界面。
 *
 * <p>面板尺寸不写死，按当前 GUI 逻辑尺寸自适应：占满可用区域，四周只留 {@link #MARGIN} 的边距。
 * 这样 GUI scale 调大（逻辑尺寸变小）时不会溢出，调小（逻辑尺寸变大）时也不会缩成一小块。</p>
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

    /** 标题左侧标记的 x 偏移。 */
    private static final int TITLE_X = 20;
    /** 标题的 y 偏移。 */
    private static final int TITLE_Y = 16;
    /** 标题栏高度，下方画分隔线。 */
    private static final int HEADER_HEIGHT = 44;

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
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // 不调用父类：父类会画菜单标题和玩家物品栏标题，M0 都不需要。
        // 这里的坐标相对面板左上角。
        guiGraphics.drawString(this.font, "Idle Factory", TITLE_X + 8, TITLE_Y + 1, COLOR_ACCENT, false);
    }
}
