package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.client.screen.FactoryGuiTheme;
import io.github.crafteve.idlefactory.client.screen.FactoryLabels;
import io.github.crafteve.idlefactory.client.screen.FactoryStatus;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryNodeLayout.Hit;
import io.github.crafteve.idlefactory.view.MemberSnapshot;
import io.github.crafteve.idlefactory.view.ViewMachine;
import io.github.crafteve.idlefactory.view.ViewNode;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryNodeModel.MemberRow;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryNodeModel.NodeRow;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

/**
 * 节点终端的 tooltip：节点、provider 键、机器行、定位与占位按钮的说明。
 *
 * <p>只把视图里已有的字段拼成文字，不重新判断状态；状态文案取 {@code status().text()}，
 * 坐标取成员主方块。</p>
 *
 * <p>第 3a 步同时保留两种入口：{@link #of(List, Hit)} 吃旧的 {@link NodeRow}，
 * {@link #ofViews(List, Hit)} 吃视图表的 {@link ViewNode}。两边的文字拼装共用同一批私有方法，
 * 第 3c 步删除旧入口。</p>
 */
final class FactoryNodeTooltip {
    private FactoryNodeTooltip() {
        // 纯静态工具类
    }

    /**
     * 构造 tooltip（旧入口：模型行）。
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
            case NODE, NAME -> nodeLines(node.name(), node.status(),
                    node.view().provider().available(),
                    node.view().provider().blocking().isOn(), node.view().machineCount(),
                    node.view().ruleCount(), node.view().patternCount(), node.view().pendingCount(),
                    node.view().pendingOldestAt(), firstMemberText(node));
            case BLOCKING -> providerLines(node.view().provider().available(), "阻挡",
                    node.view().provider().blocking().isOn() ? "开" : "关", "左键切换");
            case MULTIPLIER -> multiplierLines();
            case DISPATCH -> dispatchLines();
            case EDIT -> editLines();
            case DELETE -> deleteLines(node.view().configured(), node.view().ruleCount(),
                    node.view().patternCount(), node.view().autoEnergyOnly());
            case MEMBER -> {
                if (hit.member() < 0 || hit.member() >= node.members().size()) {
                    yield List.of();
                }
                MemberRow member = node.members().get(hit.member());
                MemberSnapshot view = member.member();
                yield memberLines(member.label(), view.dimensionId().toString(),
                        view.primaryPos() == null ? "未知" : view.primaryPos().toShortString(),
                        view.health().describe());
            }
            case LOCATE -> {
                if (hit.member() < 0 || hit.member() >= node.members().size()) {
                    yield List.of();
                }
                MemberSnapshot view = node.members().get(hit.member()).member();
                yield locateLines(view.dimensionId().toString(),
                        view.primaryPos() == null ? "未知" : view.primaryPos().toShortString());
            }
            case MORE -> moreLines(node.members().size());
        };
    }

    /**
     * 构造 tooltip（视图表入口）。
     *
     * <p>与 {@link #of(List, Hit)} 的区别只在数据来源：视图表把节点结论、规则与机器都算好了，
     * 这里不再看 {@code NodeSnapshot}。</p>
     *
     * @param nodes 节点行
     * @param theme 当前主题，状态文字取色用
     * @param hit   命中；{@code null} 时返回空列表
     * @return 文本行
     */
    static List<Component> ofViews(List<ViewNode> nodes, FactoryGuiTheme theme,
            @Nullable Hit hit) {
        if (hit == null || hit.node() < 0 || hit.node() >= nodes.size()) {
            return List.of();
        }
        ViewNode node = nodes.get(hit.node());
        FactoryStatus status = FactoryStatus.of(node.core(), theme);
        return switch (hit.kind()) {
            case NODE, NAME -> nodeLines(displayName(node), status, node.core().available(),
                    node.core().blocking().isOn(), node.machineCount(), node.ruleCount(),
                    node.core().patternCount(), node.core().pendingCount(),
                    node.core().pendingOldestAt(), firstMemberText(node));
            case BLOCKING -> providerLines(node.core().available(), "阻挡",
                    node.core().blocking().isOn() ? "开" : "关", "左键切换");
            case MULTIPLIER -> multiplierLines();
            case DISPATCH -> dispatchLines();
            case EDIT -> editLines();
            case DELETE -> deleteLines(node.configured(), node.ruleCount(),
                    node.core().patternCount(), node.core().autoEnergyOnly());
            case MEMBER -> {
                if (hit.member() < 0 || hit.member() >= node.machines().size()) {
                    yield List.of();
                }
                ViewMachine machine = node.machines().get(hit.member());
                yield memberLines("#" + (hit.member() + 1),
                        machine.block().dimension().location().toString(),
                        machine.block().pos().toShortString(), machine.health().describe());
            }
            case LOCATE -> {
                if (hit.member() < 0 || hit.member() >= node.machines().size()) {
                    yield List.of();
                }
                ViewMachine machine = node.machines().get(hit.member());
                yield locateLines(machine.block().dimension().location().toString(),
                        machine.block().pos().toShortString());
            }
            case MORE -> moreLines(node.machines().size());
        };
    }

    /**
     * 节点显示名：自定义名字优先，否则用方块名。
     *
     * @param node 节点行
     * @return 显示名
     */
    private static String displayName(ViewNode node) {
        return node.core().name() != null
                ? node.core().name() : FactoryLabels.blockName(node.core().typeBlockId());
    }

    @Nullable
    private static String firstMemberText(NodeRow node) {
        if (node.members().isEmpty()) {
            return null;
        }
        MemberSnapshot first = node.members().get(0).member();
        return first.dimensionId() + " " + (first.primaryPos() == null
                ? "未知" : first.primaryPos().toShortString());
    }

