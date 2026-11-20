package io.github.crafteve.idlefactory.client.screen.terminal.node;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.client.screen.UiResources;
import io.github.crafteve.idlefactory.client.screen.text.TerminalText;
import io.github.crafteve.idlefactory.client.screen.paint.StatusView;
import io.github.crafteve.idlefactory.client.screen.terminal.node.NodeLayout.Hit;
import io.github.crafteve.idlefactory.view.ViewMachine;
import io.github.crafteve.idlefactory.view.ViewNode;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

/**
 * 节点终端的 tooltip：节点、provider 键、机器行、定位与占位按钮的说明。
 *
 * <p>只把视图表里已有的字段拼成文字，不重新判断状态；文案走 {@code idlefactory.node.tooltip.*}
 * 语言键，带数值的用占位符，短标签单独成键。</p>
 */
final class NodeTooltip {
    private NodeTooltip() {
        // 纯静态工具类
    }

    /**
     * 构造 tooltip。
     *
     * @param nodes 节点行
     * @param hit   命中；{@code null} 时返回空列表
     * @return 文本行
     */
    static List<Component> of(List<ViewNode> nodes, @Nullable Hit hit) {
        if (hit == null || hit.node() < 0 || hit.node() >= nodes.size()) {
            return List.of();
        }
        ViewNode node = nodes.get(hit.node());
        return switch (hit.kind()) {
            case NODE, NAME -> nodeLines(node);
            case BLOCKING -> List.of(
                    bold(Component.translatable("idlefactory.node.tooltip.blocking")),
                    keyed("idlefactory.node.tooltip.blocking.value",
                            Component.translatable(node.core().blocking().isOn()
                                    ? "idlefactory.common.on" : "idlefactory.common.off")),
                    hint(Component.translatable("idlefactory.node.tooltip.click_toggle")));
            case MULTIPLIER -> List.of(
                    bold(Component.translatable("idlefactory.node.tooltip.multiplier")),
                    hint(Component.translatable("idlefactory.node.tooltip.multiplier.cycle")));
            case DISPATCH -> List.of(
                    bold(Component.translatable("idlefactory.node.tooltip.dispatch")),
                    hint(Component.translatable("idlefactory.node.tooltip.dispatch.toggle")));
            case EDIT -> List.of(
                    bold(Component.translatable("idlefactory.node.tooltip.edit")),
                    hint(Component.translatable("idlefactory.node.tooltip.edit.hint")),
                    hint(Component.translatable("idlefactory.node.tooltip.edit.wand")));
            case DELETE -> deleteLines(node);
            case MEMBER -> {
                if (hit.member() < 0 || hit.member() >= node.machines().size()) {
                    yield List.of();
                }
                ViewMachine machine = node.machines().get(hit.member());
                yield memberLines("#" + (hit.member() + 1), machine);
            }
            case LOCATE -> {
                if (hit.member() < 0 || hit.member() >= node.machines().size()) {
                    yield List.of();
                }
                yield locateLines(node.machines().get(hit.member()));
            }
            case MORE -> List.of(Component.translatable("idlefactory.node.tooltip.more",
                    node.machines().size() - NodeLayout.EXPANDED_LIMIT));
        };
    }

    private static List<Component> nodeLines(ViewNode node) {
        List<Component> lines = new ArrayList<>(7);
        lines.add(bold(Component.literal(displayName(node))));
        StatusView status = StatusView.of(node.core());
        lines.add(Component.literal(status.text()).withStyle(
                style -> style.withColor(TextColor.fromRgb(status.color() & 0xFFFFFF))));
        lines.add(keyed("idlefactory.node.tooltip.provider", node.core().available()
                ? Component.translatable("idlefactory.node.tooltip.provider.available",
                        Component.translatable(node.core().blocking().isOn()
                                ? "idlefactory.common.on" : "idlefactory.common.off"))
                : Component.translatable("idlefactory.node.tooltip.provider.unavailable")));
        lines.add(keyed("idlefactory.node.tooltip.members", node.machineCount()));
        lines.add(keyed("idlefactory.node.tooltip.rules_patterns", node.ruleCount(),
                node.core().patternCount()));
        if (node.core().pendingCount() > 0) {
            lines.add(keyed("idlefactory.node.tooltip.pending", node.core().pendingCount(),
                    waitedText(node.core().pendingOldestAt())));
        }
        if (!node.machines().isEmpty()) {
            ViewMachine first = node.machines().get(0);
            lines.add(keyed("idlefactory.node.tooltip.position",
                    first.block().dimension().location().toString(),
                    first.block().pos().toShortString()));
        }
        lines.add(hint(Component.translatable("idlefactory.node.tooltip.node.hint")));
        return lines;
    }

    private static List<Component> deleteLines(ViewNode node) {
        List<Component> lines = new ArrayList<>(3);
        lines.add(bold(Component.translatable("idlefactory.node.tooltip.delete")));
        if (node.configured()) {
            lines.add(hint(Component.translatable("idlefactory.node.tooltip.delete.configured",
                    node.ruleCount(), node.core().patternCount())));
            lines.add(hint(Component.translatable("idlefactory.node.tooltip.delete.confirm")));
        } else if (node.core().autoEnergyOnly()) {
            lines.add(hint(Component.translatable("idlefactory.node.tooltip.delete.energy")));
        } else {
            lines.add(hint(Component.translatable("idlefactory.node.tooltip.delete.empty")));
        }
        return lines;
    }

    private static List<Component> memberLines(String label, ViewMachine machine) {
        List<Component> lines = new ArrayList<>(4);
        lines.add(bold(Component.literal(label)));
        lines.add(hint(Component.translatable("idlefactory.node.tooltip.member.position",
                machine.block().dimension().location().toString(),
                machine.block().pos().toShortString())));
        lines.add(hint(Component.literal(machine.health().describe())));
        lines.add(hint(Component.translatable("idlefactory.node.tooltip.member.locate")));
        return lines;
    }

    private static List<Component> locateLines(ViewMachine machine) {
        return List.of(
                bold(Component.translatable("idlefactory.node.tooltip.locate")),
                hint(Component.translatable("idlefactory.node.tooltip.member.position",
                        machine.block().dimension().location().toString(),
                        machine.block().pos().toShortString())),
                hint(Component.translatable("idlefactory.node.tooltip.locate.hint")));
    }

    /**
     * 节点显示名：自定义名字优先，否则用方块名。
     *
     * @param node 节点行
     * @return 显示名
     */
    static String displayName(ViewNode node) {
        return NodeModel.displayName(node);
    }

    /**
     * 队首等了多久，客户端用本地游戏时间与队首入队时刻比。
     *
     * @param pendingOldestAt 队首入队时刻；没有为 -1
     * @return 秒数；算不出来时为 0
     */
    private static long waitedSeconds(long pendingOldestAt) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null || pendingOldestAt < 0L) {
            return 0L;
        }
        return Math.max(0L, level.getGameTime() - pendingOldestAt) / 20L;
    }

    private static Component waitedText(long pendingOldestAt) {
        long seconds = waitedSeconds(pendingOldestAt);
        return seconds <= 0L ? Component.empty() : Component.literal(" · " + seconds);
    }

    private static Component bold(Component text) {
        return text.copy().withStyle(ChatFormatting.BOLD);
    }

    private static Component hint(Component text) {
        return text.copy().withStyle(ChatFormatting.GRAY);
    }

    private static Component keyed(String key, Object... args) {
        return Component.translatable(key, args).withStyle(ChatFormatting.GRAY);
    }
}
