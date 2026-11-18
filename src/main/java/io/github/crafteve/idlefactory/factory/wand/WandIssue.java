package io.github.crafteve.idlefactory.factory.wand;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.location.BlockKey;

/**
 * 一条校验问题：可以挂在某个方块上，也可以是整份草稿的问题。
 *
 * <p>面板按 {@code block} 把问题画到对应行；{@code block == null} 的是草稿级问题，画在面板底部。
 * 删除被拒绝时 {@code failure} 固定是 {@link WandFailure#DELETE_BLOCKED}，用
 * {@code reason} 区分规则还是样板。</p>
 *
 * @param block   出问题的方块；整份草稿的问题为 {@code null}
 * @param failure 失败原因，不能是 {@link WandFailure#NONE}
 * @param reason  删除被拒时的残留配置；其余情况固定为 {@link WandDeleteReason#NONE}
 */
public record WandIssue(@Nullable BlockKey block, WandFailure failure, WandDeleteReason reason) {

    public WandIssue {
        if (failure == WandFailure.NONE) {
            throw new IllegalArgumentException("问题不能是成功");
        }
        if (failure == WandFailure.DELETE_BLOCKED) {
            if (reason == WandDeleteReason.NONE) {
                throw new IllegalArgumentException("删除被拒必须带原因");
            }
        } else if (reason != WandDeleteReason.NONE) {
            throw new IllegalArgumentException("只有删除被拒才带原因");
        }
    }

    /**
     * 草稿级问题。
     *
     * @param failure 失败原因
     * @return 不挂方块的问题
     */
    public static WandIssue global(WandFailure failure) {
        return new WandIssue(null, failure, WandDeleteReason.NONE);
    }

    /**
     * 挂在某个方块上的问题。
     *
     * @param block   方块
     * @param failure 失败原因
     * @return 方块级问题
     */
    public static WandIssue at(BlockKey block, WandFailure failure) {
        return new WandIssue(block, failure, WandDeleteReason.NONE);
    }

    /**
     * 删除被拒绝：节点还有规则或样板。
     *
     * @param block  被删除机器的方块
     * @param reason 残留配置
     * @return 方块级问题
     */
    public static WandIssue blocked(BlockKey block, WandDeleteReason reason) {
        return new WandIssue(block, WandFailure.DELETE_BLOCKED, reason);
    }
}
