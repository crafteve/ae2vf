package io.github.crafteve.idlefactory.factory.io;

import java.util.UUID;

/**
 * 一个执行单元的身份：机器 + 方向 + 规则。
 *
 * <p>规则归节点、执行归机器，所以身份必须带机器 id：同一条规则作用在 N 台机器上是 N 个单元。
 * 规则的面或匹配模式变了，{@link RuleKey} 就变，视为新单元（旧的账本与结论不会串过去）。</p>
 *
 * @param machineId 机器 id
 * @param input     是否输入；{@code false} 表示输出
 * @param rule      规则运行时身份
 */
public record IoUnitKey(UUID machineId, boolean input, RuleKey rule) {
}
