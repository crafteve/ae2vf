package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;

import io.github.crafteve.idlefactory.client.screen.FactoryLabels;
import io.github.crafteve.idlefactory.client.screen.FactoryNineSlice;
import io.github.crafteve.idlefactory.client.screen.FactoryScreen;
import io.github.crafteve.idlefactory.client.screen.FactorySearchQuery;
import io.github.crafteve.idlefactory.client.screen.FactorySort;
import io.github.crafteve.idlefactory.client.screen.FactoryStatus;
import io.github.crafteve.idlefactory.client.screen.FactoryUiConfig;
import io.github.crafteve.idlefactory.client.screen.FactoryUiText;
import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.factory.io.IoSide;
import io.github.crafteve.idlefactory.factory.machine.MatchMode;
import io.github.crafteve.idlefactory.factory.machine.ResourceSpec;
import io.github.crafteve.idlefactory.integration.jecharacters.JecharactersCompat;
import io.github.crafteve.idlefactory.network.payload.FactoryEditFeedbackS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryRuleActionC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryTableRequestC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryTableS2C;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 被动 IO 面板：机器列表、选中机器的规则编辑，以及搜索与排序。
 *
 * <p>机器与规则数据全部来自 {@link FactoryTableS2C}，面板不探世界；每一次增删规则都
 * 发 {@link FactoryRuleActionC2S} 让服务端重校验并回推快照，客户端不做本地预测。</p>
 *
 * <p>搜索框是面板自己实现的：AE2 终端已经有一个搜索框占用焦点，再塞一个原生
 * {@code EditBox} 会互相抢键盘。这里用「点一下进入输入态、Esc / 回车退出」的轻量做法，
 * 输入内容仍交给 {@link FactorySearchQuery} 解析，语法与旧工作台一致。</p>
 */
public final class FactoryIoPanel extends FactoryTerminalPanel {
    /** 排序按钮宽度。 */
    private static final int SORT_WIDTH = 38;
    /** 机器行高。 */
    private static final int MACHINE_ROW = 16;
    /** 规则行高。 */
    private static final int RULE_ROW = 16;
    /** 规则区标题高度。 */
    private static final int SECTION_HEADER = 11;
    /** 移除按钮命中宽度。 */
    private static final int REMOVE_WIDTH = 10;
    /** 反馈停留时间（毫秒）。 */
    private static final long FEEDBACK_MILLIS = 3000L;

    private List<FactorySnapshotBuilder.MachineView> machines = List.of();
    private List<FactorySnapshotBuilder.MachineView> visible = List.of();
    private boolean hasFactory;
    private boolean storageOnline;
    @Nullable
    private UUID selectedId;
    private int firstRow;
    private String searchText = "";
    private FactorySearchQuery query = FactorySearchQuery.EMPTY;
    private FactorySort.MachineKey sortKey = loadSortKey();
    private boolean descending = FactoryUiConfig.MACHINE_DESCENDING.get();
    private boolean searchFocused;
    @Nullable
    private String feedback;
    private boolean feedbackOk = true;
    private long feedbackUntil;

    /**
     * @param containerId 所属菜单容器 id
     */
    public FactoryIoPanel(int containerId) {
        super(containerId, "样板");
    }

    @Override
    protected String title() {
        return "被动 IO";
    }

    @Override
    protected String subtitle() {
        return "选择机器后编辑输入 / 输出规则";
    }

    @Override
    protected void requestSnapshot() {
        PacketDistributor.sendToServer(new FactoryTableRequestC2S(this.containerId));
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderBackground(graphics, mouseX, mouseY);
        this.renderSearch(graphics);
        this.renderMachineList(graphics, mouseX, mouseY);
        this.renderRules(graphics, mouseX, mouseY);
        this.renderFeedback(graphics);
    }

