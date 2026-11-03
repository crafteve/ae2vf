package io.github.crafteve.idlefactory.client.screen.terminal;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.style.TerminalStyle;

import io.github.crafteve.idlefactory.client.screen.FactoryGuiTextures;
import io.github.crafteve.idlefactory.client.screen.FactoryNineSlice;
import io.github.crafteve.idlefactory.menu.FactoryPassiveIoMenu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;

/**
 * 第三版被动 IO 管理界面：AE2 终端边框 + 36 个玩家背包槽 + 中间内容区。
 *
 * <p>当前只搭 B 方案框架：边框、背包槽、内容区占位，机器卡片与规则磁贴等自绘内容
 * 留到下一步。内容区高度按窗口高度换算成行数，固定为 18px 的整数倍，保证和 AE2
 * 终端的行切片对齐。</p>
 *
 * <p>不继承 {@code MEStorageScreen}：没有 repo、搜索框、滚动条等旧终端控件，内容区
 * 后续由本屏幕自己维护。</p>
 */
public final class FactoryPassiveIoScreen extends AEBaseScreen<FactoryPassiveIoMenu> {
    /** 窗口上下留白；给屏幕边框和 AE2 终端留呼吸空间。 */
    private static final int MARGIN = 8;
    /** 最少内容行数；少于两行时 AE2 的首行 / 末行切片会重叠。 */
    private static final int MIN_ROWS = 3;
    /** 最多内容行数；再高会顶到屏幕外，内容应滚动而不是无限拉长。 */
    private static final int MAX_ROWS = 12;
    /** 内容区左右内边距。 */
    private static final int CONTENT_PADDING = 4;
    /** 占位标题颜色：AE2 面板上的深靛色。 */
    private static final int PLACEHOLDER_TITLE = 0xFF33445C;
    /** 占位说明颜色。 */
    private static final int PLACEHOLDER_HINT = 0xFF707070;

    /** 当前内容行数；init 时按窗口高度计算。 */
    private int rows = MIN_ROWS;

    /**
     * @param menu      菜单
     * @param inventory 玩家物品栏
     * @param title     标题
     * @param style     AE2 终端样式
     */
    public FactoryPassiveIoScreen(FactoryPassiveIoMenu menu, Inventory inventory,
            Component title, ScreenStyle style) {
        super(menu, inventory, title, style);
    }

    @Override
    protected void init() {
        TerminalStyle terminal = this.style.getTerminalStyle();
        int header = terminal.getHeader().getSrcHeight();
        int row = terminal.getRow().getSrcHeight();
        int bottom = terminal.getBottom().getSrcHeight();
        int usable = Math.max(0, this.height - MARGIN * 2 - header - bottom);
        this.rows = Mth.clamp(usable / row, MIN_ROWS, MAX_ROWS);
        this.imageWidth = terminal.getScreenWidth();
        this.imageHeight = terminal.getScreenHeight(this.rows);
        super.init();
        // base_terminal.json 带一条 ME 终端的「条目数」文本，本界面不用它，必须显式隐藏。
        this.setTextHidden("entriesShown", true);
    }

    /**
     * 不画 AE2 左侧竖向按钮栏；第三版的内容控件全部在中间内容区。
     *
     * @return {@code false}
     */
    @Override
    protected boolean shouldAddToolbar() {
        return false;
    }

    @Override
    public void drawBG(GuiGraphics graphics, int offsetX, int offsetY, int mouseX, int mouseY,
            float partialTicks) {
        super.drawBG(graphics, offsetX, offsetY, mouseX, mouseY, partialTicks);
        TerminalStyle terminal = this.style.getTerminalStyle();
        FactoryPassiveIoFrame.draw(graphics, terminal, this.leftPos, this.topPos, this.rows);

        int x = this.leftPos + CONTENT_PADDING;
        int y = this.topPos + terminal.getHeader().getSrcHeight();
        int width = this.imageWidth - CONTENT_PADDING * 2;
        int height = this.rows * terminal.getRow().getSrcHeight();
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.WORK_BG, x, y, width, height);
        this.drawBorder(graphics, x, y, width, height);
        graphics.drawString(this.font, "被动 IO 管理终端", x + 8, y + 8,
                PLACEHOLDER_TITLE, false);
        graphics.drawString(this.font, "第三版框架 · 内容区待实现", x + 8, y + 20,
                PLACEHOLDER_HINT, false);
    }

    private void drawBorder(GuiGraphics graphics, int x, int y, int width, int height) {
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.TABLE_LIGHT, x, y, width, 1);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.TABLE_LIGHT, x, y + height - 1,
                width, 1);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.TABLE_LIGHT, x, y, 1, height);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.TABLE_LIGHT, x + width - 1, y, 1,
                height);
        // 1px 亮边之外再压一条深边，避免工作区直接贴到 AE2 行切片上。
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.DIVIDER_DARK, x + 1, y + 1,
                width - 2, 1);
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.DIVIDER_DARK, x + 1, y + height - 2,
                width - 2, 1);
    }
}
