package io.github.crafteve.idlefactory.command;

import java.util.List;

import io.github.crafteve.idlefactory.key.BlockKey;

/**
 * 绑定杖草稿的校验结果。
 *
 * <p>{@code reason} 是草稿级结论：{@code OK} 表示没有草稿级问题；其它值表示面板不该打开
 * （或者逐方块问题上还带着一个总原因）。{@code conflicts} 是要标红的方块：类型不一致、
 * 已被占用、重复、删到 0 台时还有规则 / 样板等。</p>
 *
 * <p>面板是否打开只看 {@code reason == OK || !conflicts.isEmpty()}：逐方块问题不阻止打开，
 * 只标红。</p>
 *
 * @param reason    草稿级原因
 * @param conflicts 需要标红的方块
 */
public record WandGateResult(CommandReason reason, List<BlockKey> conflicts) {
    public WandGateResult {
        conflicts = List.copyOf(conflicts);
    }

    /** 面板是否可以打开。 */
    public boolean openable() {
        return this.reason == CommandReason.OK || !this.conflicts.isEmpty();
    }
}
