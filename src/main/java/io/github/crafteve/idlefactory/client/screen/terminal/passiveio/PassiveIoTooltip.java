package io.github.crafteve.idlefactory.client.screen.terminal.passiveio;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEItemKey;

import io.github.crafteve.idlefactory.client.screen.UiResources;
import io.github.crafteve.idlefactory.client.screen.text.TerminalText;
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
                    Component.translatable("idlefactory.passive.tooltip.add_row"),
                    Component.translatable("idlefactory.passive.tooltip.add_row.default_input").withStyle(ChatFormatting.GRAY));
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
                Component.translatable("idlefactory.passive.tooltip.fe_input").withStyle(ChatFormatting.BOLD),
                Component.translatable("idlefactory.passive.tooltip.fe_input.hint")
                        .withStyle(ChatFormatting.GRAY),
                Component.translatable("idlefactory.passive.tooltip.fe_input.switch").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static List<Component> machine(BlockView block) {
        List<Component> lines = new ArrayList<>(4);
        lines.add(Component.literal(block.name()).withStyle(ChatFormatting.BOLD));
        lines.add(Component.literal(block.status().text()).withStyle(
                style -> style.withColor(TextColor.fromRgb(block.status().color() & 0xFFFFFF))));
        lines.add(Component.literal(block.machine().block().dimension().location()
                + " " + block.machine().block().pos().toShortString()).withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("idlefactory.passive.tooltip.members", block.machineCount())
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("idlefactory.passive.tooltip.rules", block.rules().size())
                .withStyle(ChatFormatting.GRAY));
        return lines;
    }

    private static List<Component> rule(BlockView block, RuleRow row) {
        List<Component> lines = new ArrayList<>(6);
        lines.add(Component.literal(UiResources.keyName(row.key())).withStyle(ChatFormatting.BOLD));
        lines.add(line(tr("idlefactory.passive.tooltip.direction"),
                tr(row.input() ? "idlefactory.passive.side.input" : "idlefactory.passive.side.output")));
        lines.add(line(tr("idlefactory.passive.tooltip.face"),
                row.face() == null ? tr("idlefactory.passive.face.any") : TerminalText.face(row.face())));
        if (row.key() instanceof AEItemKey) {
            lines.add(line(tr("idlefactory.passive.tooltip.mode"),
                tr(row.mode() == MatchMode.EXACT
                        ? "idlefactory.passive.mode.exact" : "idlefactory.passive.mode.fuzzy")));
        }
        lines.add(line(tr("idlefactory.passive.tooltip.rate"),
                TerminalText.rateText(row.rate(), row.key())));
        IoReason reason = TerminalText.effectiveReason(row.rate(), row.reason());
        if (reason != IoReason.RUN) {
            lines.add(line(tr("idlefactory.passive.tooltip.reason"),
                TerminalText.reason(reason, row.input())));
        }
        lines.add(Component.translatable("idlefactory.passive.tooltip.hint")
                .withStyle(ChatFormatting.DARK_GRAY));
        return lines;
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

    private static Component line(String label, String value) {
        MutableComponent text = Component.literal(label + "：").withStyle(ChatFormatting.GRAY);
        return text.append(Component.literal(value).withStyle(ChatFormatting.WHITE));
    }
}