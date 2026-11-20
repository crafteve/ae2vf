package io.github.crafteve.idlefactory.client.screen.terminal.node;

import io.github.crafteve.idlefactory.client.screen.terminal.PassiveIoHelpButton;


import io.github.crafteve.idlefactory.client.screen.terminal.TerminalChrome;

import io.github.crafteve.idlefactory.client.screen.terminal.TerminalSource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import appeng.api.config.Settings;
import appeng.api.config.SortDir;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.style.TerminalStyle;
import appeng.client.gui.widgets.Scrollbar;
import appeng.client.gui.widgets.SettingToggleButton;
import appeng.core.AEConfig;

import io.github.crafteve.idlefactory.config.IdleFactoryConfig;

import io.github.crafteve.idlefactory.client.screen.FactoryConfirmDialog;

import io.github.crafteve.idlefactory.client.screen.FeedbackSink;


import io.github.crafteve.idlefactory.client.screen.search.SearchEditBox;

import io.github.crafteve.idlefactory.client.screen.search.SearchQuery;

import io.github.crafteve.idlefactory.client.screen.sort.SortContext;

import io.github.crafteve.idlefactory.client.screen.sort.SortDirectionButton;

import io.github.crafteve.idlefactory.client.screen.sort.SortModeButton;

import io.github.crafteve.idlefactory.client.screen.terminal.node.NodeLayout.Hit;

import io.github.crafteve.idlefactory.client.screen.terminal.node.NodeLayout.Viewport;

import io.github.crafteve.idlefactory.client.screen.terminal.node.NodeModel.MemberRow;

import io.github.crafteve.idlefactory.client.screen.terminal.node.NodeModel.NodeRow;

import io.github.crafteve.idlefactory.client.wand.WandLinkCache;

import io.github.crafteve.idlefactory.integration.jecharacters.JecharactersCompat;

import io.github.crafteve.idlefactory.menu.FactoryNodeMenu;

import io.github.crafteve.idlefactory.menu.FactorySortMode;

import io.github.crafteve.idlefactory.client.screen.text.CommandText;
import io.github.crafteve.idlefactory.command.CommandResult;

import io.github.crafteve.idlefactory.client.screen.ViewTableSink;
import io.github.crafteve.idlefactory.network.payload.ViewTableRequestC2S;
import io.github.crafteve.idlefactory.view.ViewNode;
import io.github.crafteve.idlefactory.view.ViewTable;
import io.github.crafteve.idlefactory.view.ViewTableDiff;

