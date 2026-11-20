package io.github.crafteve.idlefactory.client.screen.terminal.passiveio;

import io.github.crafteve.idlefactory.client.screen.terminal.TerminalSource;

import io.github.crafteve.idlefactory.client.screen.terminal.PassiveIoViewButton;

import io.github.crafteve.idlefactory.client.screen.terminal.PassiveIoHelpButton;


import io.github.crafteve.idlefactory.client.screen.terminal.TerminalChrome;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import appeng.api.config.Settings;
import appeng.api.config.SortDir;
import appeng.api.stacks.AEKey;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.style.TerminalStyle;
import appeng.client.gui.widgets.Scrollbar;
import appeng.client.gui.widgets.SettingToggleButton;
import appeng.core.AEConfig;

import io.github.crafteve.idlefactory.client.screen.FactoryGuiTheme;
import io.github.crafteve.idlefactory.client.screen.search.SearchEditBox;
import io.github.crafteve.idlefactory.client.screen.search.SearchQuery;
import io.github.crafteve.idlefactory.client.screen.sort.SortContext;
import io.github.crafteve.idlefactory.client.screen.sort.SortDirectionButton;
import io.github.crafteve.idlefactory.client.screen.sort.SortModeButton;
import io.github.crafteve.idlefactory.client.wand.WandLinkCache;
import io.github.crafteve.idlefactory.client.screen.FeedbackSink;
import io.github.crafteve.idlefactory.client.screen.terminal.passiveio.PassiveIoJeiIngredients.AddTarget;
import io.github.crafteve.idlefactory.client.screen.terminal.passiveio.PassiveIoJeiIngredients.JeiTarget;
import io.github.crafteve.idlefactory.client.screen.terminal.passiveio.PassiveIoLayout.Hit;
import io.github.crafteve.idlefactory.client.screen.terminal.passiveio.PassiveIoLayout.Kind;
import io.github.crafteve.idlefactory.client.screen.terminal.passiveio.PassiveIoLayout.Viewport;
import io.github.crafteve.idlefactory.client.screen.terminal.passiveio.PassiveIoModel.BlockView;
import io.github.crafteve.idlefactory.client.screen.terminal.passiveio.PassiveIoModel.RuleRow;
import io.github.crafteve.idlefactory.integration.jecharacters.JecharactersCompat;
import io.github.crafteve.idlefactory.menu.FactoryPassiveIoMenu;
import io.github.crafteve.idlefactory.menu.FactoryPassiveIoView;
import io.github.crafteve.idlefactory.menu.FactorySortMode;
import io.github.crafteve.idlefactory.command.CommandResult;
import io.github.crafteve.idlefactory.network.payload.FactoryPassiveIoViewC2S;
import io.github.crafteve.idlefactory.client.screen.ViewTableSink;
import io.github.crafteve.idlefactory.network.payload.ViewTableRequestC2S;
import io.github.crafteve.idlefactory.view.ViewTable;
import io.github.crafteve.idlefactory.view.ViewTableDiff;
import io.github.crafteve.idlefactory.network.payload.FactoryTerminalSortC2S;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
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
 * <p>每 {@link #REQUEST_INTERVAL} tick 拉一份机器表；界面不靠服务端
 * 主动推送，否则切界面或重开时数据会停在旧值。编辑反馈不在界面上展示，动作结果只由随后
 * 到达的机器快照体现。</p>
 */
public final class PassiveIoScreen extends AEBaseScreen<FactoryPassiveIoMenu>
        implements ViewTableSink, FeedbackSink {
    /** 最少内容行数；少于两行时 AE2 的首行 / 末行切片会重叠。 */
    private static final int MIN_ROWS = 3;
    /** 内容区左右内边距。 */
    private static final int CONTENT_PADDING = 4;
    /** 右侧滚动条占用的宽度；内容不能压到滚动条下面。 */
    private static final int SCROLLBAR_RESERVE = 20;
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
    /** 当前排序档位；初值来自物品组件，切换后回写。 */
    private FactorySortMode sortMode;
    /** 当前排序方向；初值来自物品组件，切换后回写。 */
    private SortDir sortDirection;
    /** 内容模型：视图表 + 搜索词 → 机器块。 */
    private final PassiveIoModel model;
    /** 视图表的分页拼装与差量应用；第 5 步提升为终端数据源。 */
    private final TerminalSource source;
    /** 本帧排版；由模型、视图与视口重建。 */
    @Nullable
    private PassiveIoLayout layout;
    /** 内容区绘制器；init 时按当前字体与主题创建。 */
    @Nullable
    private PassiveIoRender render;
    /** 滚动状态与 AE2 滚动条。 */
    /** 内容区滚动像素；拖动与滚轮都写它。 */
    private int scroll;
    /** AE2 右侧滚动条；init 时创建。 */
    @Nullable
    private Scrollbar bar;
    /** 请求节拍计数。 */
    private int requestTicker;
    /** 顶栏搜索框。 */
    @Nullable
    private SearchEditBox searchBox;
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
    public PassiveIoScreen(FactoryPassiveIoMenu menu, Inventory inventory,
            Component title, ScreenStyle style) {
        super(menu, inventory, title, style);
        this.view = menu.host().view();
        this.source = new TerminalSource(menu.containerId);
        this.model = new PassiveIoModel(this.source, FactoryGuiTheme.current());
        this.sortMode = menu.host().sortMode();
        this.sortDirection = menu.host().sortDirection();
        this.model.setSort(this.sortMode, this.sortDirection);
        this.model.setPlayerContext(
                this.minecraft == null || this.minecraft.player == null
                        ? null : this.minecraft.player.level().dimension(),
                this.minecraft == null || this.minecraft.player == null
                        ? null : this.minecraft.player.blockPosition());
        this.bar = this.widgets.addScrollBar("scrollbar", Scrollbar.BIG);
        // AE2 自带的帮助按钮只在 getHelpTopic() 非空时显示；本模组还没有指南页，
        // 先用一个同图标、同位置的按钮占位，等指南页确定后再换成 GuideME 跳转。
        this.addToLeftToolbar(new PassiveIoHelpButton(button -> {
            // 指南页待接入。
        }));
        this.addToLeftToolbar(new SettingToggleButton<>(Settings.TERMINAL_STYLE,
                AEConfig.instance().getTerminalStyle(), this::toggleTerminalStyle));
        this.addToLeftToolbar(new SortModeButton(() -> this.sortMode,
                button -> this.cycleSortMode()));
        this.addToLeftToolbar(new SortDirectionButton(() -> this.sortDirection,
                button -> this.toggleSortDirection()));
        this.addToLeftToolbar(new PassiveIoViewButton(() -> this.view,
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
        // base_terminal.json 带一条 ME 终端的「条目数」文本，本界面不用它，必须显式隐藏。
        this.setTextHidden("entriesShown", true);
        this.render = new PassiveIoRender(this.font, FactoryGuiTheme.current());
        this.searchBox = new SearchEditBox(this.font,
                this.leftPos + SEARCH_X + 3, this.topPos + SEARCH_Y + 2,
                SEARCH_W - 6, 10, FactoryGuiTheme.current(), Component.literal("搜索机器…"));
        this.searchBox.setValue(rememberedQuery);
        this.searchBox.setResponder(this::applyQuery);
        this.addRenderableWidget(this.searchBox);
        this.applyQuery(rememberedQuery);
        requestTable();
        WandLinkCache.request();
    }

    @Override
    public void drawBG(GuiGraphics graphics, int offsetX, int offsetY, int mouseX, int mouseY,
            float partialTicks) {
        super.drawBG(graphics, offsetX, offsetY, mouseX, mouseY, partialTicks);
        TerminalChrome.drawFrame(graphics, this.style.getTerminalStyle(), this.leftPos,
                this.topPos, this.rows);
        // AE2 的终端行贴图中间带一排物品槽（ME 终端用来放仓库格）；本界面内容区自绘，
        // 必须把这块槽位网格盖成纯面板色，否则空内容区会露出一片格子。右侧 174 之后是
        // AE2 终端自带的滚动条滑轨（178~183），必须留着，否则滚动条只剩滑块没有轨道。
        int header = this.style.getTerminalStyle().getHeader().getSrcHeight();
        graphics.fill(this.leftPos + 2, this.topPos + header,
                this.leftPos + this.imageWidth - 21,
                this.topPos + header + this.rows * rowHeight(),
                FactoryGuiTheme.current().panelBg());
        // 填充停在滑轨左侧，保留 AE2 终端贴图自带的滑轨与 Scrollbar 原始渲染。
        Viewport frame = workAreaFrame();
        TerminalChrome.drawWorkAreaFrame(graphics, frame.x(), frame.y(), frame.width(),
                frame.height());
        TerminalChrome.drawSearchField(graphics, this.leftPos + SEARCH_X,
                this.topPos + SEARCH_Y, SEARCH_W, SEARCH_H);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.layout != null) {
            syncFromBar();
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        TerminalChrome.drawTitle(graphics, this.font, this.leftPos, this.topPos, "被动 IO");
        Hit hover = this.layout == null ? null : this.layout.hit(mouseX, mouseY, scroll());
        drawContent(graphics, hover);
        List<Component> tooltip = PassiveIoTooltip.of(this.model.blocks(), hover);
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
        PassiveIoRender content = this.render;
        PassiveIoLayout current = this.layout;
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
            scrollBy((int) (-scrollY * PassiveIoLayout.ROW_H));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public int containerId() {
        return this.menu.containerId;
    }

    @Override
    public void acceptFullPage(int containerId, long revision, int pageIndex, int pageCount,
            ViewTable page) {
        if (containerId != containerId()) {
            return;
        }
        ViewTable merged = this.source.acceptFullPage(revision, pageIndex, pageCount, page);
        if (merged != null) {
            acceptTable(merged);
        }
    }

    @Override
    public void acceptDiff(int containerId, ViewTableDiff diff) {
        if (containerId != containerId()) {
            return;
        }
        ViewTable merged = this.source.acceptDiff(diff);
        if (merged == null) {
            // 版本对不上：丢弃这一版，下一轮请求会带着已知版本重新要全量。
            this.source.reset();
            requestTable();
            return;
        }
        acceptTable(merged);
    }

    /**
     * 接收拼装完成的视图表并重建排版。
     *
     * @param table 视图表
     */
    private void acceptTable(ViewTable table) {
        this.model.setSort(this.sortMode, this.sortDirection);
        this.model.setPlayerContext(
                this.minecraft == null || this.minecraft.player == null
                        ? null : this.minecraft.player.level().dimension(),
                this.minecraft == null || this.minecraft.player == null
                        ? null : this.minecraft.player.blockPosition());
        this.model.accept(table);
        rebuildLayout();
        int signature = table.nodes().size() * 37 + totalRules();
        if (signature != this.lastLogSignature) {
            this.lastLogSignature = signature;
        }
    }

    /**
     * 编辑反馈不再展示。
     *
     * <p>终端里没有提示条：动作是否生效由随后到达的机器快照体现，失败时规则会原样留在表里。
     * 接口是两屏共用的，节点终端仍需要这条反馈，所以这里保留空实现而不是删方法。</p>
     *
     * @param result 回执
     */
    @Override
    public void acceptCommandResult(CommandResult result) {
        // 不展示、不记状态：是否生效由随后到达的机器快照体现。
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
    }

    /**
     * 接收服务端确认的排序偏好。
     *
     * <p>本地已经先改过；这里只处理两端不一致的情况（比如旧回执），一致时不做任何重排。</p>
     *
     * @param mode      档位
     * @param direction 方向
     */
    public void acceptSort(FactorySortMode mode, SortDir direction) {
        if (this.sortMode == mode && this.sortDirection == direction) {
            return;
        }
        this.sortMode = mode;
        this.sortDirection = direction;
        this.model.setSort(this.sortMode, this.sortDirection);
        this.model.setPlayerContext(
                this.minecraft == null || this.minecraft.player == null
                        ? null : this.minecraft.player.level().dimension(),
                this.minecraft == null || this.minecraft.player == null
                        ? null : this.minecraft.player.blockPosition());
        this.model.resort();
        rebuildLayout();
    }

    /** 档位按钮：循环到下一个档位。 */
    private void cycleSortMode() {
        applySort(this.sortMode.next(), this.sortDirection);
    }

    /** 方向按钮：升序 / 降序互换。 */
    private void toggleSortDirection() {
        SortDir next = this.sortDirection == SortDir.ASCENDING
                ? SortDir.DESCENDING : SortDir.ASCENDING;
        applySort(this.sortMode, next);
    }

    /**
     * 应用一次排序变化：本地先改并重排，再发请求让服务端写物品组件。
     *
     * @param mode      新档位
     * @param direction 新方向
     */
    private void applySort(FactorySortMode mode, SortDir direction) {
        if (this.sortMode == mode && this.sortDirection == direction) {
            return;
        }
        this.sortMode = mode;
        this.sortDirection = direction;
        this.model.setSort(this.sortMode, this.sortDirection);
        this.model.setPlayerContext(
                this.minecraft == null || this.minecraft.player == null
                        ? null : this.minecraft.player.level().dimension(),
                this.minecraft == null || this.minecraft.player == null
                        ? null : this.minecraft.player.blockPosition());
        this.model.resort();
        rebuildLayout();
        PacketDistributor.sendToServer(
                new FactoryTerminalSortC2S(this.menu.containerId, mode, direction));
    }

    /**
     * 当前排序上下文：档位、方向与玩家位置。
     *
     * <p>玩家位置只在快照到达时取一次，距离档因此按快照节拍重排，不做每帧重排。</p>
     *
     * @return 排序上下文
     */
    private SortContext sortContext() {
        if (this.minecraft == null || this.minecraft.player == null) {
            return new SortContext(this.sortMode, this.sortDirection, null, null);
        }
        return new SortContext(this.sortMode, this.sortDirection,
                this.minecraft.player.level().dimension(),
                this.minecraft.player.blockPosition());
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
        this.layout = PassiveIoLayout.of(this.model.blocks(), viewport, this.view);
        clampScroll();
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
        return this.scroll;
    }

    /** 当前排版；可能为 {@code null}。 */
    @Nullable
    PassiveIoLayout layout() {
        return this.layout;
    }

    /** 当前模型。 */
    PassiveIoModel model() {
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
        return PassiveIoJeiIngredients.addTargets(this.layout, scroll());
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
        PassiveIoActions.add(this.menu.containerId, this.model.blocks().get(block), key);
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
        return PassiveIoJeiIngredients.targetAt(this.layout, this.model.blocks(), scroll(),
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
        if (hit.kind() == Kind.ENERGY) {
            // FE 按钮不挂在规则行上，必须先于规则下标守卫处理。
            if (button == 0) {
                PassiveIoActions.addEnergy(this.menu.containerId, block);
            }
            return;
        }
        if (hit.rule() < 0 || hit.rule() >= block.rules().size()) {
            return;
        }
        RuleRow rule = block.rules().get(hit.rule());
        if (button == 1) {
            switch (hit.kind()) {
                case CHIP_DIR -> PassiveIoActions.flip(this.menu.containerId, block, rule);
                case CHIP_FACE -> PassiveIoActions.cycleFace(this.menu.containerId, block,
                        rule, true);
                case CHIP_MODE -> PassiveIoActions.toggleMode(this.menu.containerId, block,
                        rule);
                case RULE -> PassiveIoActions.delete(this.menu.containerId, block, rule);
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
            case CHIP_DIR -> PassiveIoActions.flip(this.menu.containerId, block, rule);
            case CHIP_FACE -> PassiveIoActions.cycleFace(this.menu.containerId, block,
                    rule, false);
            case CHIP_MODE -> PassiveIoActions.toggleMode(this.menu.containerId, block,
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
        this.model.setQuery(SearchQuery.parse(rememberedQuery,
                JecharactersCompat::contains));
        rebuildLayout();
    }

    private void requestTable() {
        if (this.minecraft != null && this.minecraft.player != null) {
            PacketDistributor.sendToServer(
                    new ViewTableRequestC2S(this.menu.containerId, this.source.revision()));
        }
    }

    private String emptyText() {
        if (!this.model.hasFactory()) {
            return "工厂未就绪：重新打开一次终端";
        }
        if (this.model.filteredEmpty()) {
            return "没有匹配的机器";
        }
        return "还没有机器 · 用机器绑定杖右键方块接入";
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

    /** 把滚动夹到当前排版上限，并把范围同步给 AE2 滚动条。 */
    private void clampScroll() {
        if (this.bar == null || this.layout == null) {
            return;
        }
        int height = this.layout.viewport().height();
        this.scroll = Mth.clamp(this.scroll, 0, this.layout.maxScroll(height));
        this.bar.setHeight(height);
        this.bar.setRange(0, this.layout.maxScroll(height), height);
        this.bar.setCurrentScroll(this.scroll);
        this.bar.setVisible(true);
    }

    /**
     * 按像素滚动。
     *
     * @param delta 正数向下
     */
    private void scrollBy(int delta) {
        if (this.bar == null || this.layout == null) {
            return;
        }
        int height = this.layout.viewport().height();
        this.scroll = Mth.clamp(this.scroll + delta, 0, this.layout.maxScroll(height));
        this.bar.setCurrentScroll(this.scroll);
    }

    /** 读取滚动条位置（拖动由 AE2 控件改），越界时夹紧。 */
    private void syncFromBar() {
        if (this.bar == null || this.layout == null) {
            return;
        }
        int value = this.bar.getCurrentScroll();
        if (value != this.scroll) {
            this.scroll = Mth.clamp(value, 0, this.layout.maxScroll(this.layout.viewport().height()));
        }
    }
}