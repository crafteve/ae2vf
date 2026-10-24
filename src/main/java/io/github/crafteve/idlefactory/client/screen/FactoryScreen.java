package io.github.crafteve.idlefactory.client.screen;

import io.github.crafteve.idlefactory.menu.FactoryMenu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 工厂工作台界面。
 *
 * <p>M0 只画一个 480×300 的深色空面板：深色底、细边框、青色标题。
 * ESC 关闭由 {@code Screen#keyPressed} 的默认行为处理，不需要额外代码。</p>
 */
public class FactoryScreen extends AbstractContainerScreen<FactoryMenu> {
    /** 面板宽度。 */
    private static final int PANEL_WIDTH = 480;
    /** 面板高度。 */
    private static final int PANEL_HEIGHT = 300;

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
        // 父类只有固定 176×166 的构造函数，尺寸在这里覆盖
        this.imageWidth = PANEL_WIDTH;
        this.imageHeight = PANEL_HEIGHT;
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;

        // 深色面板底色
        guiGraphics.fill(x, y, x + PANEL_WIDTH, y + PANEL_HEIGHT, COLOR_PANEL);

        // 1px 细边框（上 / 下 / 左 / 右）
        guiGraphics.fill(x, y, x + PANEL_WIDTH, y + 1, COLOR_BORDER);
        guiGraphics.fill(x, y + PANEL_HEIGHT - 1, x + PANEL_WIDTH, y + PANEL_HEIGHT, COLOR_BORDER);
        guiGraphics.fill(x, y, x + 1, y + PANEL_HEIGHT, COLOR_BORDER);
        guiGraphics.fill(x + PANEL_WIDTH - 1, y, x + PANEL_WIDTH, y + PANEL_HEIGHT, COLOR_BORDER);

        // 标题栏分隔线
        guiGraphics.fill(x + 1, y + HEADER_HEIGHT, x + PANEL_WIDTH - 1, y + HEADER_HEIGHT + 1, COLOR_BORDER);

        // 标题左侧的青色竖条
        guiGraphics.fill(x + TITLE_X, y + TITLE_Y, x + TITLE_X + 2, y + TITLE_Y + 9, COLOR_ACCENT);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // 不调用父类：父类会画菜单标题（物品名）和玩家物品栏标题，M0 都不需要。
        // 这里的坐标相对面板左上角。
        guiGraphics.drawString(this.font, "Idle Factory", TITLE_X + 8, TITLE_Y + 1, COLOR_ACCENT, false);
    }
}
