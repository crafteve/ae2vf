package io.github.crafteve.idlefactory.client.screen;

import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.factory.resource.ResourceKind;
import io.github.crafteve.idlefactory.integration.jecharacters.JecharactersCompat;
import io.github.crafteve.idlefactory.menu.FactoryMenu;
import io.github.crafteve.idlefactory.network.payload.FactoryEditFeedbackS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryStorageRequestC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryStorageS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTableRequestC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryTableS2C;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 工厂工作台界面。
 *
 * <p>界面分成四块：顶栏负责工厂状态、产线筛选和统计；左表显示服务端
 * {@link FactoryTableS2C} 给出的机器视图；右侧上半区在「仓库」和「机器」
 * 两个 tab 间切换；右上角搜索框只过滤当前可见内容，不改变服务端数据。</p>
 *
 * <p>左表的每一行对应一个 {@link FactorySnapshotBuilder.MachineView}。行高不是固定的：
 * 机器可以展开成「输入/输出」两行规则详情，所以绘制和命中测试统一走
 * {@link FactoryScreenGeometry#tableHitAt(double)}，不能再用固定 {@code rowY} 推算。</p>
 *
 * <p>仓库区不伪造原版槽位，而是用 {@link FactoryStorageRepo} 维护服务端快照。
 * 仓库格点击只发送 {@code FactoryStorageActionC2S} 手势，实际数量、背包扣减和
 * carried 同步全部由服务端决定；这样 AE2 的大数量物品和容器仓库都能走同一条路。</p>
 *
 * <p>机器库使用深色卡片、独立图标槽和状态色条；所有静态外观来自 {@link FactoryNineSlice}
 * 贴图，本类只负责坐标与绘制顺序。几何值集中在 {@link FactoryLayout}，调布局尺寸
 * 不应改这里。</p>
 *
 * <p>分节目录：01 字段与生命周期；02 快照请求与接收；03 搜索、过滤与排序；
 * 04 产线选择与快照访问；06 布局日志与玩家槽位；07 绘制入口与静态底板；
 * 10 表格命中、拖放与 IO 展开操作；15 玩家槽位绘制；17 JEI 目标与布局访问。</p>
 *
 * <p>下面的分节只用于导航，不代表这个类已经拆分；真正的拆分另开计划，不在注释整理里
 * 移动方法归属。分节头按现有代码顺序放置，不改变字段或方法的声明顺序。</p>
 */
public class FactoryScreen extends AbstractContainerScreen<FactoryMenu>
        implements FactoryScreenContext {
    // ------------------------------------------------------------------
    // ---- 01. 字段与生命周期 ----
    // 这里放常量、状态字段、构造、init 与 tick；只描述壳的生命周期，业务派生在 03/04。
    // ------------------------------------------------------------------

    /** 左表每隔多少 tick 主动请求一次机器快照。 */
    private static final int REQUEST_INTERVAL = 20;
    /** 仓库 tab 打开时每隔多少 tick 刷新一次仓库快照。 */
    private static final int STORAGE_REQUEST_INTERVAL = 100;
    /** tab 下标：仓库。 */
    static final int TAB_STORAGE = 0;
    /** tab 下标：机器。 */
    static final int TAB_MACHINES = 1;

    /** 界面唯色源；客户端主题配置取一次，避免每帧重复查。 */
    private final FactoryGuiTheme theme = FactoryGuiTheme.current();
    /** 客户端状态与派生视图；绘制/命中/输入都从这里读。 */
    final FactoryScreenState state = new FactoryScreenState(this.theme);
    /** 几何/命中；绘制、输入、JEI 共用。 */
    private final FactoryScreenGeometry geometry = new FactoryScreenGeometry(this);
    /** 顶栏/右区静态底板。 */
    private final FactoryChromeRender chromeRender = new FactoryChromeRender(this);
    /** 左表渲染。 */
    private final FactoryTableRender tableRender = new FactoryTableRender(this);
    /** 右区动态内容。 */
    private final FactoryRightContentRender rightContentRender =
            new FactoryRightContentRender(this);
    /** 全部 tooltip。 */
    private final FactoryTooltipRender tooltipRender = new FactoryTooltipRender(this);
    /** 输入层。 */
    private final FactoryScreenInput input = new FactoryScreenInput(this);
    /** JEI 目标解析。 */
    private final FactoryJeiTargets jeiTargets = new FactoryJeiTargets(this);
    /** 当前屏幕尺寸下的几何；init 重建，绘制与命中只读它。 */
    FactoryLayout layout;
    /** 搜索输入框；init 重建，关闭时把内容记进 rememberedSearch。 */
    private FactorySearchEditBox searchBox;
    /** 机器表请求节拍器。 */
    private int requestTicker;
    /** 仓库请求节拍器。 */
    private int storageRequestTicker;

    /** 编辑态总控：产线下拉、命名/确认浮层、反馈。 */
    @Nullable
    private FactoryEditController editController;
    /** 右区排序按钮：键、方向、范围。 */
    @Nullable
    private FactorySortButtons sortButtons;
    /** 本帧鼠标 x；{@code renderSlot} 不接收鼠标坐标，只能在这里暂存。 */
    private int renderMouseX;
    /** 本帧鼠标 y；与 x 一起用于槽位/卡片悬停。 */
    private int renderMouseY;

    /**
     * 构造工厂界面。
     *
     * <p>这里只设置一个最小尺寸，真正的几何在 {@link #init()} 里按屏幕大小重建；
     * 菜单与玩家背包由原版容器界面保存。</p>
     *
     * @param menu            菜单
     * @param playerInventory 玩家背包
     * @param title           标题
     */
    public FactoryScreen(FactoryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = FactoryLayout.MIN_PANEL_W;
        this.imageHeight = FactoryLayout.MIN_PANEL_H;
    }

    /**
     * 初始化界面：按当前屏幕重建几何、槽位、搜索框和排序按钮。
     *
     * <p>原版 {@code AbstractContainerScreen} 用 {@code imageWidth/imageHeight} 计算面板
     * 居中，所以这里先把它们同步成 {@link FactoryLayout} 的结果，避免自己再算一份偏移。
     * 初始化末尾会立即请求一份机器表与仓库快照，避免首帧空白。</p>
     */
    @Override
    protected void init() {
        this.layout = FactoryLayout.of(this.width, this.height);
        this.imageWidth = this.layout.panelW;
        this.imageHeight = this.layout.panelH;
        // AbstractContainerScreen 用 imageWidth/imageHeight 计算居中位置；
        // 这里让它们和 FactoryLayout 的 panelW/panelH 完全一致，避免自己再算一份偏移。
        super.init();
        if (this.editController == null) {
            this.editController = new FactoryEditController(this, this.font, this.theme);
        }
        this.editController.rebind(this.layout);
        this.editController.closeAll();
        FactorySortButtons buttons = this.sortButtons;
        if (buttons == null) {
            buttons = new FactorySortButtons(this::onSortChanged);
            this.sortButtons = buttons;
        }
        this.state.bind(this.layout, this.font, buttons);
        if (this.state.selectedLineId == null) {
            this.state.selectedLineId = FactoryScreenState.rememberedLineId;
        }
        positionSlots();
        String previousQuery = this.searchBox == null
                ? FactoryScreenState.rememberedSearch
                : this.searchBox.getValue();
        int searchX = this.layout.rightX + 4;
        int searchW = this.layout.searchWidth();
        this.searchBox = new FactorySearchEditBox(this.font, searchX + 18,
                this.layout.searchY + 3, searchW - 32, 10,
                this.theme, Component.literal("搜索…"));
        this.searchBox.setResponder(value -> this.state.setSearchQuery(
                FactorySearchQuery.parse(value, JecharactersCompat::contains)));
        this.searchBox.setValue(previousQuery);
        this.addRenderableWidget(this.searchBox);
        this.state.setSearchQuery(
                FactorySearchQuery.parse(previousQuery, JecharactersCompat::contains));
        logLayout();
        requestTable();
        if (this.state.activeTab == TAB_STORAGE) {
            requestStorage();
        }
    }

    /**
     * 容器 tick：驱动编辑浮层计时，并按节拍请求机器表和仓库快照。
     *
     * <p>机器表每 {@link #REQUEST_INTERVAL} tick 主动拉一次，仓库只在仓库 tab 打开时
     * 每 {@link #STORAGE_REQUEST_INTERVAL} tick 拉一次；界面不能靠服务端主动推送，
     * 否则切 tab 或重开界面时数据会停在旧值。</p>
     */
    @Override
    protected void containerTick() {
        super.containerTick();
        if (this.editController != null) {
            this.editController.tick();
        }
        if (++this.requestTicker >= REQUEST_INTERVAL) {
            this.requestTicker = 0;
            requestTable();
        }
        if (this.state.activeTab == TAB_STORAGE
                && ++this.storageRequestTicker >= STORAGE_REQUEST_INTERVAL) {
            requestStorage();
        }
    }

    // ------------------------------------------------------------------
    // ---- 02. 快照请求与接收 ----
    // 负责按节拍请求机器表/仓库快照并接收服务端分片；只保存服务端算好的视图，不在客户端推导。
    // ------------------------------------------------------------------

    /**
     * 向服务端要一份当前机器表。
     *
     * <p>界面不复制服务端状态，只按节拍主动拉取；这个请求由 {@link #containerTick()}
     * 驱动，打开界面时也会立即发一次，避免首帧空白。</p>
     */
    private void requestTable() {
        if (this.minecraft != null && this.minecraft.player != null) {
            PacketDistributor.sendToServer(new FactoryTableRequestC2S(this.menu.containerId));
        }
    }

    /**
     * 向服务端要一份当前仓库摘要。
     *
     * <p>只在仓库 tab 打开时按较低频率拉取；切 tab 或打开界面时会立即请求一次。
     * 请求前把节拍器清零，避免刚手动刷新又马上被 tick 触发第二次。</p>
     */
    void requestStorage() {
        this.storageRequestTicker = 0;
        if (this.minecraft != null && this.minecraft.player != null) {
            PacketDistributor.sendToServer(new FactoryStorageRequestC2S(this.menu.containerId));
        }
    }

    /**
     * 收到服务端机器表快照。
     *
     * <p>由 {@code ClientFactoryTableHandler} 在客户端线程调用。这里只保存服务端已经
     * 计算好的视图，不重新观察世界、不推导工作状态；收到后同步清理已不存在的展开行。</p>
     *
     * @param payload 机器表快照
     */
    public void acceptTable(FactoryTableS2C payload) {
        this.state.acceptTable(payload);
        if (this.editController != null) {
            this.editController.onTable(this.state.lines, this.state.selectedLineId,
                    this.state.hasFactory);
        }
    }

    /**
     * 收到服务端仓库快照。
     *
     * <p>快照可能分多个包到达：{@code part == 0} 表示重新开始，{@code last} 到齐才整体替换
     * {@link #storageRepo}，避免界面闪到半份数据。替换后仍保持已有条目的相对顺序，只追加新条目，
     * 防止 Shift 连续取物时格子因数量降序重排而跳动。</p>
     *
     * @param payload 仓库快照分片
     */
    public void acceptStorage(FactoryStorageS2C payload) {
        this.state.acceptStorage(payload);
    }

    /**
     * 收到编辑动作的服务端反馈。
     *
     * <p>界面打开时聊天栏不可见，所以反馈在左表底部临时显示；命名浮层/IO 编辑区后续会
     * 在自己的状态行里显示同一份文案。</p>
     *
     * @param payload 反馈
     */
    public void acceptEditFeedback(FactoryEditFeedbackS2C payload) {
        if (this.editController != null) {
            this.editController.onFeedback(payload);
        }
    }

    // ------------------------------------------------------------------
    // ---- 03. 搜索、过滤与排序 ----
    // 负责把搜索条件、排序按钮和仓库/机器快照合成两侧可见列表，并夹紧滚动位置。
    // ------------------------------------------------------------------

    /**
     * 重新计算仓库和机器库的可见视图。
     *
     * <p>搜索框同时过滤左右两侧：仓库走 {@link FactoryStorageRepo#configure}，
     * 机器库直接保留能匹配的 {@link FactorySnapshotBuilder.MachineView}。
     * 过滤后还要夹紧两个滚动位置，避免搜索把当前页缩短后越界。</p>
     */
    private void rebuildFiltered() {
        this.state.rebuildFiltered();
    }

    /**
     * 排序键或方向变化后重建视图。
     *
     * <p>排序变化会让原来的滚动位置失去意义，所以两个列表都回到顶部；搜索过滤保持不变。</p>
     */
    private void onSortChanged() {
        this.state.onSortChanged();
    }

    // ------------------------------------------------------------------
    // ---- 04. 产线选择与快照访问 ----
    // 负责当前产线选择，并把服务端快照以只读视图暴露给编辑控制器和输入层。
    // ------------------------------------------------------------------

    /**
     * 选中一条产线；切换后左表从第一行重新显示。
     *
     * @param lineId 产线 id；{@code null} 表示清空
     */
    void selectLine(@Nullable UUID lineId) {
        this.state.selectLine(lineId);
    }

    /**
     * 当前选中产线 id，给编辑控制器读。
     *
     * @return 产线 id；未选择时为 {@code null}
     */
    @Nullable
    UUID selectedLineId() {
        return this.state.selectedLineId;
    }

    /**
     * 服务端最近一次给的产线列表。
     *
     * @return 产线视图
     */
    List<FactorySnapshotBuilder.LineView> lines() {
        return this.state.lines;
    }

    /**
     * 服务端最近一次给的机器视图。
     *
     * @return 机器视图
     */
    List<FactorySnapshotBuilder.MachineView> machines() {
        return this.state.machines;
    }

    /**
     * 玩家是否已有工厂。
     *
     * @return 有工厂为 {@code true}
     */
    boolean hasFactory() {
        return this.state.hasFactory;
    }

    // ------------------------------------------------------------------
    // ---- 06. 布局日志与玩家槽位 ----
    // 负责把最终几何写进日志，并在 init 时重建 36 个玩家槽位；坐标只在这里换算。
    // ------------------------------------------------------------------

    /**
     * 把当前布局打进日志。
     *
     * <p>尺寸问题（挤压配方查看器、列被裁掉）只有在具体分辨率 + GUI 缩放下才复现，
     * 从截图反推缩放并不可靠。因此把面板、左右区、行数和列宽一次打进日志，
     * 排障时只需要看这一行。</p>
     */
    private void logLayout() {
        FactoryLayout l = this.layout;
        StringBuilder columns = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) {
                columns.append(' ');
            }
            columns.append(l.columnW(i) == 0 ? "-" : String.valueOf(l.columnW(i)));
        }
        double guiScale = this.minecraft == null ? -1.0D : this.minecraft.getWindow().getGuiScale();
        IdleFactory.LOGGER.info(
                "Idle Factory UI: screen={}x{} guiScale={} gutter={} panel={}x{} left={} right={}"
                        + " rows={} gridRows={} columns=[{}]",
                this.width, this.height, guiScale, FactoryLayout.gutter(this.width),
                l.panelW, l.panelH, l.leftW, l.rightW, l.rowsVisible, l.gridRows, columns);
    }

    /**
     * 把 36 个玩家槽位摆到右区底部。
     *
     * <p>1.21.1 的 {@code Slot.x/y} 是 final，不能直接改坐标，所以按新坐标重建
     * Slot 对象；容器和容器索引保持不变，服务端不受影响（服务端不需要这些坐标）。</p>
     *
     * <p>Slot 坐标是相对面板左上角的，而 {@link #leftPos}/{@link #topPos} 是面板在
     * 屏幕上的偏移，因此这里要先减掉偏移。前 9 格是快捷栏，后 27 格是主背包。</p>
     */
    private void positionSlots() {
        FactoryLayout l = this.layout;
        int relInvX = l.invX - this.leftPos;
        int relInvY = l.invY - this.topPos;
        int relHotbarY = l.hotbarY - this.topPos;

        List<Slot> slots = this.menu.slots;
        int count = Math.min(FactoryMenu.PLAYER_SLOTS, slots.size());
        for (int i = 0; i < count; i++) {
            Slot old = slots.get(i);
            int x;
            int y;
            if (i < 9) {
                x = relInvX + i * FactoryLayout.CELL + 1;
                y = relHotbarY + 1;
            } else {
                int index = i - 9;
                x = relInvX + index % FactoryLayout.GRID_COLS * FactoryLayout.CELL + 1;
                y = relInvY + index / FactoryLayout.GRID_COLS * FactoryLayout.CELL + 1;
            }
            // Slot.x/y 是 final，只能按新坐标重建；容器与索引保持原值，服务端交互不受影响。
            Slot slot = new Slot(old.container, old.getContainerSlot(), x, y);
            slot.index = i;
            slots.set(i, slot);
        }
    }

    // ------------------------------------------------------------------
    // ---- 07. 绘制入口与静态底板 ----
    // 负责一帧的绘制顺序、面板底图、右区静态 chrome、表格外框和通用凹槽网格。
    // ------------------------------------------------------------------

    /**
     * 绘制整个工厂界面。
     *
     * <p>先让原版画容器底图与槽位，再按「顶栏 → 左表 → 右区 → tooltip → 编辑浮层」
     * 的顺序叠自定义内容。tooltip 必须最后画，浮层打开时由编辑控制器统一抑制，
     * 避免菜单被物品提示穿透。</p>
     *
     * @param graphics    画布
     * @param mouseX      鼠标 x
     * @param mouseY      鼠标 y
     * @param partialTick 渲染插值
     */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderMouseX = mouseX;
        this.renderMouseY = mouseY;
        flushPendingStack();
        super.render(graphics, mouseX, mouseY, partialTick);
        this.chromeRender.drawHeader(graphics);
        this.tableRender.drawTable(graphics);
        this.rightContentRender.draw(graphics, mouseX, mouseY);
        this.tooltipRender.draw(graphics, mouseX, mouseY);
        if (this.editController != null) {
            this.editController.draw(graphics, mouseX, mouseY, partialTick);
        }
    }

    /**
     * 把滚轮累计的台数发给服务端。
     *
     * <p>滚轮一格在 Windows 上常一次发多个事件，所以这里按「停手 150ms 合并一次」发送。
     * 发出后本地乐观显示最多保留 500ms，等快照回来再校正；过期没等到就回到快照值，
     * 不会一直显示一个服务端没接受的数字。</p>
     */
    private void flushPendingStack() {
        FactoryEditController controller = this.editController;
        if (controller == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (this.state.pendingStackDue(now)) {
            int target = this.state.pendingStackTarget;
            UUID anchorId = this.state.pendingStackAnchorId;
            if (anchorId == null) {
                return;
            }
            this.state.markPendingStackSent(now);
            controller.applyStackCount(anchorId, target);
        } else if (this.state.pendingStackExpired(now)) {
            this.state.clearPendingStack();
        }
    }

    /**
     * 画面板底图与静态分隔线。
     *
     * <p>原版先画容器背景，这里再叠九宫格面板、顶栏/左右区边线、表头和右区 chrome；
     * 动态行、物品和文字由 {@link #render} 的后半段画，避免底图反复覆盖内容。</p>
     *
     * @param graphics    画布
     * @param partialTick 渲染插值
     * @param mouseX      鼠标 x
     * @param mouseY      鼠标 y
     */
    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        FactoryLayout l = this.layout;

        FactoryNineSlice.panel(graphics, this.leftPos, this.topPos, this.imageWidth, this.imageHeight);

        // 顶栏下沿只画到右区左边缘，右侧 tab 条自己带走自己的边线。
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.DIVIDER_DARK,
                l.headerX, l.headerY + FactoryLayout.HEADER_H, l.rightX - l.headerX, 1);

        // 左右两区之间的竖向分隔线；宽度固定 1px，避免不同 GUI 缩放下变粗。
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.DIVIDER,
                l.rightX - FactoryLayout.GAP / 2, l.bodyY, 1, l.bodyH);

        // 表头整体向内缩 2px，和外层深色/白色双层表框留出层次；
        // 表框本身在 FactoryTableRender 的最后绘制，保证白色内圈不会被行卡片盖住。
        FactoryNineSlice.header(graphics, l.leftX + 2, l.tableHeaderY + 2,
                l.leftW - 4, FactoryLayout.TABLE_HEADER_H - 2);

        // 表格底部统计行上方的细分隔线。
        FactoryNineSlice.fill(graphics, FactoryGuiTextures.DIVIDER, l.tableX, l.footerY, l.tableW, 1);

        drawRightChrome(graphics);
    }

    /**
     * 右区的静态底板。
     *
     * <p>只画 tab、搜索框、仓库/机器网格的凹槽、合成占位和箭头；实际的仓库物品、
     * 机器卡片、文字与 tooltip 在 {@link FactoryRightContentRender} 里画。这样静态底图和
     * 动态数据分开，刷新数据时不会重复铺底图。</p>
     *
     * @param graphics 画布
     */
    private void drawRightChrome(GuiGraphics graphics) {
        this.chromeRender.drawRightChrome(graphics);
    }

    // ------------------------------------------------------------------
    // ---- 10. 表格命中、拖放与 IO 展开操作 ----
    // 负责左表/机器库/仓库的命中、拖放目标和 IO 卡控件；所有坐标都与绘制共用同一套布局。
    // ------------------------------------------------------------------

    /**
     * 鼠标是否在“新增机器”行上。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 命中为 {@code true}
     */
    boolean addMachineRowContains(double mouseX, double mouseY) {
        return this.geometry.addMachineRowContains(mouseX, mouseY);
    }

    /**
     * 鼠标下的机器库组行（机器 tab）。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 机器组；没命中为 {@code null}
     */
    @Nullable
    FactoryMachineStack libraryStackAt(double mouseX, double mouseY) {
        return this.geometry.libraryStackAt(mouseX, mouseY);
    }

    /**
     * 鼠标下的左表机器行（机器列），用作“拖回机器库”的源。
     *
     * <p>不只认 18×18 图标：机器名也算，减少“必须精准按住图标”的摩擦。</p>
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 机器组；没命中为 {@code null}
     */
    @Nullable
    FactoryMachineStack machineIconAt(double mouseX, double mouseY) {
        return this.geometry.machineIconAt(mouseX, mouseY);
    }

    /**
     * 鼠标是否落在机器库网格上（移出机器的拖放目标）。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 命中为 {@code true}
     */
    boolean isInMachineLibrary(double mouseX, double mouseY) {
        return this.geometry.isInMachineLibrary(mouseX, mouseY);
    }

    /**
     * 鼠标下的 IO 空卡/空槽；拖动资源时用它决定放到哪一侧。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 目标；没命中为 {@code null}
     */
    @Nullable
    FactoryScreenGeometry.IoDropTarget ioDropTargetAt(double mouseX, double mouseY) {
        return this.geometry.ioDropTargetAt(mouseX, mouseY);
    }

    /**
     * 鼠标是否在仓库网格上；拖规则回仓库删除时用它判断目标。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 命中为 {@code true}
     */
    boolean isInStorageGrid(double mouseX, double mouseY) {
        return this.geometry.isInStorageGrid(mouseX, mouseY);
    }

    // ------------------------------------------------------------------
    // ---- 15. 玩家槽位绘制 ----
    // 负责 36 个玩家槽位的底图、悬停高亮抑制和原版标签抑制；槽位坐标在 06 重建。
    // ------------------------------------------------------------------

    /**
     * 玩家背包槽位的底图。
     *
     * <p>悬停时换成浅蓝 {@code slot_hover}，其余时候用普通凹槽；物品和数量仍由
     * {@code super.renderSlot} 画在上面，不能在这里覆盖。</p>
     *
     * @param graphics 画布
     * @param slot     玩家槽位
     */
    @Override
    protected void renderSlot(GuiGraphics graphics, Slot slot) {
        if (isHovering(slot.x, slot.y, 16, 16, this.renderMouseX, this.renderMouseY)) {
            FactoryNineSlice.icon(graphics, FactoryGuiTextures.SLOT_HOVER, slot.x - 1, slot.y - 1,
                    FactoryGuiTextures.SLOT_HOVER_SIZE);
        } else {
            FactoryNineSlice.slot(graphics, slot.x - 1, slot.y - 1,
                    FactoryLayout.CELL, FactoryLayout.CELL);
        }
        super.renderSlot(graphics, slot);
    }

    /**
     * 抑制原版槽位高亮。
     *
     * <p>悬停底色已经在 {@link #renderSlot} 里由 {@code slot_hover} 接管；再叠原版
     * 白色高亮会把自定义槽位边框盖成两层。</p>
     *
     * @param graphics    画布
     * @param slot        槽位
     * @param mouseX      鼠标 x
     * @param mouseY      鼠标 y
     * @param partialTick 渲染插值
     */
    @Override
    protected void renderSlotHighlight(GuiGraphics graphics, Slot slot, int mouseX, int mouseY,
            float partialTick) {
        // 悬停底色已经在 renderSlot 里由 slot_hover 接管，不再叠原版白色高亮。
    }

    /**
     * 抑制原版标题与「物品栏」文字。
     *
     * <p>本界面自己画「放置工厂」「合成」「背包」，原版标签会和右区标签重叠；
     * 这里覆盖为空实现，不改变原版槽位的其他绘制。</p>
     *
     * @param graphics 画布
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 原版 GUI 的标题和「物品栏」文字由本类自己的标题/标签绘制替代。
    }

    /**
     * 当前鼠标位置对应的仓库条目。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 条目；空气格、非仓库 tab 或不在网格内时为 {@code null}
     */
    @Nullable
    FactoryStorageS2C.Entry storageEntryAt(double mouseX, double mouseY) {
        FactoryLayout l = this.layout;
        if (this.state.activeTab != TAB_STORAGE
                || mouseX < l.gridX
                || mouseX >= l.gridX + FactoryLayout.GRID_COLS * FactoryLayout.CELL
                || mouseY < l.gridY
                || mouseY >= l.gridY + l.gridRows * FactoryLayout.CELL) {
            return null;
        }
        int col = (int) ((mouseX - l.gridX) / FactoryLayout.CELL);
        int row = (int) ((mouseY - l.gridY) / FactoryLayout.CELL);
        int index = (this.state.storageFirstRow + row) * FactoryLayout.GRID_COLS + col;
        return index >= 0 && index < this.state.filteredStorageEntries.size()
                ? this.state.filteredStorageEntries.get(index)
                : null;
    }

    /**
     * 关闭界面时保存搜索词和产线选择。
     *
     * <p>这两项是纯客户端状态，跨打开保留；产线还会在 acceptTable 里校验，
     * 如果服务端已经没有该产线就自动清空。</p>
     */
    @Override
    public void removed() {
        if (this.searchBox != null) {
            FactoryScreenState.rememberedSearch = this.searchBox.getValue();
        }
        FactoryScreenState.rememberedLineId = this.state.selectedLineId;
        if (this.editController != null) {
            this.editController.closeAll();
        }
        super.removed();
    }

    // ------------------------------------------------------------------
    // ---- 17. JEI 目标与布局访问 ----
    // 负责把鼠标下的槽位/规则/机器转成 JEI 目标，并暴露只读布局给调试与测试。
    // ------------------------------------------------------------------

    /**
     * JEI R/U 查询目标：资源类型、物品图标和它在屏幕上的格子区域。
     *
     * @param kind  资源类型；{@code ENERGY} 走 JEI 的 FE 配料，其余按物品/桶处理
     * @param stack 物品图标；能量没有物品形态，固定为空
     * @param area  可点击区域
     */
    public record JeiTarget(ResourceKind kind, ItemStack stack, Rect2i area) {
    }

    /**
     * 当前鼠标下的 JEI 查询目标。
     *
     * <p>覆盖左表机器/输入/输出、仓库格、机器库卡片和玩家背包槽位。只返回界面
     * 上真实画出来的物品；流体按桶图标处理，能量交给独立的 FE 配料（不再退回红石粉）。
     * JEI 插件拿到这个目标后会自己创建 clickable ingredient。</p>
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 目标；鼠标下没有物品时为 {@code null}
     */
    @Nullable
    public JeiTarget jeiTargetAt(double mouseX, double mouseY) {
        return this.jeiTargets.at(mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return this.input.mouseClicked(mouseX, mouseY, button)
                || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return this.input.keyPressed(keyCode, scanCode, modifiers)
                || super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return this.input.charTyped(codePoint, modifiers)
                || super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX,
            double dragY) {
        return this.input.mouseDragged(mouseX, mouseY, button)
                || super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return this.input.mouseReleased(mouseX, mouseY, button)
                || super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return this.input.mouseScrolled(mouseX, mouseY, scrollY)
                || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /**
     * 编辑控制器；输入层需要调用它的浮层/拖拽动作。
     *
     * @return 控制器；init 前为 {@code null}
     */
    @Nullable
    FactoryEditController editController() {
        return this.editController;
    }

    /**
     * 关闭界面；输入层不能直接调用 protected 的 {@code onClose()}。
     */
    void closeScreen() {
        this.onClose();
    }

    @Override
    public FactoryScreenState state() {
        return this.state;
    }

    @Override
    public FactoryScreenGeometry geometry() {
        return this.geometry;
    }

    @Override
    public io.github.crafteve.idlefactory.menu.FactoryMenu menu() {
        return this.menu;
    }

    @Override
    public int leftPos() {
        return this.leftPos;
    }

    @Override
    public int topPos() {
        return this.topPos;
    }

    @Override
    public FactoryLayout layout() {
        return this.layout;
    }

    @Override
    public FactoryGuiTheme theme() {
        return this.theme;
    }

    @Override
    public net.minecraft.client.gui.Font font() {
        return this.font;
    }

    @Override
    @Nullable
    public FactoryScreenInteraction interaction() {
        return this.editController;
    }

    @Override
    @Nullable
    public FactorySearchEditBox searchBox() {
        return this.searchBox;
    }

    @Override
    @Nullable
    public FactorySortButtons sortButtons() {
        return this.sortButtons;
    }

    @Override
    public int mouseX() {
        return this.renderMouseX;
    }

    @Override
    public int mouseY() {
        return this.renderMouseY;
    }
}