    @Override
    public void acceptTable(FactoryTableS2C payload) {
        this.hasFactory = payload.hasFactory();
        this.storageOnline = payload.storageOnline();
        this.machines = payload.machines();
        if (this.selectedId != null && this.selected() == null) {
            this.selectedId = null;
        }
        this.rebuild();
    }

    @Override
    public void acceptEditFeedback(FactoryEditFeedbackS2C payload) {
        this.feedback = payload.message();
        this.feedbackOk = payload.ok();
        this.feedbackUntil = Util.getMillis() + FEEDBACK_MILLIS;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (this.inSearch(mouseX, mouseY)) {
            this.focusSearch(true);
            return true;
        }
        if (this.inSort(mouseX, mouseY)) {
            this.cycleSort(button == 1);
            return true;
        }
        if (this.inMachineList(mouseX, mouseY)) {
            this.focusSearch(false);
            int row = this.machineRowAt(mouseY);
            List<FactorySnapshotBuilder.MachineView> rows = this.visible;
            int index = this.firstRow + row;
            if (index >= 0 && index < rows.size()) {
                this.selectedId = rows.get(index).ref().id();
            }
            return true;
        }
        this.focusSearch(false);
        int rule = this.ruleHit(mouseX, mouseY);
        if (rule == RuleHit.NONE) {
            return true;
        }
        if (rule == RuleHit.ADD_INPUT) {
            this.addRule(IoSide.INPUT);
        } else if (rule == RuleHit.ADD_OUTPUT) {
            this.addRule(IoSide.OUTPUT);
        } else {
            this.removeRule(rule);
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaY) {
        if (!this.inMachineList(mouseX, mouseY) || deltaY == 0.0D) {
            return true;
        }
        int rows = this.visibleRows();
        int max = Math.max(0, this.visible.size() - rows);
        this.firstRow = Mth.clamp(this.firstRow - (int) Math.signum(deltaY), 0, max);
        return true;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (!this.searchFocused || codePoint < 32) {
            return false;
        }
        this.searchText = this.searchText + codePoint;
        this.applySearch();
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!this.searchFocused) {
            return false;
        }
        if (keyCode == 256 || keyCode == 257) {
            this.focusSearch(false);
            return true;
        }
        if (keyCode == 259 && !this.searchText.isEmpty()) {
            int end = this.searchText.offsetByCodePoints(this.searchText.length(), -1);
            this.searchText = this.searchText.substring(0, end);
            this.applySearch();
        }
        return true;
    }

    /** 当前选中的机器；没有选中时为 {@code null}。 */
    @Nullable
    private FactorySnapshotBuilder.MachineView selected() {
        if (this.selectedId == null) {
            return null;
        }
        for (FactorySnapshotBuilder.MachineView machine : this.machines) {
            if (machine.ref().id().equals(this.selectedId)) {
                return machine;
            }
        }
        return null;
    }

    /**
     * 按搜索与排序重建可见列表，并把滚动夹回合法范围。
     */
    private void rebuild() {
        List<FactorySnapshotBuilder.MachineView> filtered = new ArrayList<>();
        for (FactorySnapshotBuilder.MachineView machine : this.machines) {
            if (this.query.matches(searchFields(machine))) {
                filtered.add(machine);
            }
        }
        this.visible = FactorySort.machines(filtered, this.sortKey, this.descending);
        this.firstRow = Mth.clamp(this.firstRow, 0,
                Math.max(0, this.visible.size() - this.visibleRows()));
    }

    /**
     * 机器搜索字段：名字、方块注册名、模组名、状态与坐标。
     *
     * @param machine 机器视图
     * @return 搜索字段
     */
    private FactorySearchQuery.Fields searchFields(FactorySnapshotBuilder.MachineView machine) {
        ResourceLocation blockId = machine.blockId();
        String id = blockId == null ? "" : blockId.toString();
        String namespace = blockId == null ? "" : blockId.getNamespace();
        String status = FactoryStatus.text(machine, this.theme);
        String tooltip = status + "\n" + machine.ref().dimension().location() + " "
                + machine.ref().pos().toShortString() + "\n规则 " + machine.ruleCount() + " 条";
        return new FactorySearchQuery.Fields(FactoryLabels.blockName(blockId), id, namespace,
                namespace.isEmpty() ? "" : FactoryUiText.modName(namespace), tooltip, List.of());
    }

