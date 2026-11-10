package io.github.crafteve.idlefactory.factory.wand;

/**
 * 删除被拒绝时节点上残留的配置。
 *
 * <p>只有 {@link WandFailure#DELETE_BLOCKED} 才会带非 {@link #NONE} 的原因：节点还有 IO 规则
 * 或还有样板时不允许把最后一台机器删掉，否则规则与样板会跟着节点一起消失。</p>
 */
public enum WandDeleteReason {
    /** 不是删除拒绝，或没有残留配置。 */
    NONE,
    /** 节点还有 IO 规则。 */
    HAS_RULES,
    /** 节点还有样板。 */
    HAS_PATTERNS
}
