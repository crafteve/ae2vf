package io.github.crafteve.idlefactory.client.screen.terminal.node;

import io.github.crafteve.idlefactory.client.screen.paint.StatusColor;

import io.github.crafteve.idlefactory.client.wand.WandDraftState;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.client.screen.paint.Card;
import io.github.crafteve.idlefactory.client.screen.paint.KeycapButton;
import io.github.crafteve.idlefactory.client.screen.UiResources;
import io.github.crafteve.idlefactory.client.screen.text.TerminalText;
import io.github.crafteve.idlefactory.client.screen.NineSliceRenderer;
import io.github.crafteve.idlefactory.client.screen.paint.StatusView;
import io.github.crafteve.idlefactory.client.screen.paint.StatusBar;
import io.github.crafteve.idlefactory.client.screen.terminal.node.NodeLayout.Hit;
import io.github.crafteve.idlefactory.client.screen.terminal.node.NodeLayout.Kind;
import io.github.crafteve.idlefactory.client.screen.terminal.node.NodeLayout.NodeBox;
import io.github.crafteve.idlefactory.client.screen.terminal.node.NodeLayout.Viewport;
import io.github.crafteve.idlefactory.io.DispatchMode;
import io.github.crafteve.idlefactory.node.Multiplier;
import io.github.crafteve.idlefactory.observation.NodeHealth;
import io.github.crafteve.idlefactory.view.ViewMachine;
import io.github.crafteve.idlefactory.view.ViewNode;
import io.github.crafteve.idlefactory.view.ViewNodeCore;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 节点管理内容区的绘制。
 *
 * <p>只读模型与排版，不写状态：悬停由屏幕算好 {@link Hit} 传进来。节点行复用被动 IO 的
 * {@link Card} 层级、{@link KeycapButton} 键帽与 {@link StatusBar} 状态灯，不另造
 * 一套卡片外观。</p>
 */
final class NodeRender {
    /** 悬停底色（白色 12%）。 */
    private static final int HOVER_FILL = 0x1FFFFFFF;
    /** 待确认删除的键帽底色。 */
    /** 模式键闲置底：冷灰。 */
    private static final int MODE_IDLE = 0xFFE3E5EE;
    /** 倍率关闭：纯白键。 */
    private static final int MULT_OFF = MODE_IDLE;
    /** 倍率固定档：浅蓝 + 深蓝字。 */
    private static final int MULT_BLUE = 0xFFA8C4E4;
    private static final int MULT_BLUE_TEXT = 0xFF12263A;
    /** 倍率智能：浅青 + 黑字。 */
    private static final int MULT_SMART = 0xFF2F5C9E;
    /** 派单逐台：淡紫；轮询：淡黄。 */
    private static final int DISPATCH_SEQUENTIAL = 0xFFB7A6D8;
    private static final int DISPATCH_SEQUENTIAL_TEXT = 0xFF221933;
    private static final int DISPATCH_ROUND = 0xFFDCC163;
    private static final int DISPATCH_ROUND_TEXT = 0xFF33280A;
    /** 阻挡开启：淡绿 + 深绿字。 */
    private static final int BLOCK_GREEN = 0xFFB7D8B0;
    private static final int BLOCK_GREEN_TEXT = 0xFF14300F;
    /** 动作键：编辑冷灰 + 黑字；删除灰红 + 白字。 */
    private static final int EDIT_KEY = 0xFFDCDEE6;
    private static final int DELETE_KEY = 0xFFC0433F;
    private static final int DELETE_ARMED = 0xFFFF5A5A;

    /** 正文色。 */
    private static final int TEXT_PRIMARY = 0xFF000000;
    /** 次要文字。 */
    private static final int TEXT_DIM = 0xFF5F5F5F;
    /** 键帽亮边。 */
    private static final int BEVEL_LIGHT = 0xFFD8D9DF;
    /** 机器行深色带。 */
    private static final int IO_ROW = 0xFFA9AAB8;

    private final Font font;

    NodeRender(Font font) {
        this.font = font;
    }

