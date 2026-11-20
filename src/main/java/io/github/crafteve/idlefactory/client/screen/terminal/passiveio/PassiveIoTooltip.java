package io.github.crafteve.idlefactory.client.screen.terminal.passiveio;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEItemKey;

import io.github.crafteve.idlefactory.client.screen.FactoryLabels;
import io.github.crafteve.idlefactory.client.screen.terminal.passiveio.PassiveIoLayout.Hit;
import io.github.crafteve.idlefactory.client.screen.terminal.passiveio.PassiveIoModel.BlockView;
import io.github.crafteve.idlefactory.client.screen.terminal.passiveio.PassiveIoModel.RuleRow;
import io.github.crafteve.idlefactory.io.IoReason;
import io.github.crafteve.idlefactory.machine.MatchMode;
import io.github.crafteve.idlefactory.resource.AeKeyAdapter;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

/**
 * 内容区 tooltip：机器行、规则行、chip 与虚线新增行的说明。
 *
 * <p>只把模型里已有的字段拼成文字，不重新判断状态：状态文字取 {@code block.status().text()}，
 * 速率取服务端算好的值，原因取逐规则原因。chip 的 tooltip 与整行共用一份，鼠标停在 chip 上
 * 也能看到完整说明。</p>
 */
final class PassiveIoTooltip {
    private PassiveIoTooltip() {
        // 纯静态工具类
    }

    /**
     * 构造 tooltip。
     *
     * @param blocks 模型块
     * @param hit    命中；{@code null} 时返回空列表
     * @return 文本行
     */
    static List<Component> of(List<BlockView> blocks, @Nullable Hit hit) {
        if (hit == null || hit.block() < 0 || hit.block() >= blocks.size()) {
            return List.of();
        }
        BlockView block = blocks.get(hit.block());
        return switch (hit.kind()) {
            case MACHINE -> machine(block);
            case ENERGY -> energy();
            case ADD -> List.of(
                    Component.literal("从 JEI 拖到这里新建行"),
                    Component.literal("新规则默认作为输入").withStyle(ChatFormatting.GRAY));
            default -> rule(block, block.rules().get(hit.rule()));
        };
    }

    /**
     * FE 快捷按钮的提示：只说明点下去会发生什么，不重复规则行信息。
     *
     * @return 文本行
     */
    private static List<Component> energy() {
        return List.of(
                Component.literal("FE 输入").withStyle(ChatFormatting.BOLD),
                Component.literal("点一下：给这个节点加一条输入 FE 规则")
                        .withStyle(ChatFormatting.GRAY),
                Component.literal("加完可用规则上的「入」切成输出").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static List<Component> machine(BlockView block) {
        List<Component> lines = new ArrayList<>(4);
        lines.add(Component.literal(block.name()).withStyle(ChatFormatting.BOLD));
        lines.add(Component.literal(block.status().text()).withStyle(
                style -> style.withColor(TextColor.fromRgb(block.status().color() & 0xFFFFFF))));
        lines.add(Component.literal(block.machine().block().dimension().location()
                + " " + block.machine().block().pos().toShortString()).withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("机器 " + block.machineCount() + " 台")
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("规则 " + block.rules().size() + " 条")
                .withStyle(ChatFormatting.GRAY));
        return lines;
    }

    private static List<Component> rule(BlockView block, RuleRow row) {
        List<Component> lines = new ArrayList<>(6);
        lines.add(Component.literal(FactoryLabels.name(row.key())).withStyle(ChatFormatting.BOLD));
        lines.add(line("方向", row.input() ? "输入" : "输出"));
        lines.add(line("面", row.face() == null ? "任意" : FactoryLabels.face(row.face())));
        if (row.key() instanceof AEItemKey) {
            lines.add(line("模式", row.mode() == MatchMode.EXACT ? "精确" : "模糊"));
        }
        lines.add(line("速率", FactoryLabels.rateText(row.rate(), row.key())));
        IoReason reason = FactoryLabels.effectiveReason(row.rate(), row.reason());
        if (reason != IoReason.RUN) {
            lines.add(line("原因", FactoryLabels.reason(reason, row.input())));
        }
        lines.add(Component.literal("左键改方向 / 面 / 模式，右键删除")
                .withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    private static Component line(String label, String value) {
        MutableComponent text = Component.literal(label + "：").withStyle(ChatFormatting.GRAY);
        return text.append(Component.literal(value).withStyle(ChatFormatting.WHITE));
    }
}