    /**
     * 更新搜索条件。
     */
    private void applySearch() {
        this.query = FactorySearchQuery.parse(this.searchText, JecharactersCompat::contains);
        this.rebuild();
    }

    /**
     * 切换搜索焦点。
     *
     * @param focused 是否聚焦
     */
    private void focusSearch(boolean focused) {
        if (focused && !this.searchFocused) {
            this.takeFocus();
        }
        this.searchFocused = focused;
    }

    /**
     * 循环切换排序键。
     *
     * @param toggleDirection 是否只翻转方向
     */
    private void cycleSort(boolean toggleDirection) {
        if (toggleDirection) {
            this.descending = !this.descending;
        } else {
            this.sortKey = switch (this.sortKey) {
                case NAME -> FactorySort.MachineKey.STATUS;
                case STATUS -> FactorySort.MachineKey.RULES;
                default -> FactorySort.MachineKey.NAME;
            };
        }
        FactoryUiConfig.MACHINE_KEY.set(this.sortKey.name());
        FactoryUiConfig.MACHINE_DESCENDING.set(this.descending);
        this.rebuild();
    }

    /**
     * 从客户端配置读排序键；非法值退回名称排序。
     *
     * @return 排序键
     */
    private static FactorySort.MachineKey loadSortKey() {
        String raw = FactoryUiConfig.MACHINE_KEY.get();
        try {
            return FactorySort.MachineKey.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return FactorySort.MachineKey.NAME;
        }
    }

    /**
     * 把光标上的物品加成一条规则。
     *
     * @param side 输入 / 输出
     */
    private void addRule(IoSide side) {
        FactorySnapshotBuilder.MachineView machine = this.selected();
        if (machine == null || this.minecraft.player == null) {
            return;
        }
        ItemStack carried = this.minecraft.player.containerMenu.getCarried();
        AEItemKey key = AEItemKey.of(carried);
        if (key == null) {
            this.feedback = "光标上没有物品";
            this.feedbackOk = false;
            this.feedbackUntil = Util.getMillis() + FEEDBACK_MILLIS;
            return;
        }
        PacketDistributor.sendToServer(new FactoryRuleActionC2S(this.containerId,
                FactoryRuleActionC2S.Action.ADD, machine.ref().id(), side, key, null,
                MatchMode.IGNORE_COMPONENTS));
    }

    /**
     * 删除一条规则。
     *
     * @param hit 命中的规则槽
     */
    private void removeRule(int hit) {
        FactorySnapshotBuilder.MachineView machine = this.selected();
        if (machine == null) {
            return;
        }
        IoSide side = hit < RuleHit.OUTPUT_BASE ? IoSide.INPUT : IoSide.OUTPUT;
        int index = hit % RuleHit.OUTPUT_BASE;
        List<ResourceSpec> rules = side == IoSide.INPUT ? machine.inputs() : machine.outputs();
        if (index < 0 || index >= rules.size()) {
            return;
        }
        PacketDistributor.sendToServer(new FactoryRuleActionC2S(this.containerId,
                FactoryRuleActionC2S.Action.REMOVE, machine.ref().id(), side,
                rules.get(index).key(), null, MatchMode.IGNORE_COMPONENTS));
    }