    /**
     * 绘制一张节点卡所需的全部数据：与快照类型解耦。
     *
     * <p>旧的 {@link NodeRow} 与视图表的 {@link ViewNode} 都映射到它，绘制代码只认它。
     * 第 3c 步删掉旧入口后，这份结构就是唯一的绘制输入。</p>
     *
     * @param nodeId       节点 id，编辑态与删除确认都按它比对
     * @param name         显示名
     * @param status       状态展示（文字、颜色、贴图）
     * @param hint         名字后面的状态小字；空串表示不放
     * @param present      图标是节点方块还是屏障
     * @param typeBlockId  类型方块 id，图标用
     * @param available    provider 是否可用
     * @param blockingOn   阻挡是否打开
     * @param multiplier   倍率
     * @param dispatch     派单模式
     * @param machineCount 机器台数，>1 时画数量角标
     * @param members      展开后的成员行
     */
    private record CardData(java.util.UUID nodeId, String name, StatusView status, String hint,
            boolean present, net.minecraft.resources.ResourceLocation typeBlockId,
            boolean available, boolean blockingOn, Multiplier multiplier, DispatchMode dispatch,
            int machineCount, List<MemberCardData> members) {

        private CardData {
            members = List.copyOf(members);
        }
    }

    /**
     * 成员行的绘制数据。
     *
     * @param label   行标签
     * @param blockId 观测到的方块 id，图标用
     * @param status  状态展示，取灯贴图
     */
    private record MemberCardData(String label, @Nullable net.minecraft.resources.ResourceLocation blockId,
            StatusView status) {
    }

    /**
     * 画整个内容区。
     *
     * <p>节点结论、规则与机器都已在服务端算好，这里只把状态映射成颜色与贴图。</p>
     */
    void draw(GuiGraphics graphics, NodeLayout layout, List<ViewNode> nodes, int scroll,
            @Nullable Hit hover, @Nullable java.util.UUID pendingDelete, String emptyText) {
        List<CardData> cards = new ArrayList<>(nodes.size());
        for (ViewNode node : nodes) {
            cards.add(cardOf(node));
        }
        drawCards(graphics, layout, cards, scroll, hover, pendingDelete, emptyText);
    }

    private void drawCards(GuiGraphics graphics, NodeLayout layout, List<CardData> rows,
            int scroll, @Nullable Hit hover, @Nullable java.util.UUID pendingDelete,
            String emptyText) {
        Viewport viewport = layout.viewport();
        if (rows.isEmpty()) {
            graphics.drawString(this.font, emptyText,
                    viewport.x() + (viewport.width() - this.font.width(emptyText)) / 2,
                    viewport.y() + 12, TEXT_DIM, false);
            return;
        }
        for (NodeBox box : layout.boxes()) {
            CardData row = rows.get(box.index());
            int top = NodeLayout.screenY(viewport, box.top(), scroll);
            int bottom = NodeLayout.screenY(viewport, box.bottom(), scroll);
            if (bottom <= viewport.y() || top >= viewport.y() + viewport.height()) {
                continue;
            }
            Card.draw(graphics, NodeLayout.cardX(viewport), top,
                    NodeLayout.cardWidth(viewport), box.bottom() - box.top(),
                    Card.Level.MACHINE, true);
            if (hover != null && hover.node() == box.index()
                    && (hover.kind() == Kind.NODE || hover.kind() == Kind.NAME)) {
                // 卡片悬停铺满整张卡，但压在内容下面；按钮悬停只亮按钮自己。
                graphics.fill(NodeLayout.cardX(viewport) + 1, top + 1,
                        NodeLayout.cardRight(viewport) - 1, bottom - 1, HOVER_FILL);
            }
            drawNodeRow(graphics, viewport, box, row, top, hover, pendingDelete);
            if (box.expanded()) {
                drawMembers(graphics, viewport, box, row, scroll, hover);
            }
        }
    }