    @Nullable
    private static String firstMemberText(ViewNode node) {
        if (node.machines().isEmpty()) {
            return null;
        }
        ViewMachine first = node.machines().get(0);
        return first.block().dimension().location() + " " + first.block().pos().toShortString();
    }

    private static List<Component> nodeLines(String name, FactoryStatus status, boolean available,
            boolean blockingOn, int machineCount, int ruleCount, int patternCount,
            int pendingCount, long pendingOldestAt, @Nullable String firstMember) {
        List<Component> lines = new ArrayList<>(6);
        lines.add(Component.literal(name).withStyle(ChatFormatting.BOLD));
        lines.add(Component.literal(status.text()).withStyle(
                style -> style.withColor(TextColor.fromRgb(status.color() & 0xFFFFFF))));
        lines.add(line("provider", available
                ? "可用 · 阻挡 " + (blockingOn ? "开" : "关") : "不可用（非 ME / 离线 / 失联）"));
        lines.add(line("成员", machineCount + " 台"));
        lines.add(line("规则 / 样板", ruleCount + " 条 / " + patternCount + " 份"));
        if (pendingCount > 0) {
            lines.add(line("待投递", pendingCount + " 单" + waitedText(pendingOldestAt)));
        }
        if (firstMember != null) {
            lines.add(line("位置", firstMember));
        }
        lines.add(Component.literal("点名字改名 · 点卡片展开 / 收起")
                .withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    private static List<Component> multiplierLines() {
        return List.of(
                Component.literal("倍增").withStyle(ChatFormatting.BOLD),
                Component.literal("左键循环：关 → ×2 → ×4 → ×8 → 智能")
                        .withStyle(ChatFormatting.GRAY));
    }

    private static List<Component> dispatchLines() {
        return List.of(
                Component.literal("逐台 / 轮询").withStyle(ChatFormatting.BOLD),
                Component.literal("左键切换顺序 / 轮询").withStyle(ChatFormatting.GRAY));
    }

    private static List<Component> editLines() {
        return List.of(
                Component.literal("编辑节点").withStyle(ChatFormatting.BOLD),
                Component.literal("进入绑定杖编辑态，右键加 / 删机器，蹲下右键提交")
                        .withStyle(ChatFormatting.GRAY),
                Component.literal("主手或快捷栏里要有绑定杖").withStyle(ChatFormatting.DARK_GRAY));
    }

    /**
     * 队首等了多久，客户端用本地游戏时间与队首入队时刻比。
     *
     * @param pendingOldestAt 队首入队时刻；没有为 -1
     * @return 形如「 · 最久等 3 秒」；算不出来时为空串
     */
    private static String waitedText(long pendingOldestAt) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null || pendingOldestAt < 0L) {
            return "";
        }
        long ticks = Math.max(0L, level.getGameTime() - pendingOldestAt);
        return " · 最久等 " + (ticks / 20L) + " 秒";
    }

    private static List<Component> providerLines(boolean available, String label, String value,
            String hint) {
        List<Component> lines = new ArrayList<>(3);
        lines.add(Component.literal(label).withStyle(ChatFormatting.BOLD));
        if (!available) {
            lines.add(Component.literal("不可用：仓库离线 / 节点失联")
                    .withStyle(ChatFormatting.GRAY));
            return lines;
        }
        lines.add(line(label, value));
        lines.add(Component.literal(hint).withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    /**
     * 悬停提示里说明「要删掉什么」：规则、样板，还是两者都有。
     *
     * @param ruleCount     规则条数
     * @param patternCount  样板份数
     * @return 说明文字
     */
    private static String configuredText(int ruleCount, int patternCount) {
        if (ruleCount > 0 && patternCount > 0) {
            return "该节点有 " + ruleCount + " 条规则、" + patternCount + " 份样板";
        }
        if (patternCount > 0) {
            return "该节点有 " + patternCount + " 份样板（会退回背包）";
        }
        return "该节点有 " + ruleCount + " 条规则";
    }

    private static List<Component> deleteLines(boolean configured, int ruleCount,
            int patternCount, boolean autoEnergyOnly) {
        List<Component> lines = new ArrayList<>(3);
        lines.add(Component.literal("删除节点").withStyle(ChatFormatting.BOLD));
        if (configured) {
            lines.add(Component.literal(configuredText(ruleCount, patternCount))
                    .withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("点击后弹窗确认").withStyle(ChatFormatting.DARK_GRAY));
        } else if (autoEnergyOnly) {
            lines.add(Component.literal("只有接入时自动补的 FE 规则，直接删除")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(Component.literal("没有规则与样板，确认后删除")
                    .withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    private static List<Component> memberLines(String label, String dimensionId, String posText,
            String healthText) {
        List<Component> lines = new ArrayList<>(4);
        lines.add(Component.literal(label).withStyle(ChatFormatting.BOLD));
        lines.add(Component.literal(dimensionId + " " + posText).withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal(healthText).withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("点行尾准星定位").withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    private static List<Component> locateLines(String dimensionId, String posText) {
        return List.of(
                Component.literal("定位").withStyle(ChatFormatting.BOLD),
                Component.literal(dimensionId + " " + posText).withStyle(ChatFormatting.GRAY),
                Component.literal("世界高亮 3 秒").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static List<Component> moreLines(int memberCount) {
        return List.of(Component.literal("展开剩余 "
                + (memberCount - FactoryNodeLayout.EXPANDED_LIMIT) + " 台"));
    }

    private static Component line(String label, String value) {
        MutableComponent text = Component.literal(label + "：").withStyle(ChatFormatting.GRAY);
        return text.append(Component.literal(value).withStyle(ChatFormatting.WHITE));
    }
}
