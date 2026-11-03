package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import appeng.api.config.Settings;
import appeng.api.stacks.AEKey;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.style.TerminalStyle;
import appeng.client.gui.widgets.Scrollbar;
import appeng.client.gui.widgets.SettingToggleButton;
import appeng.core.AEConfig;

import io.github.crafteve.idlefactory.client.screen.FactoryGuiTheme;
import io.github.crafteve.idlefactory.client.screen.FactorySearchEditBox;
import io.github.crafteve.idlefactory.client.screen.FactorySearchQuery;
import io.github.crafteve.idlefactory.client.screen.FactoryTableSink;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryPassiveIoJeiTargets.AddTarget;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryPassiveIoJeiTargets.JeiTarget;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryPassiveIoLayout.Hit;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryPassiveIoLayout.Viewport;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryPassiveIoModel.BlockView;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryPassiveIoModel.RuleRow;
import io.github.crafteve.idlefactory.integration.jecharacters.JecharactersCompat;
import io.github.crafteve.idlefactory.menu.FactoryPassiveIoMenu;
import io.github.crafteve.idlefactory.menu.FactoryPassiveIoView;
import io.github.crafteve.idlefactory.network.payload.FactoryEditFeedbackS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryPassiveIoViewC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryTableRequestC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryTableS2C;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 第三版被动 IO 管理界面：AE2 终端边框 + 36 个玩家背包槽 + 自绘内容区。
 *
 * <p>壳只做五件事：算内容视口、拉机器表、把快照交给模型、把鼠标命中交给动作层、按快照重画。
 * 内容区的模型、排版、绘制、滚动、JEI 目标与探针各自独立，屏幕类不内联任何卡片坐标。</p>
 *
 * <p>请求节拍与旧工作台一致：每 {@link #REQUEST_INTERVAL} tick 拉一份机器表；界面不靠服务端
 * 主动推送，否则切界面或重开时数据会停在旧值。编辑反馈不在界面上展示，动作结果只由随后
 * 到达的机器快照体现。</p>
 */
public final class FactoryPassiveIoScreen extends AEBaseScreen<FactoryPassiveIoMenu>
        implements FactoryTableSink {
    /** 最少内容行数；少于两行时 AE2 的首行 / 末行切片会重叠。 */
    private static final int MIN_ROWS = 3;
    /** 内容区左右内边距。 */
    private static final int CONTENT_PADDING = 4;
    /** 右侧滚动条占用的宽度；内容不能压到滚动条下面。 */
    private static final int SCROLLBAR_RESERVE = 17;
    /** 滚动条（滑块 + 滑槽）右移像素；滑槽是贴图，靠 {@code drawScrollbarGroove} 重画。 */
    private static final int SCROLLBAR_SHIFT = 4;
    /** 工作区描边框宽：内容区再往里缩 3px，给亮边和 2px 间隙让位。 */
    private static final int WORK_FRAME = 3;
    /** 描边框与顶栏搜索框之间的间距。 */
    private static final int WORK_GAP = 2;
    /** 机器表请求节拍。 */
    private static final int REQUEST_INTERVAL = 20;
    /** 顶栏搜索框的外框位置与尺寸。 */
    private static final int SEARCH_X = 46;
    private static final int SEARCH_Y = 4;
    private static final int SEARCH_W = 124;
    private static final int SEARCH_H = 13;

    /** 开发者探针：只在非生产环境响应 F6–F9，避免把调试键带进正式包。 */
    private static final boolean PROBES = !FMLLoader.isProduction();
    /** 跨界面保留的搜索词；关闭界面再打开仍保留。 */
    private static String rememberedQuery = "";

    /** 当前内容行数；init 时按窗口高度计算。 */
    private int rows = MIN_ROWS;
    /** 当前列表 / 磁贴视图；初值来自物品组件。 */
    private FactoryPassiveIoView view;
    /** 内容模型：快照 + 搜索词 → 机器块。 */
    private final FactoryPassiveIoModel model;
    /** 本帧排版；由模型、视图与视口重建。 */
    @Nullable
    private FactoryPassiveIoLayout layout;
    /** 内容区绘制器；init 时按当前字体与主题创建。 */
    @Nullable
    private FactoryPassiveIoRender render;
    /** 滚动状态与 AE2 滚动条。 */
    private final FactoryPassiveIoScroll scroll;
    /** 请求节拍计数。 */
    private int requestTicker;
    /** 顶栏搜索框。 */
    @Nullable
    private FactorySearchEditBox searchBox;
    /** 是否正在从 JEI 拖动资源。 */
    private boolean jeiDragging;
    /** 上一次日志签名，避免每秒刷一行表快照。 */
    private int lastLogSignature = -1;

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
        this.model = new FactoryPassiveIoModel(FactoryGuiTheme.current());
        Scrollbar bar = this.widgets.addScrollBar("scrollbar", Scrollbar.BIG);
        this.scroll = new FactoryPassiveIoScroll(bar);
        // AE2 自带的帮助按钮只在 getHelpTopic() 非空时显示；本模组还没有指南页，
        // 先用一个同图标、同位置的按钮占位，等指南页确定后再换成 GuideME 跳转。
        this.addToLeftToolbar(new FactoryPassiveIoHelpButton(button -> {
            // 指南页待接入。
        }));
        this.addToLeftToolbar(new SettingToggleButton<>(Settings.TERMINAL_STYLE,
                AEConfig.instance().getTerminalStyle(), this::toggleTerminalStyle));
        this.addToLeftToolbar(new FactoryPassiveIoViewButton(() -> this.view,
                button -> this.toggleView()));
    }

    @Override
    protected void init() {
        // 切终端高度 / 窗口尺寸都会重新走 init。Screen.init 不会清空旧控件，直接再 add
        // 会多出一份搜索框和工具栏按钮，旧位置的控件继续渲染，看起来就是文字重影、
        // 高亮串位。AE2 的 MEStorageScreen.reinitalize 也是先移除所有 children 再 init。
        this.clearWidgets();
        this.searchBox = null;
        TerminalStyle terminal = this.style.getTerminalStyle();
        // getPossibleRows 内部会自己减 header / bottom，这里只能传屏幕可用高度。
        int margin = AEConfig.instance().getTerminalMargin();
        int usable = Math.max(0, this.height - margin * 2);
        int possibleRows = terminal.getPossibleRows(usable);
        this.rows = Math.max(MIN_ROWS,
                AEConfig.instance().getTerminalStyle().getRows(possibleRows));
        this.imageWidth = terminal.getScreenWidth();
        this.imageHeight = terminal.getScreenHeight(this.rows);
        super.init();
        // AE2 只在 init 时按样式定位一次滚动条；内容区加宽后必须再右移一次。
        this.scroll.shiftX(SCROLLBAR_SHIFT);
        // base_terminal.json 带一条 ME 终端的「条目数」文本，本界面不用它，必须显式隐藏。
        this.setTextHidden("entriesShown", true);
        this.render = new FactoryPassiveIoRender(this.font, FactoryGuiTheme.current());
        this.searchBox = new FactorySearchEditBox(this.font,
                this.leftPos + SEARCH_X + 3, this.topPos + SEARCH_Y + 2,
                SEARCH_W - 6, 10, FactoryGuiTheme.current(), Component.literal("搜索机器…"));
        this.searchBox.setValue(rememberedQuery);
        this.searchBox.setResponder(this::applyQuery);
        this.addRenderableWidget(this.searchBox);
        this.applyQuery(rememberedQuery);
        FactoryPassiveIoProbe.logScreen(this);
        requestTable();
    }

    @Override
    public void drawBG(GuiGraphics graphics, int offsetX, int offsetY, int mouseX, int mouseY,
            float partialTicks) {
        super.drawBG(graphics, offsetX, offsetY, mouseX, mouseY, partialTicks);
        FactoryPassiveIoFrame.draw(graphics, this.style.getTerminalStyle(), this.leftPos,
                this.topPos, this.rows);
        // AE2 的终端行贴图中间带一排物品槽（ME 终端用来放仓库格）；本界面内容区自绘，
        // 必须把这块槽位网格盖成纯面板色，否则空内容区会露出一片格子。右侧 174 之后是
        // AE2 终端自带的滚动条滑轨（178~183），必须留着，否则滚动条只剩滑块没有轨道。
        int header = this.style.getTerminalStyle().getHeader().getSrcHeight();
        int grooveX = this.scroll.grooveX();
        graphics.fill(this.leftPos + 2, this.topPos + header, grooveX,
                this.topPos + header + this.rows * rowHeight(),
                FactoryGuiTheme.current().panelBg());
        // 滑槽跟着滑块右移：填充盖掉了原位置的滑槽，这里用同一张终端贴图在新位置重画。
        FactoryPassiveIoChrome.drawScrollbarGroove(graphics, grooveX,
                this.topPos + header, this.rows * rowHeight(), this.imageWidth - 17);
        // 填充停在滑轨左侧，保留 AE2 终端贴图自带的滑轨与 Scrollbar 原始渲染。
        Viewport frame = workAreaFrame();
        FactoryPassiveIoChrome.drawWorkAreaFrame(graphics, frame.x(), frame.y(), frame.width(),
                frame.height());
        FactoryPassiveIoChrome.drawSearchField(graphics, this.leftPos + SEARCH_X,
                this.topPos + SEARCH_Y, SEARCH_W, SEARCH_H);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.layout != null) {
            this.scroll.syncFromBar(this.layout);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        FactoryPassiveIoChrome.drawTitle(graphics, this.font, this.leftPos, this.topPos);
        Hit hover = this.layout == null ? null : this.layout.hit(mouseX, mouseY, scroll());
        drawContent(graphics, hover);
        List<Component> tooltip = FactoryPassiveIoTooltip.of(this.model.blocks(), hover);
        if (!tooltip.isEmpty()) {
            graphics.renderComponentTooltip(this.font, tooltip, mouseX, mouseY);
        }
    }

    /**
     * 画内容区；先按视口裁剪，滚出视口的部分不能画到背包槽或滚动条上。
     *
     * @param graphics 画布
     * @param hover    当前命中
     */
    private void drawContent(GuiGraphics graphics, @Nullable Hit hover) {
        FactoryPassiveIoRender content = this.render;
        FactoryPassiveIoLayout current = this.layout;
        if (content == null || current == null) {
            return;
        }
        Viewport viewport = current.viewport();
        graphics.enableScissor(viewport.x(), viewport.y(),
                viewport.x() + viewport.width(), viewport.y() + viewport.height());
        try {
            content.draw(graphics, current, this.model.blocks(), scroll(), hover,
                    this.jeiDragging, emptyText());
        } finally {
            graphics.disableScissor();
        }
    }

    @Override
    public void containerTick() {
        super.containerTick();
        if (++this.requestTicker >= REQUEST_INTERVAL) {
            this.requestTicker = 0;
            requestTable();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 内容区先于 super 处理：原版容器界面会把面板内的空白点击也吃掉，
        // 先走 super 的话 chip 永远收不到点击。内容区不与槽位 / 搜索框 / 滚动条重叠。
        if (this.layout != null) {
            Hit hit = this.layout.hit(mouseX, mouseY, scroll());
            if (hit != null) {
                clickHit(hit, button);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.layout != null && insideViewport(this.layout.viewport(), mouseX, mouseY)) {
            this.scroll.scrollBy(this.layout, (int) (-scrollY * FactoryPassiveIoLayout.ROW_H));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (PROBES && FactoryPassiveIoProbe.handleKey(this, keyCode)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public int containerId() {
        return this.menu.containerId;
    }

    @Override
    public void acceptTable(FactoryTableS2C payload) {
        this.model.accept(payload);
        rebuildLayout();
        int signature = payload.machines().size() * 37 + totalRules();
        if (signature != this.lastLogSignature) {
            this.lastLogSignature = signature;
            FactoryPassiveIoProbe.logTable(this);
        }
    }

    /**
     * 编辑反馈不再展示。
     *
     * <p>终端里没有提示条：动作是否生效由随后到达的机器快照体现，失败时规则会原样留在表里。
     * 接口是两屏共用的，旧工作台仍需要这条反馈，所以这里保留空实现而不是删方法。</p>
     *
     * @param payload 反馈
     */
    @Override
    public void acceptEditFeedback(FactoryEditFeedbackS2C payload) {
        // 不展示、不记状态。
    }

    /**
     * 接收服务端确认的视图。
     *
     * @param next 服务端保存的视图
     */
    public void acceptView(FactoryPassiveIoView next) {
        if (this.view == next) {
            return;
        }
        this.view = next;
        rebuildLayout();
        FactoryPassiveIoProbe.logScreen(this);
    }

    /**
     * 按当前视口重建排版并夹紧滚动。
     *
     * <p>由快照、搜索词、视图与窗口尺寸变化触发；滚动本身不重建排版。</p>
     */
    void rebuildLayout() {
        if (this.render == null) {
            return;
        }
        Viewport viewport = contentViewport();
        this.layout = FactoryPassiveIoLayout.of(this.model.blocks(), viewport, this.view);
        this.scroll.clampTo(this.layout);
    }

    /**
     * 当前内容视口（描边框以内）。
     *
     * <p>视口比描边框再缩进 2px，绘制与命中都从这里取；描边框本身不参与滚动。</p>
     *
     * @return 视口（屏幕坐标）
     */
    Viewport contentViewport() {
        int header = this.style.getTerminalStyle().getHeader().getSrcHeight();
        return new Viewport(this.leftPos + CONTENT_PADDING + WORK_FRAME,
                this.topPos + header + WORK_GAP + WORK_FRAME,
                this.imageWidth - CONTENT_PADDING * 2 - SCROLLBAR_RESERVE - WORK_FRAME * 2,
                this.rows * rowHeight() - WORK_GAP - WORK_FRAME * 2);
    }

    /**
     * 工作区描边框外沿（包含描边，不含滚动条）。
     *
     * @return 描边框（屏幕坐标）
     */
    Viewport workAreaFrame() {
        Viewport viewport = contentViewport();
        return new Viewport(viewport.x() - WORK_FRAME, viewport.y() - WORK_FRAME,
                viewport.width() + WORK_FRAME * 2, viewport.height() + WORK_FRAME * 2);
    }

    /** 当前滚动像素。 */
    int scroll() {
        return this.scroll.value();
    }

    /** 当前排版；可能为 {@code null}。 */
    @Nullable
    FactoryPassiveIoLayout layout() {
        return this.layout;
    }

    /** 当前模型。 */
    FactoryPassiveIoModel model() {
        return this.model;
    }

    /** 当前视图。 */
    FactoryPassiveIoView view() {
        return this.view;
    }

    /** 当前行数，给探针日志用。 */
    int rows() {
        return this.rows;
    }

    /** 当前内容块。 */
    List<BlockView> blocks() {
        return this.model.blocks();
    }

    /** 打开 / 关闭 JEI 拖动状态。 */
    public void setJeiDragging(boolean value) {
        this.jeiDragging = value;
    }

    /**
     * JEI 拖拽目标：每台机器一条虚线新增行 / 虚线新增磁贴。
     *
     * @return 目标列表
     */
    public List<AddTarget> addTargets() {
        this.jeiDragging = true;
        return targets();
    }

    /**
     * 只算虚线新增区域，不改拖拽状态。
     *
     * <p>EMI 自己画拖拽高亮，不需要 {@code jeiDragging}；如果复用 {@link #addTargets()}，
     * EMI 拖完没有 JEI 的 {@code onComplete} 来复位，虚线会一直亮着。</p>
     *
     * @return 目标列表
     */
    public List<AddTarget> targets() {
        if (this.layout == null) {
            return List.of();
        }
        return FactoryPassiveIoJeiTargets.addTargets(this.layout, scroll());
    }

    /**
     * JEI 拖入后新建一条输入规则。
     *
     * @param block 目标机器块下标
     * @param key   资源
     */
    public void addFromJei(int block, AEKey key) {
        if (block < 0 || block >= this.model.blocks().size()) {
            return;
        }
        FactoryPassiveIoActions.add(this.menu.containerId, this.model.blocks().get(block), key);
    }

    /**
     * 鼠标下的 JEI 查询目标（R / U）。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 目标；没有物品时为 {@code null}
     */
    @Nullable
    public JeiTarget jeiTargetAt(double mouseX, double mouseY) {
        if (this.layout == null) {
            return null;
        }
        return FactoryPassiveIoJeiTargets.targetAt(this.layout, this.model.blocks(), scroll(),
                mouseX, mouseY);
    }

    /**
     * 点击命中：chip 左键正向循环、右键反向循环；规则卡右键删除，不做选中态。
     *
     * <p>悬停已经按卡片 / chip 分好了高亮：悬停卡片整张卡片亮，悬停 chip 只有 chip 亮。
     * 右键跟随悬停对象走——悬停卡片就是删除，悬停 chip 就是反向翻选项。</p>
     *
     * @param hit    命中
     * @param button 鼠标键
     */
    void clickHit(Hit hit, int button) {
        if (hit.block() < 0 || hit.block() >= this.model.blocks().size()) {
            return;
        }
        BlockView block = this.model.blocks().get(hit.block());
        if (hit.rule() < 0 || hit.rule() >= block.rules().size()) {
            return;
        }
        RuleRow rule = block.rules().get(hit.rule());
        if (button == 1) {
            switch (hit.kind()) {
                case CHIP_DIR -> FactoryPassiveIoActions.flip(this.menu.containerId, block, rule);
                case CHIP_FACE -> FactoryPassiveIoActions.cycleFace(this.menu.containerId, block,
                        rule, true);
                case CHIP_MODE -> FactoryPassiveIoActions.toggleMode(this.menu.containerId, block,
                        rule);
                case RULE -> FactoryPassiveIoActions.delete(this.menu.containerId, block, rule);
                default -> {
                    // 机器行与新增行的右键不做事。
                }
            }
            return;
        }
        if (button != 0) {
            return;
        }
        switch (hit.kind()) {
            case CHIP_DIR -> FactoryPassiveIoActions.flip(this.menu.containerId, block, rule);
            case CHIP_FACE -> FactoryPassiveIoActions.cycleFace(this.menu.containerId, block,
                    rule, false);
            case CHIP_MODE -> FactoryPassiveIoActions.toggleMode(this.menu.containerId, block,
                    rule);
            default -> {
                // 卡片本体左键不做事，取消选中模式。
            }
        }
    }

    private void toggleView() {
        setView(this.view.next());
    }

    private void setView(FactoryPassiveIoView next) {
        if (this.view == next) {
            return;
        }
        this.view = next;
        PacketDistributor.sendToServer(
                new FactoryPassiveIoViewC2S(this.menu.containerId, next));
        rebuildLayout();
        FactoryPassiveIoProbe.logScreen(this);
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

    private int rowHeight() {
        return this.style.getTerminalStyle().getRow().getSrcHeight();
    }

    private void applyQuery(String raw) {
        rememberedQuery = raw == null ? "" : raw;
        this.model.setQuery(FactorySearchQuery.parse(rememberedQuery,
                JecharactersCompat::contains));
        rebuildLayout();
    }

    private void requestTable() {
        if (this.minecraft != null && this.minecraft.player != null) {
            PacketDistributor.sendToServer(new FactoryTableRequestC2S(this.menu.containerId));
        }
    }

    private String emptyText() {
        if (!this.model.hasFactory()) {
            return "先执行 /idf create";
        }
        if (this.model.filteredEmpty()) {
            return "没有匹配的机器";
        }
        return "还没有机器 · 用 /idf factory connect 接入";
    }

    private int totalRules() {
        int total = 0;
        for (BlockView block : this.model.blocks()) {
            total += block.rules().size();
        }
        return total;
    }

    private static boolean insideViewport(Viewport viewport, double mouseX, double mouseY) {
        return mouseX >= viewport.x() && mouseX < viewport.x() + viewport.width()
                && mouseY >= viewport.y() && mouseY < viewport.y() + viewport.height();
    }
}