    private void drawNodeRow(GuiGraphics graphics, Viewport viewport, NodeBox box, CardData row,
            int top, @Nullable Hit hover, @Nullable java.util.UUID pendingDelete) {
        int left = NodeLayout.cardX(viewport) + 1;
        int right = NodeLayout.cardRight(viewport) - 1;
        int bandTop = top + 1;
        int bandBottom = top + NodeLayout.NODE_ROW_H - 1;
        graphics.fill(left, bandTop, right, bandBottom, IO_ROW);
        StatusBar.draw(graphics, left, bandTop, bandBottom - bandTop, row.status().color());

        int slotX = NodeLayout.nameX(viewport) - NodeLayout.SLOT - 1;
        NineSliceRenderer.slot(graphics, slotX, top + 1, NodeLayout.SLOT,
                NodeLayout.SLOT);
        ItemStack icon = row.present()
                ? UiResources.blockStack(row.typeBlockId()) : new ItemStack(Items.BARRIER);
        graphics.renderItem(icon.isEmpty() ? new ItemStack(Items.BARRIER) : icon,
                slotX + 1, top + 2);
        if (row.machineCount() > 1) {
            // 用原版物品数量修饰器：右下角白字黑描边，和物品同一渲染批次，不会被图标盖住。
            graphics.renderItemDecorations(this.font,
                    icon.copyWithCount(row.machineCount()), slotX + 1, top + 2);
        }

        String name = ellipsize(row.name(), NodeLayout.nameWidth(viewport));
        graphics.drawString(this.font, name, NodeLayout.nameX(viewport), top + 6,
                TEXT_PRIMARY, false);
        drawStatusHints(graphics, viewport, row, top);

        boolean provider = row.available();
        int keyY = top + NodeLayout.KEY_INSET_Y;
        int keyTextY = keyY + (NodeLayout.KEY_H - this.font.lineHeight) / 2 + 1;

        KeycapButton.draw(graphics, NodeLayout.keyX(viewport, 0), keyY,
                NodeLayout.KEY_W, NodeLayout.KEY_H,
                provider && row.blockingOn() ? BLOCK_GREEN : MODE_IDLE,
                provider, hovered(hover, box.index(), Kind.BLOCKING));
        drawLabel(graphics, tr("idlefactory.node.label.blocking"), NodeLayout.keyX(viewport, 0), NodeLayout.KEY_W,
                keyTextY, provider && row.blockingOn() ? BLOCK_GREEN_TEXT : TEXT_DIM);

        Multiplier multiplier = row.multiplier();
        boolean multiplierActive = multiplier.mode()
                != io.github.crafteve.idlefactory.node.MultiplierMode.OFF;
        boolean smart = multiplier.mode()
                == io.github.crafteve.idlefactory.node.MultiplierMode.SMART;
        int multiplierBg = !multiplierActive ? MULT_OFF
                : smart ? MULT_SMART : MULT_BLUE;
        int multiplierText = smart ? 0xFFFFFFFF
                : multiplierActive ? MULT_BLUE_TEXT : TEXT_PRIMARY;
        KeycapButton.draw(graphics, NodeLayout.keyX(viewport, 1), keyY,
                NodeLayout.KEY_W, NodeLayout.KEY_H, multiplierBg,
                provider, hovered(hover, box.index(), Kind.MULTIPLIER));
        drawLabel(graphics, multiplierLabel(multiplier), NodeLayout.keyX(viewport, 1),
                NodeLayout.KEY_W, keyTextY, multiplierText);

        boolean roundRobin = row.dispatch() == DispatchMode.ROUND_ROBIN;
        KeycapButton.draw(graphics, NodeLayout.keyX(viewport, 2), keyY,
                NodeLayout.KEY_W, NodeLayout.KEY_H,
                roundRobin ? DISPATCH_ROUND : DISPATCH_SEQUENTIAL,
                provider, hovered(hover, box.index(), Kind.DISPATCH));
        drawLabel(graphics, tr(roundRobin
                ? "idlefactory.node.label.round_robin" : "idlefactory.node.label.sequential"),
                NodeLayout.keyX(viewport, 2), NodeLayout.KEY_W, keyTextY,
                roundRobin ? DISPATCH_ROUND_TEXT : DISPATCH_SEQUENTIAL_TEXT);

        // 动作键没有开关态：编辑冷灰 + 黑字，删除灰红 + 白字，和模式键的浅色系统分开。
        int actionY = top + NodeLayout.KEY_INSET_Y;
        int editX = NodeLayout.actionX(viewport);
        int deleteX = NodeLayout.deleteX(viewport);
        KeycapButton.draw(graphics, editX, actionY,
                NodeLayout.ACTION_W, NodeLayout.ACTION_H, EDIT_KEY,
                true, hovered(hover, box.index(), Kind.EDIT));
        drawLabel(graphics, tr("idlefactory.node.label.edit"), editX,
                NodeLayout.ACTION_W, keyTextY, TEXT_PRIMARY);

        KeycapButton.draw(graphics, deleteX, actionY,
                NodeLayout.ACTION_W, NodeLayout.ACTION_H, DELETE_KEY, true,
                hovered(hover, box.index(), Kind.DELETE));
        drawLabel(graphics, tr("idlefactory.node.label.delete"), deleteX,
                NodeLayout.ACTION_W, keyTextY, 0xFFFFFFFF);
    }