    private void renderSearch(GuiGraphics graphics) {
        int x = this.contentLeft();
        int y = this.contentTop();
        int width = this.searchWidth();
        FactoryNineSlice.field(graphics, x, y, width, FactoryTerminalGeometry.SEARCH_HEIGHT);
        String text = this.searchText.isEmpty() && !this.searchFocused ? "搜索机器" : this.searchText;
        int color = this.searchText.isEmpty() && !this.searchFocused
                ? this.theme.textDim() : this.theme.textPrimary();
        graphics.drawString(this.font, text, x + 3, y + 3, color, false);
        if (this.searchFocused && (Util.getMillis() / 500L) % 2L == 0L) {
            int caretX = x + 3 + this.font.width(this.searchText);
            graphics.fill(caretX, y + 2, caretX + 1, y + 10, this.theme.textPrimary());
        }
        int sortX = x + width + 4;
        String label = sortLabel();
        FactoryNineSlice.sortButton(graphics, sortX, y, SORT_WIDTH,
                FactoryTerminalGeometry.SEARCH_HEIGHT);
        graphics.drawString(this.font, label,
                sortX + (SORT_WIDTH - this.font.width(label)) / 2, y + 3,
                this.theme.textPrimary(), false);
    }

    private void renderMachineList(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = this.contentLeft();
        int top = this.machinesTop();
        int bottom = this.machinesBottom();
        if (!this.hasFactory) {
            graphics.drawString(this.font, "还没有工厂", x + 2, top + 2, this.theme.textDim(), false);
            return;
        }
        if (this.visible.isEmpty()) {
            graphics.drawString(this.font, this.machines.isEmpty() ? "机器库为空" : "没有匹配的机器",
                    x + 2, top + 2, this.theme.textDim(), false);
            return;
        }
        graphics.enableScissor(x, top, x + this.contentWidth(), bottom);
        int rows = this.visibleRows();
        for (int row = 0; row < rows; row++) {
            int index = this.firstRow + row;
            if (index >= this.visible.size()) {
                break;
            }
            FactorySnapshotBuilder.MachineView machine = this.visible.get(index);
            int y = top + row * MACHINE_ROW;
            boolean hovered = mouseX >= x && mouseX < x + this.contentWidth()
                    && mouseY >= y && mouseY < y + MACHINE_ROW;
            boolean selected = machine.ref().id().equals(this.selectedId);
            if (selected) {
                graphics.fill(x, y, x + this.contentWidth(), y + MACHINE_ROW,
                        FactoryTerminalGeometry.ROW_SELECTED);
            } else if (hovered) {
                graphics.fill(x, y, x + this.contentWidth(), y + MACHINE_ROW,
                        FactoryTerminalGeometry.ROW_HOVER);
            }
            ItemStack icon = FactoryLabels.blockStack(machine.blockId());
            if (!icon.isEmpty()) {
                graphics.renderItem(icon, x, y);
            }
            String name = FactoryLabels.blockName(machine.blockId());
            int statusWidth = 34;
            String status = FactoryStatus.text(machine, this.theme);
            int nameWidth = this.contentWidth() - 20 - statusWidth - 2;
            graphics.drawString(this.font, trim(name, nameWidth), x + 18, y + 4,
                    this.theme.textPrimary(), false);
            graphics.drawString(this.font, trim(status, statusWidth),
                    x + this.contentWidth() - this.font.width(trim(status, statusWidth)),
                    y + 4, this.theme.textDim(), false);
        }
        graphics.disableScissor();
    }

    private void renderRules(GuiGraphics graphics, int mouseX, int mouseY) {
        FactorySnapshotBuilder.MachineView machine = this.selected();
        if (machine == null) {
            return;
        }
        int x = this.contentLeft();
        int width = this.contentWidth();
        int top = this.rulesTop();
        int sectionHeight = (this.machinesBottom() - top) / 2;
        this.renderSection(graphics, mouseX, mouseY, x, top, width, sectionHeight,
                IoSide.INPUT, machine.inputs());
        this.renderSection(graphics, mouseX, mouseY, x, top + sectionHeight, width, sectionHeight,
                IoSide.OUTPUT, machine.outputs());
    }

