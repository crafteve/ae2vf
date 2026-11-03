package io.github.crafteve.idlefactory.client.screen.terminal;

import appeng.api.config.Settings;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.style.TerminalStyle;
import appeng.client.gui.widgets.Scrollbar;
import appeng.client.gui.widgets.SettingToggleButton;
import appeng.core.AEConfig;

import io.github.crafteve.idlefactory.client.screen.FactoryGuiTextures;
import io.github.crafteve.idlefactory.client.screen.FactoryNineSlice;
import io.github.crafteve.idlefactory.menu.FactoryPassiveIoMenu;
import io.github.crafteve.idlefactory.menu.FactoryPassiveIoView;
import io.github.crafteve.idlefactory.network.payload.FactoryPassiveIoViewC2S;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 第三版被动 IO 管理界面：AE2 终端边框 + 36 个玩家背包槽 + 中间内容区。
 *
 * <p>当前只搭 B 方案框架：边框、背包槽、内容区占位，机器卡片与规则磁贴等自绘内容
 * 留到下一步。内容区高度按窗口高度换算成行数，固定为 18px 的整数倍，保证和 AE2
 * 终端的行切片对齐。</p>
 *
 * <p>不继承 {@code MEStorageScreen}：没有 repo、搜索框与排序控件；滚动条、左侧按钮和
 * 内容区都由本屏幕自己维护。左侧按钮沿用 AE2 的 {@code VerticalButtonBar}。</p>
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
    /** 右侧滚动条占用的宽度；内容不能压到滚动条下面。 */
    private static final int SCROLLBAR_RESERVE = 20;
    /** 占位标题颜色：AE2 面板上的深靛色。 */
    private static final int PLACEHOLDER_TITLE = 0xFF33445C;
    /** 占位说明颜色。 */
    private static final int PLACEHOLDER_HINT = 0xFF707070;

    /** 当前内容行数；init 时按窗口高度计算。 */
    private int rows = MIN_ROWS;
    /** 当前列表 / 卡片视图；初值来自物品组件。 */
    private FactoryPassiveIoView view;
    /** AE2 右侧滚动条；范围由内容高度决定。 */
    private final Scrollbar scrollbar;

    /**
     * @param menu      菜单
     * @param inventory 玩家物品栏
     * @param title     标题
     * @param style     AE2 终端样式
     */
    public FactoryPassiveIoScreen(FactoryPassiveIoMenu menu, Inventory inventory,
            Component title, ScreenStyle style) {
        super(menu, inventory, title, style);
        this.view = menu.host().view();
        this.scrollbar = this.widgets.addScrollBar("scrollbar", Scrollbar.BIG);
        this.addToLeftToolbar(new SettingToggleButton<>(Settings.TERMINAL_STYLE,
                AEConfig.instance().getTerminalStyle(), this::toggleTerminalStyle));
        this.addToLeftToolbar(new FactoryPassiveIoViewButton(() -> this.view,
                button -> this.toggleView()));
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
        this.scrollbar.setHeight(this.contentHeight());
        this.scrollbar.setVisible(true);
        this.updateScrollbar();
    }

    @Override
    public void drawBG(GuiGraphics graphics, int offsetX, int offsetY, int mouseX, int mouseY,
            float partialTicks) {
        super.drawBG(graphics, offsetX, offsetY, mouseX, mouseY, partialTicks);
        TerminalStyle terminal = this.style.getTerminalStyle();
        FactoryPassiveIoFrame.draw(graphics, terminal, this.leftPos, this.topPos, this.rows);

        int x = this.leftPos + CONTENT_PADDING;
        int y = this.topPos + terminal.getHeader().getSrcHeight();
        int width = this.contentWidth();
        int height = this.contentHeight();
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.WORK_BG, x, y, width, height);
        this.drawBorder(graphics, x, y, width, height);
        graphics.drawString(this.font, "被动 IO 管理终端", x + 8, y + 8,
                PLACEHOLDER_TITLE, false);
        graphics.drawString(this.font, "第三版框架 · 内容区待实现", x + 8, y + 20,
                PLACEHOLDER_HINT, false);
    }

    /**
     * 高度按钮：按 AE2 的终端样式循环，写 AE 全局客户端配置后重建行数与槽位。
     *
     * @param button    高度按钮
     * @param backwards 是否反向循环
     */
    private void toggleTerminalStyle(
            SettingToggleButton<appeng.api.config.TerminalStyle> button, boolean backwards) {
        appeng.api.config.TerminalStyle next = button.getNextValue(backwards);
        AEConfig.instance().setTerminalStyle(next);
        button.set(next);
        // 与 MEStorageScreen.reinitalize 同义：重算 rows / imageHeight，重建槽位与控件。
        this.init();
    }

    /**
     * 视图按钮：先本地切换，再发 C2S 让服务端把设置写进物品组件。
     */
    private void toggleView() {
        FactoryPassiveIoView next = this.view.next();
        this.view = next;
        PacketDistributor.sendToServer(
                new FactoryPassiveIoViewC2S(this.menu.containerId, next));
        this.updateScrollbar();
    }

    /**
     * 接收服务端确认的视图；当前屏幕只更新图标与滚动范围。
     *
     * @param next 服务端保存的视图
     */
    public void acceptView(FactoryPassiveIoView next) {
        if (this.view == next) {
            return;
        }
        this.view = next;
        this.updateScrollbar();
    }

    /**
     * @return 当前菜单容器 id；服务端同步包用它过滤过期界面
     */
    public int containerId() {
        return this.menu.containerId;
    }

    private int contentWidth() {
        return this.imageWidth - CONTENT_PADDING * 2 - SCROLLBAR_RESERVE;
    }

    private int contentHeight() {
        return this.rows * this.rowHeight();
    }

    private int rowHeight() {
        return this.style.getTerminalStyle().getRow().getSrcHeight();
    }

    /**
     * 重算滚动范围。当前内容区还是占位，范围为整块视口；接入卡片后由实际内容高度替换。
     */
    private void updateScrollbar() {
        int page = this.contentHeight();
        this.scrollbar.setHeight(page);
        this.scrollbar.setRange(0, Math.max(1, page), page);
        this.scrollbar.setVisible(true);
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