import io.github.crafteve.idlefactory.network.payload.FactoryTerminalSortC2S;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 节点管理终端：AE2 终端边框 + 36 个玩家背包槽 + 自绘节点卡片列表。
 *
 * <p>壳与被动 IO 终端一致：每 {@link #REQUEST_INTERVAL} tick 拉一次节点表，内容区先于
 * {@code super.mouseClicked} 处理点击。节点行复用 IO 的卡片、键帽与状态灯；展开后的机器行只在
 * 行尾放定位。</p>
 *
 * <p>删除有规则或样板的节点会先弹确认浮层；没有配置的节点直接删除。倍增、逐台/轮询与编辑会话
 * 还没有后端，按钮保留但只提示「待接入」。</p>
 */
public final class NodeScreen extends AEBaseScreen<FactoryNodeMenu>
        implements FeedbackSink, ViewTableSink {

    /** 面板底色（AE2 终端内部实色）。 */
    private static final int PANEL_BG = 0xFFCBCCD4;
    /** 键帽 / 边框亮边。 */
    private static final int BEVEL_LIGHT = 0xFFD8D9DF;
    /** 格子暗边。 */
    private static final int SLOT_BORDER_DARK = 0xFF878FA5;
    /** 正文色。 */
    private static final int TEXT_PRIMARY = 0xFF000000;
    /** 次要文字。 */
    private static final int TEXT_DIM = 0xFF5F5F5F;
    /** 最少内容行数。 */
    private static final int MIN_ROWS = 3;
    /** 内容区左右内边距。 */
    private static final int CONTENT_PADDING = 4;
    /** 右侧滚动条占用宽度。 */
    private static final int SCROLLBAR_RESERVE = 20;
    /** 工作区描边框宽。 */
    private static final int WORK_FRAME = 3;
    /** 描边框与顶栏的间距。 */
    private static final int WORK_GAP = 2;
    /** 节点表请求节拍。 */
    private static final int REQUEST_INTERVAL = 20;
    /** 顶栏搜索框位置与尺寸。 */
    private static final int SEARCH_X = 46;
    private static final int SEARCH_Y = 4;
    private static final int SEARCH_W = 124;
    private static final int SEARCH_H = 13;
    /** 改名输入框高度。 */
    private static final int RENAME_H = 10;
    /** 跨界面保留的搜索词。 */
    private static String rememberedQuery = "";
    private int rows = MIN_ROWS;
    private final NodeModel model;
    /** 视图表的分页拼装与差量应用；第 5 步提升为终端数据源。 */
    private final TerminalSource source;
    /** 当前排序档位；初值来自物品组件，切换后回写。 */
    private FactorySortMode sortMode;
    /** 当前排序方向；初值来自物品组件，切换后回写。 */
    private SortDir sortDirection;
    @Nullable
    private NodeLayout layout;
    @Nullable
    private NodeRender render;
    @Nullable
    private Scrollbar bar;
    private int scroll;
    private int requestTicker;
    @Nullable
    private SearchEditBox searchBox;
    /** 当前展开的节点。 */
    private final Set<UUID> expanded = new HashSet<>();
    /** 点过「… N 台」的节点，展开全部成员。 */
    private final Set<UUID> expandedAll = new HashSet<>();
    @Nullable
    private FactoryConfirmDialog confirmDialog;
    /** 正在改名的节点；{@code null} 表示不在改名。 */
    @Nullable
    private UUID renamingNode;
    @Nullable
    private SearchEditBox renameBox;
    private int lastLogSignature = -1;

    /**
     * @param menu      菜单
     * @param inventory 玩家物品栏
     * @param title     标题
     * @param style     AE2 终端样式
     */
    public NodeScreen(FactoryNodeMenu menu, Inventory inventory, Component title,
            ScreenStyle style) {
        super(menu, inventory, title, style);
        this.source = new TerminalSource(menu.containerId);
        this.model = new NodeModel(this.source);
        this.sortMode = menu.host().sortMode();
        this.sortDirection = menu.host().sortDirection();
        this.model.setSort(this.sortMode, this.sortDirection);
        this.model.setPlayerContext(
                this.minecraft == null || this.minecraft.player == null
                        ? null : this.minecraft.player.level().dimension(),
                this.minecraft == null || this.minecraft.player == null
                        ? null : this.minecraft.player.blockPosition());
        this.bar = this.widgets.addScrollBar("scrollbar", Scrollbar.BIG);
        this.addToLeftToolbar(new PassiveIoHelpButton(button -> {
            // 指南页待接入。
        }));
        this.addToLeftToolbar(new SettingToggleButton<>(Settings.TERMINAL_STYLE,
                AEConfig.instance().getTerminalStyle(), this::toggleTerminalStyle));
        this.addToLeftToolbar(new SortModeButton(() -> this.sortMode,
                button -> this.cycleSortMode()));
        this.addToLeftToolbar(new SortDirectionButton(() -> this.sortDirection,
                button -> this.toggleSortDirection()));
    }

    @Override
    protected void init() {
        this.clearWidgets();
        this.searchBox = null;
        this.renameBox = null;
        this.renamingNode = null;
        TerminalStyle terminal = this.style.getTerminalStyle();
        int margin = AEConfig.instance().getTerminalMargin();
        int usable = Math.max(0, this.height - margin * 2);
        int possibleRows = terminal.getPossibleRows(usable);
        this.rows = Math.max(MIN_ROWS,
                AEConfig.instance().getTerminalStyle().getRows(possibleRows));
        this.imageWidth = terminal.getScreenWidth();
        this.imageHeight = terminal.getScreenHeight(this.rows);
        super.init();
        this.setTextHidden("entriesShown", true);
        this.render = new NodeRender(this.font);
        this.confirmDialog = new FactoryConfirmDialog(this.font);
        this.searchBox = new SearchEditBox(this.font,
                this.leftPos + SEARCH_X + 3, this.topPos + SEARCH_Y + 2,
                SEARCH_W - 6, 10, Component.literal("搜索节点…"));
        this.searchBox.setValue(rememberedQuery);
        this.searchBox.setResponder(this::applyQuery);
        this.addRenderableWidget(this.searchBox);
        this.applyQuery(rememberedQuery);
        this.requestTable();
        WandLinkCache.request();
    }

    @Override
    public void drawBG(GuiGraphics graphics, int offsetX, int offsetY, int mouseX, int mouseY,
            float partialTicks) {
        super.drawBG(graphics, offsetX, offsetY, mouseX, mouseY, partialTicks);
        TerminalChrome.drawFrame(graphics, this.style.getTerminalStyle(), this.leftPos,
                this.topPos, this.rows);
        int header = this.style.getTerminalStyle().getHeader().getSrcHeight();
        graphics.fill(this.leftPos + 2, this.topPos + header,
                this.leftPos + this.imageWidth - 21,
                this.topPos + header + this.rows * rowHeight(),
                PANEL_BG);
        Viewport frame = workAreaFrame();
        TerminalChrome.drawWorkAreaFrame(graphics, frame.x(), frame.y(), frame.width(),
                frame.height());
        TerminalChrome.drawSearchField(graphics, this.leftPos + SEARCH_X,
                this.topPos + SEARCH_Y, SEARCH_W, SEARCH_H);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        syncFromBar();
        super.render(graphics, mouseX, mouseY, partialTick);
        TerminalChrome.drawTitle(graphics, this.font, this.leftPos, this.topPos, "节点管理");
        Hit hover = this.layout == null ? null : this.layout.hit(mouseX, mouseY, this.scroll);
        drawContent(graphics, hover);
        drawRenameBox(graphics, mouseX, mouseY, partialTick);
        // 浮层打开时不再画底层的 tooltip：原版 tooltip 在 z=400，会压在弹窗上。
        boolean dialogOpen = this.confirmDialog != null && this.confirmDialog.isOpen();
        List<Component> tooltip = NodeTooltip.of(this.model.visibleNodes(), hover);
        if (!tooltip.isEmpty() && !dialogOpen) {
            graphics.renderComponentTooltip(this.font, tooltip, mouseX, mouseY);
        }
        if (this.confirmDialog != null) {
            this.confirmDialog.draw(graphics, mouseX, mouseY);
        }
    }

    private void drawContent(GuiGraphics graphics, @Nullable Hit hover) {
        NodeRender content = this.render;
        NodeLayout current = this.layout;
        if (content == null || current == null) {
            return;
        }
        Viewport viewport = current.viewport();
        graphics.enableScissor(viewport.x(), viewport.y(),
                viewport.x() + viewport.width(), viewport.y() + viewport.height());
        try {
            content.draw(graphics, current, this.model.visibleNodes(), this.scroll, hover, null,
                    emptyText());
        } finally {
            graphics.disableScissor();
        }
    }

    private void drawRenameBox(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        SearchEditBox box = this.renameBox;
        if (box == null) {
            return;
        }
        syncRenameBox();
        if (this.renameBox == null) {
            return;
        }
        graphics.fill(box.getX() - 1, box.getY() - 1, box.getX() + box.getWidth() + 1,
                box.getY() + box.getHeight() + 1, BEVEL_LIGHT);
        graphics.fill(box.getX() - 1, box.getY() - 1, box.getX() + box.getWidth() + 1,
                box.getY(), SLOT_BORDER_DARK);
        box.render(graphics, mouseX, mouseY, partialTick);
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
        if (this.confirmDialog != null && this.confirmDialog.isOpen()) {
            return this.confirmDialog.mouseClicked(mouseX, mouseY, button);
        }
        if (this.renameBox != null) {
            if (this.renameBox.mouseClicked(mouseX, mouseY, button)) {
                this.renameBox.setFocused(true);
                return true;
            }
            commitRename();
            return true;
        }
        if (this.layout != null) {
            Hit hit = this.layout.hit(mouseX, mouseY, this.scroll);
            if (hit != null) {
                clickHit(hit, button);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        NodeLayout current = this.layout;
        if (current != null && inside(current.viewport(), mouseX, mouseY)) {
            if (this.renameBox != null) {
                commitRename();
            }
            this.scroll = Mth.clamp(this.scroll + (int) (-scrollY * NodeLayout.NODE_ROW_H),
                    0, current.maxScroll(current.viewport().height()));
            if (this.bar != null) {
                this.bar.setCurrentScroll(this.scroll);
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.confirmDialog != null && this.confirmDialog.isOpen()) {
            return this.confirmDialog.keyPressed(keyCode);
        }
        if (this.renameBox != null) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                commitRename();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                cancelRename();
                return true;
            }
            this.renameBox.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.renameBox != null) {
            this.renameBox.charTyped(codePoint, modifiers);
            return true;
        }
        return super.charTyped(codePoint, modifiers);
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
        // 改名框 / 删除确认框打开时冻结行序：快照照常更新数据，但列表不在用户操作期间跳动。
        this.model.setSortFrozen(this.renameBox != null
                || (this.confirmDialog != null && this.confirmDialog.isOpen()));
        this.model.setSort(this.sortMode, this.sortDirection);
        this.model.setPlayerContext(
                this.minecraft == null || this.minecraft.player == null
                        ? null : this.minecraft.player.level().dimension(),
                this.minecraft == null || this.minecraft.player == null
                        ? null : this.minecraft.player.blockPosition());
        this.model.accept(table);
        // 表里已经消失的节点不再占用展开状态；搜索过滤不会误删展开状态。
        Set<UUID> alive = new HashSet<>();
        for (ViewNode node : table.nodes()) {
            alive.add(node.nodeId());
        }
        this.expanded.retainAll(alive);
        this.expandedAll.retainAll(alive);
        rebuildLayout();
    }

    /**
     * 反馈：失败走动作栏，成功由快照体现。
     *
     * @param result 回执
     */
    @Override
    public void acceptCommandResult(CommandResult result) {
        if (!result.ok() && this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.displayClientMessage(
                    Component.literal(CommandText.text(result)), true);
        }
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

    void rebuildLayout() {
        if (this.render == null) {
            return;
        }
        Viewport viewport = contentViewport();
        this.layout = NodeLayout.of(this.model.rows(), viewport, this.expanded,
                this.expandedAll);
        // 改名框不在这里取消：每秒一次的快照刷新也会走 rebuildLayout，取消会让改名框活不过一秒。
        // 位置在每帧 drawRenameBox 里按最新排版重贴；节点真的消失时才由 syncRenameBox 取消。
        clampScroll();
        int signature = this.model.rows().size() * 31 + totalMembers();
        if (signature != this.lastLogSignature) {
            this.lastLogSignature = signature;
        }
    }

    private void clampScroll() {
        if (this.layout == null) {
            return;
        }
        int max = this.layout.maxScroll(this.layout.viewport().height());
        this.scroll = Mth.clamp(this.scroll, 0, max);
        if (this.bar != null) {
            this.bar.setHeight(this.layout.viewport().height());
            this.bar.setRange(0, max, this.layout.viewport().height());
            this.bar.setCurrentScroll(this.scroll);
            this.bar.setVisible(true);
        }
    }

    private void syncFromBar() {
        if (this.bar == null || this.layout == null) {
            return;
        }
        int value = this.bar.getCurrentScroll();
        if (value != this.scroll) {
            this.scroll = Mth.clamp(value, 0,
                    this.layout.maxScroll(this.layout.viewport().height()));
        }
    }

    private void clickHit(Hit hit, int button) {
        if (hit.node() < 0 || hit.node() >= this.model.rows().size()) {
            return;
        }
        NodeRow node = this.model.rows().get(hit.node());
        switch (hit.kind()) {
            case NODE -> toggleExpanded(node.nodeId());
            case NAME -> beginRename(hit.node(), node);
            case BLOCKING -> {
                if (node.view().core().available()) {
                    NodeActions.setBlocking(this.menu.containerId, node,
                            !node.view().core().blocking().isOn());
                }
            }
            case MULTIPLIER -> NodeActions.cycleMultiplier(this.menu.containerId, node);
            case DISPATCH -> NodeActions.toggleDispatch(this.menu.containerId, node);
            case EDIT -> NodeActions.edit(this.menu.containerId, node);
            case DELETE -> requestDelete(node);
            case MEMBER -> {
                // 机器行主体不做事，定位在行尾准星上。
            }
            case LOCATE -> {
                if (hit.member() >= 0 && hit.member() < node.members().size()) {
                    MemberRow member = node.members().get(hit.member());
                    NodeActions.locate(node, member);
                }
            }
            case MORE -> {
                this.expanded.add(node.nodeId());
                this.expandedAll.add(node.nodeId());
                rebuildLayout();
            }
        }
    }

    private void toggleExpanded(UUID nodeId) {
        if (!this.expanded.add(nodeId)) {
            this.expanded.remove(nodeId);
            this.expandedAll.remove(nodeId);
        }
        rebuildLayout();
    }

    private void beginRename(int index, NodeRow node) {
        NodeLayout current = this.layout;
        if (current == null || index >= current.boxes().size()) {
            return;
        }
        int top = NodeLayout.screenY(current.viewport(),
                current.boxes().get(index).top(), this.scroll);
        Viewport viewport = current.viewport();
        int x = NodeLayout.nameX(viewport);
        int width = Math.max(40, NodeLayout.nameWidth(viewport));
        this.renameBox = new SearchEditBox(this.font, x, top + 5, width, RENAME_H,
                Component.literal("节点名"),
                TEXT_PRIMARY, TEXT_DIM);
        this.renameBox.setValue(node.view().core().name() == null ? ""
                : node.view().core().name());
        this.renameBox.setMaxLength(io.github.crafteve.idlefactory.config.IdleFactoryConfig.maxNameLength());
        this.renameBox.setFocused(true);
        // 搜索框让出焦点，避免两个输入框的焦点框同时亮着。
        if (this.searchBox != null) {
            this.searchBox.setFocused(false);
        }
        this.renamingNode = node.nodeId();
    }

    /**
     * 把改名框贴回正在改名的节点：布局 / 滚动 / 快照变化后都按当前几何重新定位。
     *
     * <p>节点被删除或被搜索过滤掉时取消改名，避免输入框留在空处。</p>
     */
    private void syncRenameBox() {
        SearchEditBox box = this.renameBox;
        UUID nodeId = this.renamingNode;
        NodeLayout current = this.layout;
        if (box == null || nodeId == null) {
            return;
        }
        int index = findNodeIndex(nodeId);
        if (current == null || index < 0 || index >= current.boxes().size()) {
            cancelRename();
            return;
        }
        int top = NodeLayout.screenY(current.viewport(),
                current.boxes().get(index).top(), this.scroll);
        box.setX(NodeLayout.nameX(current.viewport()));
        box.setY(top + 5);
        box.setWidth(Math.max(40, NodeLayout.nameWidth(current.viewport())));
    }

    /**
     * 节点在当前过滤结果里的下标。
     *
     * @param nodeId 节点 id
     * @return 下标；不在可见行里为 -1
     */
    private int findNodeIndex(UUID nodeId) {
        List<NodeRow> rows = this.model.rows();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).nodeId().equals(nodeId)) {
                return i;
            }
        }
        return -1;
    }

    private void commitRename() {
        SearchEditBox box = this.renameBox;
        UUID nodeId = this.renamingNode;
        if (box == null || nodeId == null) {
            cancelRename();
            return;
        }
        String value = box.getValue();
        this.renameBox = null;
        this.renamingNode = null;
        NodeRow node = findNode(nodeId);
        if (node != null) {
            NodeActions.rename(this.menu.containerId, node, value);
        }
    }

    private void cancelRename() {
        this.renameBox = null;
        this.renamingNode = null;
    }

    @Nullable
    private NodeRow findNode(@Nullable UUID nodeId) {
        if (nodeId == null) {
            return null;
        }
        for (NodeRow row : this.model.rows()) {
            if (row.nodeId().equals(nodeId)) {
                return row;
            }
        }
        return null;
    }

    private void requestDelete(NodeRow node) {
        if (!node.view().configured()) {
            NodeActions.delete(this.menu.containerId, node);
            return;
        }
        if (this.confirmDialog == null) {
            return;
        }
        // 几何交给浮层自己算：可用区域是终端窗口，压暗区域是整个屏幕——
        // AE2 左侧工具栏按钮画在 leftPos 之外，只压暗终端会让它们保持亮着。
        this.confirmDialog.open("删除节点", node.name() + "\n" + deleteDetail(node.view()),
                "删除", () -> NodeActions.delete(this.menu.containerId, node),
                new FactoryConfirmDialog.Bounds(this.leftPos, this.topPos, this.imageWidth,
                        this.imageHeight),
                new FactoryConfirmDialog.Bounds(0, 0, this.width, this.height));
    }

    /**
     * 删除确认里说清楚「删掉的是什么」：规则、样板，还是两者都有。
     *
     * <p>样板删节点时会退回背包，所以单独提一句；自动补的 FE 规则会和节点一起消失，
     * 下次接机器会再补，不需要在弹窗里吓人。</p>
     *
     * @param view 节点视图
     * @return 说明文字
     */
    private static String deleteDetail(ViewNode view) {
        int rules = view.ruleCount();
        int patterns = view.core().patternCount();
        if (rules > 0 && patterns > 0) {
            return "有 " + rules + " 条 IO 规则、" + patterns + " 份样板，删除后不可恢复。";
        }
        if (patterns > 0) {
            return "有 " + patterns + " 份样板（会退回背包），删除后不可恢复。";
        }
        return "有 " + rules + " 条 IO 规则，删除后不可恢复。";
    }

    /**
     * 内容视口。
     *
     * @return 视口
     */
    Viewport contentViewport() {
        int header = this.style.getTerminalStyle().getHeader().getSrcHeight();
        return new Viewport(this.leftPos + CONTENT_PADDING + WORK_FRAME,
                this.topPos + header + WORK_GAP + WORK_FRAME,
                this.imageWidth - CONTENT_PADDING * 2 - SCROLLBAR_RESERVE - WORK_FRAME * 2,
                this.rows * rowHeight() - WORK_GAP - WORK_FRAME * 2);
    }

    /**
     * 工作区描边框外沿。
     *
     * @return 描边框
     */
    Viewport workAreaFrame() {
        Viewport viewport = contentViewport();
        return new Viewport(viewport.x() - WORK_FRAME, viewport.y() - WORK_FRAME,
                viewport.width() + WORK_FRAME * 2, viewport.height() + WORK_FRAME * 2);
    }

    private void toggleTerminalStyle(
            SettingToggleButton<appeng.api.config.TerminalStyle> button, boolean backwards) {
        appeng.api.config.TerminalStyle next = button.getNextValue(backwards);
        AEConfig.instance().setTerminalStyle(next);
        button.set(next);
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
            return "没有匹配的节点";
        }
        return "还没有机器 · 用机器绑定杖右键方块接入";
    }

    private int totalMembers() {
        int total = 0;
        for (NodeRow row : this.model.rows()) {
            total += row.members().size();
        }
        return total;
    }

    private static boolean inside(Viewport viewport, double mouseX, double mouseY) {
        return mouseX >= viewport.x() && mouseX < viewport.x() + viewport.width()
                && mouseY >= viewport.y() && mouseY < viewport.y() + viewport.height();
    }

    // ---- 探针与包内访问 ----

    NodeLayout layout() {
        return this.layout;
    }

    NodeModel model() {
        return this.model;
    }

    int scroll() {
        return this.scroll;
    }

    int rows() {
        return this.rows;
    }
}