    private void renderSection(GuiGraphics graphics, int mouseX, int mouseY, int x, int y,
            int width, int height, IoSide side, List<ResourceSpec> rules) {
        FactoryNineSlice.ioHeader(graphics, x, y, width, SECTION_HEADER);
        graphics.drawString(this.font, side == IoSide.INPUT ? "输入" : "输出", x + 2, y + 2,
                this.theme.textPrimary(), false);
        boolean addHovered = mouseX >= x + width - 12 && mouseX < x + width
                && mouseY >= y && mouseY < y + SECTION_HEADER;
        FactoryNineSlice.button(graphics, x + width - 12, y, 12, SECTION_HEADER);
        graphics.drawString(this.font, "+", x + width - 9, y + 2,
                addHovered ? this.theme.accent() : this.theme.textPrimary(), false);
        int maxRows = Math.max(0, (height - SECTION_HEADER) / RULE_ROW);
        for (int i = 0; i < Math.min(maxRows, rules.size()); i++) {
            ResourceSpec rule = rules.get(i);
            int rowY = y + SECTION_HEADER + i * RULE_ROW;
            boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= rowY
                    && mouseY < rowY + RULE_ROW;
            if (hovered) {
                graphics.fill(x, rowY, x + width, rowY + RULE_ROW,
                        FactoryTerminalGeometry.ROW_HOVER);
            }
            io.github.crafteve.idlefactory.client.screen.FactoryResourceIconRender.draw(
                    graphics, this.font, rule.key(), x + 1, rowY);
            String label = FactoryLabels.name(rule.key());
            int removeX = x + width - REMOVE_WIDTH;
            int labelWidth = removeX - (x + 19) - 16;
            graphics.drawString(this.font, trim(label, labelWidth), x + 19, rowY + 4,
                    this.theme.textPrimary(), false);
            String face = FactoryLabels.face(rule);
            graphics.drawString(this.font, face, removeX - 2 - this.font.width(face), rowY + 4,
                    this.theme.textFace(), false);
            graphics.drawString(this.font, "×", removeX + 1, rowY + 4, this.theme.error(), false);
        }
        if (rules.size() > maxRows) {
            graphics.drawString(this.font, "+" + (rules.size() - maxRows),
                    x + 2, y + height - 10, this.theme.textDim(), false);
        }
    }

    private void renderFeedback(GuiGraphics graphics) {
        if (this.feedback == null || Util.getMillis() > this.feedbackUntil) {
            return;
        }
        int color = this.feedbackOk ? this.theme.statusRun() : this.theme.error();
        graphics.drawString(this.font, trim(this.feedback, this.contentWidth() - 4),
                this.contentLeft() + 2, this.bottom() - 12, color, false);
    }

    @Override
    public FactoryScreen.JeiTarget jeiTargetAt(double mouseX, double mouseY) {
        int x = this.contentLeft();
        if (this.inMachineList(mouseX, mouseY)) {
            int row = this.machineRowAt(mouseY);
            int index = this.firstRow + row;
            if (index >= 0 && index < this.visible.size()
                    && mouseX < x + FactoryTerminalGeometry.ICON_SIZE) {
                return this.itemTarget(
                        FactoryLabels.blockStack(this.visible.get(index).blockId()),
                        x, this.machinesTop() + row * MACHINE_ROW);
            }
        }
        FactorySnapshotBuilder.MachineView machine = this.selected();
        if (machine == null) {
            return null;
        }
        int top = this.rulesTop();
        int sectionHeight = (this.machinesBottom() - top) / 2;
        FactoryScreen.JeiTarget input = this.ruleTargetAt(machine.inputs(), x, top,
                sectionHeight, mouseX, mouseY);
        if (input != null) {
            return input;
        }
        return this.ruleTargetAt(machine.outputs(), x, top + sectionHeight, sectionHeight,
                mouseX, mouseY);
    }

