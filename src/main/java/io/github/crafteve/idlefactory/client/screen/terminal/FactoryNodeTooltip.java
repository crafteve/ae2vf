package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.client.screen.terminal.FactoryNodeLayout.Hit;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryNodeLayout.Kind;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryNodeModel.MemberRow;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryNodeModel.NodeRow;
import io.github.crafteve.idlefactory.factory.FactoryNodeSnapshotBuilder;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

/**
 * 节点终端的 tooltip：节点、provider 键、机器行、定位与占位按钮的说明。
 *
 * <p>只把视图里已有的字段拼成文字，不重新判断状态；状态文案取 {@code status().text()}，
 * 坐标取成员主方块。</p>
 */
final class FactoryNodeTooltip {
    private FactoryNodeTooltip() {
        // 纯静态工具类
    }

    /**
     * 构造 tooltip。
     *
     * @param rows 模型行
     * @param hit  命中；{@code null} 时返回空列表
     * @return 文本行
     */
    static List<Component> of(List<NodeRow> rows, @Nullable Hit hit) {
        if (hit == null || hit.node() < 0 || hit.node() >= rows.size()) {
            return List.of();
        }
        NodeRow node = rows.get(hit.node());
        return switch (hit.kind()) {
            case NODE, NAME -> node(node);
            case BLOCKING -> provider(node, "阻挡", node.view().provider().blocking().isOn()
                    ? "开" : "关", "左键切换");
            case MULTIPLIER -> List.of(
                    Component.literal("倍增").withStyle(ChatFormatting.BOLD),
                    Component.literal("待接入：样板倍率").withStyle(ChatFormatting.GRAY));
            case DISPATCH -> List.of(
                    Component.literal("逐台 / 轮询").withStyle(ChatFormatting.BOLD),
                    Component.literal("待接入：多机派单策略").withStyle(ChatFormatting.GRAY));
            case EDIT -> List.of(
                    Component.literal("编辑节点").withStyle(ChatFormatting.BOLD),
                    Component.literal("待接入：绑定杖编辑会话").withStyle(ChatFormatting.GRAY));
            case DELETE -> delete(node);
            case MEMBER -> member(node, hit.member());
            case LOCATE -> locate(node, hit.member());
            case MORE -> List.of(Component.literal("展开剩余 "
                    + (node.members().size() - FactoryNodeLayout.EXPANDED_LIMIT) + " 台"));
        };
    }

    private static List<Component> node(NodeRow node) {
        List<Component> lines = new ArrayList<>(6);
        lines.add(Component.literal(node.name()).withStyle(ChatFormatting.BOLD));
        lines.add(Component.literal(node.status().text()).withStyle(
                style -> style.withColor(TextColor.fromRgb(node.status().color() & 0xFFFFFF))));
        String blocking = node.view().provider().blocking().isOn() ? "开" : "关";
        lines.add(line("provider", node.view().provider().available()
                ? "可用 · 阻挡 " + blocking : "不可用（非 ME / 离线 / 失联）"));
        lines.add(line("成员", node.view().machineCount() + " 台 · 方块 "
                + node.view().blockCount() + " 个"));
        lines.add(line("规则 / 样板", node.view().ruleCount() + " 条 / "
                + node.view().patternCount() + " 份"));
        if (!node.members().isEmpty()) {
            FactoryNodeSnapshotBuilder.MemberView first = node.members().get(0).member();
            lines.add(line("位置", first.dimensionId() + " "
                    + (first.primaryPos() == null ? "未知" : first.primaryPos().toShortString())));
        }
        lines.add(Component.literal("点名字改名 · 点卡片展开 / 收起")
                .withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    private static List<Component> provider(NodeRow node, String label, String value, String hint) {
        List<Component> lines = new ArrayList<>(3);
        lines.add(Component.literal(label).withStyle(ChatFormatting.BOLD));
        if (!node.view().provider().available()) {
            lines.add(Component.literal("不可用：非 ME / 仓库离线 / 失联")
                    .withStyle(ChatFormatting.GRAY));
            return lines;
        }
        lines.add(line(label, value));
        lines.add(Component.literal(hint).withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    private static List<Component> delete(NodeRow node) {
        List<Component> lines = new ArrayList<>(3);
        lines.add(Component.literal("删除节点").withStyle(ChatFormatting.BOLD));
        if (node.view().configured()) {
            lines.add(Component.literal("该节点有 " + node.view().ruleCount() + " 条规则、"
                    + node.view().patternCount() + " 份样板").withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("点击后弹窗确认").withStyle(ChatFormatting.DARK_GRAY));
        } else {
            lines.add(Component.literal("没有规则与样板，确认后删除")
                    .withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    private static List<Component> member(NodeRow node, int index) {
        if (index < 0 || index >= node.members().size()) {
            return List.of();
        }
        MemberRow member = node.members().get(index);
        FactoryNodeSnapshotBuilder.MemberView view = member.member();
        List<Component> lines = new ArrayList<>(4);
        lines.add(Component.literal(member.label()).withStyle(ChatFormatting.BOLD));
        lines.add(Component.literal(view.dimensionId() + " "
                + (view.primaryPos() == null ? "未知" : view.primaryPos().toShortString()))
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal(view.health().describe()).withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("点行尾准星定位").withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    private static List<Component> locate(NodeRow node, int index) {
        if (index < 0 || index >= node.members().size()) {
            return List.of();
        }
        FactoryNodeSnapshotBuilder.MemberView view = node.members().get(index).member();
        return List.of(
                Component.literal("定位").withStyle(ChatFormatting.BOLD),
                Component.literal(view.dimensionId() + " "
                        + (view.primaryPos() == null ? "未知" : view.primaryPos().toShortString()))
                        .withStyle(ChatFormatting.GRAY),
                Component.literal("世界高亮 3 秒").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static Component line(String label, String value) {
        MutableComponent text = Component.literal(label + "：").withStyle(ChatFormatting.GRAY);
        return text.append(Component.literal(value).withStyle(ChatFormatting.WHITE));
    }
}
