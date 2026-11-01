package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;

import io.github.crafteve.idlefactory.client.screen.FactoryLabels;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 被动 IO 面板的状态与输入：机器列表、选中机器的规则编辑，以及搜索与排序。
 *
 * <p>机器与规则数据全部来自 {@link FactoryTableS2C}，面板不探世界；每一次增删规则都
 * 发 {@link FactoryRuleActionC2S} 让服务端重校验并回推快照，客户端不做本地预测。</p>
 *
 * <p>搜索框是面板自己实现的：AE2 终端已经有一个搜索框占用焦点，再塞一个原生
 * {@code EditBox} 会互相抢键盘。这里用「点一下进入输入态、Esc / 回车退出」的轻量做法，
 * 输入内容仍交给 {@link FactorySearchQuery} 解析，语法与旧工作台一致。</p>
 *
 * <p>绘制与几何在 {@link FactoryIoPanelRender}：状态与输入留在本类，行高、命中区、
 * 文字截断都归渲染侧，两个文件各自不超过一屏。</p>
 */
public final class FactoryIoPanel extends FactoryTerminalPanel {
    /** 反馈停留时间（毫秒）。 */
    private static final long FEEDBACK_MILLIS = 3000L;

    /** 机器列表全量视图。 */
    List<FactorySnapshotBuilder.MachineView> machines = List.of();
    /** 搜索 + 排序后的可见机器。 */
    List<FactorySnapshotBuilder.MachineView> visible = List.of();
    /** 服务端是否已经有工厂。 */
    boolean hasFactory;
    /** 仓库是否在线。 */
    boolean storageOnline;
    /** 当前选中的机器 id。 */
    @Nullable
    UUID selectedId;
    /** 列表第一条可见机器的下标。 */
    int firstRow;
    /** 展开区的像素滚动。 */
    int detailScroll;
    /** 搜索原文。 */
    String searchText = "";
    /** 解析后的搜索条件。 */
    FactorySearchQuery query = FactorySearchQuery.EMPTY;
    /** 排序键。 */
    FactorySort.MachineKey sortKey = loadSortKey();
    /** 是否降序。 */
    boolean descending = FactoryUiConfig.MACHINE_DESCENDING.get();
    /** 搜索框是否聚焦。 */
    boolean searchFocused;
    /** 最近一条编辑反馈。 */
    @Nullable
    String feedback;
    /** 反馈是否为成功。 */
    boolean feedbackOk = true;
    /** 反馈过期时刻（毫秒）。 */
    long feedbackUntil;

    /** 绘制与几何。 */
    private final FactoryIoPanelRender render = new FactoryIoPanelRender(this);
    /** JEI 命中与拖入。 */
    private final FactoryIoPanelJei jei = new FactoryIoPanelJei(this, this.render);

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
        this.render.render(graphics, mouseX, mouseY);
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
        if (this.render.inSearch(mouseX, mouseY)) {
            this.focusSearch(true);
            return true;
        }
        if (this.render.inSort(mouseX, mouseY)) {
            this.cycleSort(button == 1);
            return true;
        }
        this.focusSearch(false);
        int chevron = this.render.chevronAt(mouseX, mouseY);
        if (chevron >= 0) {
            int index = this.firstRow + chevron;
            if (index >= 0 && index < this.visible.size()) {
                UUID id = this.visible.get(index).ref().id();
                this.selectedId = id.equals(this.selectedId) ? null : id;
                this.detailScroll = 0;
            }
            return true;
        }
        int hit = this.render.detailHit(mouseX, mouseY);
        if (hit != FactoryIoPanelRender.Hit.NONE) {
            this.onDetailHit(hit);
            return true;
        }
        int row = this.render.rowAt(mouseX, mouseY);
        if (row >= 0) {
            int index = this.firstRow + row;
            if (index >= 0 && index < this.visible.size()) {
                this.selectedId = this.visible.get(index).ref().id();
                this.detailScroll = 0;
            }
        }
        return true;
    }

    /**
     * 处理展开区里的点击。
     *
     * @param hit {@link FactoryIoPanelRender.Hit} 常量或规则下标
     */
    private void onDetailHit(int hit) {
        if (hit == FactoryIoPanelRender.Hit.ADD_INPUT
                || hit == FactoryIoPanelRender.Hit.EMPTY_INPUT) {
            this.addRule(IoSide.INPUT);
        } else if (hit == FactoryIoPanelRender.Hit.ADD_OUTPUT
                || hit == FactoryIoPanelRender.Hit.EMPTY_OUTPUT) {
            this.addRule(IoSide.OUTPUT);
        } else {
            this.removeRule(hit);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaY) {
        if (deltaY == 0.0D || !this.render.inPanel(mouseX, mouseY)) {
            return true;
        }
        if (this.render.inExpandedDetail(mouseX, mouseY)) {
            FactorySnapshotBuilder.MachineView machine = this.selected();
            if (machine != null) {
                int max = this.render.detailMaxScroll(machine);
                this.detailScroll = Mth.clamp(
                        this.detailScroll - (int) Math.signum(deltaY) * 12, 0, max);
            }
            return true;
        }
        int rows = this.render.visibleRows();
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

    @Override
    public FactoryScreen.JeiTarget jeiTargetAt(double mouseX, double mouseY) {
        return this.jei.jeiTargetAt(mouseX, mouseY);
    }

    @Override
    public boolean dropJeiKey(AEKey key, double mouseX, double mouseY) {
        return this.jei.dropJeiKey(key, mouseX, mouseY);
    }

    @Override
    public List<FactoryTerminalDropArea> jeiDropAreas() {
        return this.jei.jeiDropAreas();
    }

    /** 当前选中的机器；没有选中时为 {@code null}。 */
    @Nullable
    FactorySnapshotBuilder.MachineView selected() {
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
                Math.max(0, this.visible.size() - this.render.visibleRows()));
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
        IoSide side = hit < FactoryIoPanelRender.Hit.OUTPUT_BASE ? IoSide.INPUT : IoSide.OUTPUT;
        int index = side == IoSide.INPUT
                ? hit - FactoryIoPanelRender.Hit.INPUT_BASE
                : hit - FactoryIoPanelRender.Hit.OUTPUT_BASE;
        List<ResourceSpec> rules = side == IoSide.INPUT ? machine.inputs() : machine.outputs();
        if (index < 0 || index >= rules.size()) {
            return;
        }
        PacketDistributor.sendToServer(new FactoryRuleActionC2S(this.containerId,
                FactoryRuleActionC2S.Action.REMOVE, machine.ref().id(), side,
                rules.get(index).key(), null, MatchMode.IGNORE_COMPONENTS));
    }
}