    @Override
    public boolean dropJeiKey(AEKey key, double mouseX, double mouseY) {
        FactorySnapshotBuilder.MachineView machine = this.selected();
        if (machine == null) {
            return false;
        }
        for (FactoryTerminalDropArea area : this.jeiDropAreas()) {
            if (mouseX >= area.area().getX() && mouseX < area.area().getX() + area.area().getWidth()
                    && mouseY >= area.area().getY()
                    && mouseY < area.area().getY() + area.area().getHeight()) {
                PacketDistributor.sendToServer(new FactoryRuleActionC2S(this.containerId,
                        FactoryRuleActionC2S.Action.ADD, machine.ref().id(), area.side(), key, null,
                        MatchMode.IGNORE_COMPONENTS));
                return true;
            }
        }
        return false;
    }

    @Override
    public List<FactoryTerminalDropArea> jeiDropAreas() {
        if (this.selected() == null) {
            return List.of();
        }
        int x = this.contentLeft();
        int width = this.contentWidth();
        int top = this.rulesTop();
        int sectionHeight = (this.machinesBottom() - top) / 2;
        return List.of(
                new FactoryTerminalDropArea(new Rect2i(x, top, width, sectionHeight),
                        IoSide.INPUT),
                new FactoryTerminalDropArea(
                        new Rect2i(x, top + sectionHeight, width, sectionHeight), IoSide.OUTPUT));
    }

    /**
     * 规则图标命中：只认 16×16 图标区域。
     *
     * @param rules         规则列表
     * @param x             区域左边界
     * @param y             区域上边界
     * @param sectionHeight 区域高度
     * @param mouseX        鼠标 x
     * @param mouseY        鼠标 y
     * @return 目标；没命中时为 {@code null}
     */
    @Nullable
    private FactoryScreen.JeiTarget ruleTargetAt(List<ResourceSpec> rules, int x, int y,
            int sectionHeight, double mouseX, double mouseY) {
        int maxRows = Math.max(0, (sectionHeight - SECTION_HEADER) / RULE_ROW);
        int limit = Math.min(maxRows, rules.size());
        for (int i = 0; i < limit; i++) {
            int rowY = y + SECTION_HEADER + i * RULE_ROW;
            if (mouseX >= x + 1 && mouseX < x + 1 + FactoryTerminalGeometry.ICON_SIZE
                    && mouseY >= rowY && mouseY < rowY + FactoryTerminalGeometry.ICON_SIZE) {
                return this.resourceTarget(rules.get(i).key(), x + 1, rowY);
            }
        }
        return null;
    }

    private int contentLeft() {
        return this.left() + FactoryTerminalGeometry.CONTENT_PADDING;
    }

    private int contentTop() {
        return this.top() + FactoryTerminalGeometry.HEADER_HEIGHT + 15;
    }

    private int contentWidth() {
        return FactoryTerminalGeometry.contentWidth();
    }

    private int searchWidth() {
        return this.contentWidth() - SORT_WIDTH - 4;
    }

    private int machinesTop() {
        return this.contentTop() + FactoryTerminalGeometry.SEARCH_HEIGHT + 4;
    }

    private int machinesBottom() {
        if (this.selected() == null) {
            return this.bottom() - FactoryTerminalGeometry.CONTENT_PADDING;
        }
        return this.bottom() - FactoryTerminalGeometry.CONTENT_PADDING - this.rulesHeight();
    }

    private int rulesTop() {
        return this.machinesBottom() + 2;
    }

    private int rulesHeight() {
        return Math.min(130, Math.max(74, this.height() / 3));
    }

    private int visibleRows() {
        return Math.max(1, (this.machinesBottom() - this.machinesTop()) / MACHINE_ROW);
    }

    private int machineRowAt(double mouseY) {
        return (int) ((mouseY - this.machinesTop()) / MACHINE_ROW);
    }

    private boolean inSearch(double mouseX, double mouseY) {
        int x = this.contentLeft();
        int y = this.contentTop();
        return mouseX >= x && mouseX < x + this.searchWidth()
                && mouseY >= y && mouseY < y + FactoryTerminalGeometry.SEARCH_HEIGHT;
    }