    /**
     * 视图表入口的绘制数据：从节点行取。
     *
     * <p>成员行的标签按加入顺序编号，与旧快照 {@code "#N"} 的口径一致。</p>
     *
     * @param node 节点行
     * @return 卡片数据
     */
    private CardData cardOf(ViewNode node) {
        ViewNodeCore core = node.core();
        StatusView status = StatusView.of(core);
        List<MemberCardData> members = new ArrayList<>(node.machines().size());
        for (int i = 0; i < node.machines().size(); i++) {
            ViewMachine machine = node.machines().get(i);
            members.add(new MemberCardData("#" + (i + 1), machine.blockId(), status));
        }
        return new CardData(core.nodeId(), displayName(node), status,
                statusHint(core.probed(), core.stale(), core.health(), core.available()),
                core.probed() && core.health().isOnline(), core.typeBlockId(), core.available(),
                core.blocking().isOn(), core.multiplier(), core.dispatch(), node.machineCount(),
                members);
    }

    /**
     * 节点显示名：自定义名字优先，否则用方块名。
     *
     * @param node 节点行
     * @return 显示名
     */
    private static String displayName(ViewNode node) {
        return node.core().name() != null
                ? node.core().name() : UiResources.blockName(node.core().typeBlockId());
    }

    /**
     * 取一条界面文案。
     *
     * @param key  语言键
     * @param args 占位符实参
     * @return 当前语言下的文本
     */
    private static String tr(String key, Object... args) {
        return net.minecraft.network.chat.Component.translatable(key, args).getString();
    }

    /** 倍率键帽文字：关 / ×N / 智。 */
    private static String multiplierLabel(
            io.github.crafteve.idlefactory.node.Multiplier multiplier) {
        return switch (multiplier.mode()) {
            case OFF -> tr("idlefactory.node.label.multiplier.off");
            case FIXED -> Integer.toString(multiplier.factor());
            case SMART -> tr("idlefactory.node.label.multiplier.smart");
        };
    }

    private void drawStatusHints(GuiGraphics graphics, Viewport viewport, CardData row, int top) {
        String hint = row.hint();
        int nameX = NodeLayout.nameX(viewport);
        int nameWidth = NodeLayout.nameWidth(viewport);
        int used = this.font.width(row.name());
        if (hint.isEmpty() || used + 2 + this.font.width(hint) > nameWidth) {
            return;
        }
        graphics.drawString(this.font, hint, nameX + used + 2, top + 6, row.status().color(),
                false);
    }

    /**
     * 状态小字：失联 / 未载 / 非 ME。只放得下时画，完整状态始终在 tooltip 里。
     *
     * @param probed    是否探测过
     * @param stale     健康是否过期
     * @param health    节点健康
     * @param available provider 是否可用
     * @return 小字；空串表示不放
     */
    private static String statusHint(boolean probed, boolean stale, NodeHealth health,
            boolean available) {
        if (!probed) {
            return tr("idlefactory.status.unprobed");
        }
        if (stale) {
            return tr("idlefactory.status.stale");
        }
        return switch (health) {
            case OFFLINE -> tr("idlefactory.status.offline");
            case PARTIAL -> tr("idlefactory.status.partial");
            case ONLINE -> available ? "" : tr("idlefactory.node.hint.not_me");
        };
    }