    private boolean inSort(double mouseX, double mouseY) {
        int x = this.contentLeft() + this.searchWidth() + 4;
        int y = this.contentTop();
        return mouseX >= x && mouseX < x + SORT_WIDTH
                && mouseY >= y && mouseY < y + FactoryTerminalGeometry.SEARCH_HEIGHT;
    }

    private boolean inMachineList(double mouseX, double mouseY) {
        return mouseX >= this.contentLeft()
                && mouseX < this.contentLeft() + this.contentWidth()
                && mouseY >= this.machinesTop() && mouseY < this.machinesBottom();
    }

    /**
     * 命中规则区：返回 {@link #RuleHit} 的常量或规则下标编码。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @return 命中结果
     */
    private int ruleHit(double mouseX, double mouseY) {
        FactorySnapshotBuilder.MachineView machine = this.selected();
        if (machine == null) {
            return RuleHit.NONE;
        }
        int x = this.contentLeft();
        int width = this.contentWidth();
        int top = this.rulesTop();
        int sectionHeight = (this.machinesBottom() - top) / 2;
        int hit = this.sectionHit(mouseX, mouseY, x, top, width, sectionHeight,
                IoSide.INPUT, machine.inputs(), RuleHit.INPUT_BASE);
        if (hit != RuleHit.NONE) {
            return hit;
        }
        return this.sectionHit(mouseX, mouseY, x, top + sectionHeight, width, sectionHeight,
                IoSide.OUTPUT, machine.outputs(), RuleHit.OUTPUT_BASE);
    }

    private int sectionHit(double mouseX, double mouseY, int x, int y, int width, int height,
            IoSide side, List<ResourceSpec> rules, int base) {
        if (mouseY < y || mouseY >= y + height) {
            return RuleHit.NONE;
        }
        if (mouseY < y + SECTION_HEADER) {
            if (mouseX >= x + width - 12 && mouseX < x + width) {
                return side == IoSide.INPUT ? RuleHit.ADD_INPUT : RuleHit.ADD_OUTPUT;
            }
            return RuleHit.NONE;
        }
        int index = (int) ((mouseY - y - SECTION_HEADER) / RULE_ROW);
        if (index < 0 || index >= rules.size()) {
            return RuleHit.NONE;
        }
        if (mouseX >= x + width - REMOVE_WIDTH && mouseX < x + width) {
            return base + index;
        }
        return RuleHit.NONE;
    }

    private String sortLabel() {
        String key = switch (this.sortKey) {
            case STATUS -> "状态";
            case RULES -> "规则";
            default -> "名称";
        };
        return key + (this.descending ? "↓" : "↑");
    }

    /**
     * 按像素宽度截断文字，末尾加省略号。
     *
     * @param text  原文
     * @param width 最大宽度
     * @return 截断后的文字
     */
    private String trim(String text, int width) {
        if (width <= 0) {
            return "";
        }
        if (this.font.width(text) <= width) {
            return text;
        }
        String ellipsis = "…";
        int budget = width - this.font.width(ellipsis);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (this.font.width(builder.toString() + c) > budget) {
                break;
            }
            builder.append(c);
        }
        return builder + ellipsis;
    }

    /** 规则区命中编码。 */
    private static final class RuleHit {
        /** 没命中。 */
        private static final int NONE = -1;
        /** 输入区加号。 */
        private static final int ADD_INPUT = -2;
        /** 输出区加号。 */
        private static final int ADD_OUTPUT = -3;
        /** 输入规则下标偏移。 */
        private static final int INPUT_BASE = 0;
        /** 输出规则下标偏移；与输入错开，避免同一 block 被当成两侧下标。 */
        private static final int OUTPUT_BASE = 1000;

        private RuleHit() {
            // 纯常量持有类
        }
    }
}