    private void drawMembers(GuiGraphics graphics, Viewport viewport, NodeBox box, CardData row,
            int scroll, @Nullable Hit hover) {
        for (int i = 0; i < box.shownMembers(); i++) {
            MemberCardData member = row.members().get(i);
            int y = NodeLayout.memberRowY(viewport, box, i, scroll);
            int x = NodeLayout.memberX(viewport);
            int width = NodeLayout.memberWidth(viewport);
            Card.draw(graphics, x, y, width, NodeLayout.MEMBER_ROW_H,
                    Card.Level.IO, true);
            if (hover != null && hover.kind() == Kind.MEMBER && hover.node() == box.index()
                    && hover.member() == i) {
                graphics.fill(x + 1, y + 1, x + width - 1, y + NodeLayout.MEMBER_ROW_H - 1,
                        HOVER_FILL);
            }
            int iconX = NodeLayout.memberIconX(viewport);
            int iconY = y + (NodeLayout.MEMBER_ROW_H - NodeLayout.MEMBER_ICON) / 2;
            ItemStack icon = UiResources.blockStack(member.blockId());
            float scale = NodeLayout.MEMBER_ICON / 16.0F;
            graphics.pose().pushPose();
            graphics.pose().translate(iconX, iconY, 0.0F);
            graphics.pose().scale(scale, scale, 1.0F);
            graphics.renderItem(icon.isEmpty() ? new ItemStack(Items.BARRIER) : icon, 0, 0);
            graphics.pose().popPose();
            graphics.drawString(this.font, member.label(), NodeLayout.memberTextX(viewport),
                    y + 4, TEXT_PRIMARY, false);
            NineSliceRenderer.icon(graphics, member.status().lamp(),
                    NodeLayout.memberDotX(viewport), y + 6, 3);
            int locateX = NodeLayout.locateX(viewport);
            KeycapButton.draw(graphics, locateX, y + 1, NodeLayout.LOCATE,
                    NodeLayout.MEMBER_ROW_H - 2, BEVEL_LIGHT, true,
                    hover != null && hover.kind() == Kind.LOCATE && hover.node() == box.index()
                            && hover.member() == i);
            drawLabel(graphics, tr("idlefactory.node.label.locate"), locateX, NodeLayout.LOCATE, y + 3,
                    TEXT_PRIMARY);
        }
        if (box.totalMembers() > box.shownMembers()) {
            int y = NodeLayout.moreRowY(viewport, box, scroll);
            int x = NodeLayout.memberX(viewport);
            graphics.drawString(this.font,
                    tr("idlefactory.node.more", box.totalMembers() - box.shownMembers()), x + 4, y + 3,
                    TEXT_DIM, false);
        }
    }

    private boolean hovered(@Nullable Hit hover, int node, Kind kind) {
        return hover != null && hover.node() == node && hover.kind() == kind;
    }

    /**
     * 在键帽里居中画一个汉字或符号；和被动 IO 的 chip 同一种表达。
     *
     * @param graphics 画布
     * @param text     单字
     * @param x        键帽左边缘
     * @param width    键帽宽
     * @param y        文字基线
     * @param color    颜色
     */
    private void drawLabel(GuiGraphics graphics, String text, int x, int width, int y,
            int color) {
        graphics.drawString(this.font, text, x + (width - this.font.width(text)) / 2, y, color,
                false);
    }

    private String ellipsize(String text, int width) {
        if (width <= 0) {
            return "";
        }
        if (this.font.width(text) <= width) {
            return text;
        }
        String ellipsis = "…";
        int body = width - this.font.width(ellipsis);
        return body <= 0 ? "" : this.font.plainSubstrByWidth(text, body) + ellipsis;
    }
}
