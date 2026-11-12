/**
 * 工厂的 IO 执行层：表单、排期、搬运事务、账本与历史结论。
 *
 * <p>工作清单是 {@code Form}，一行是 {@code Form.Entry}（机器 + 一条规则，规则自带方向）。
 * {@code PassivePlanner} 按规则择时并排好这一轮的清单，{@code PassiveIoRunner} 是薄驱动，
 * {@code IoExecutor} 按清单跑，{@code IoPortResolver} 是机器侧唯一的选面 / 模拟 / 写入入口，
 * {@code IoTransfer} 是唯一写路径。</p>
 *
 * <p>{@code Reason} 是执行结果原因，{@code MoveResult} 是「搬了多少 + 为什么」。
 * {@code Analyzer} 是账本（{@code Ledger}，会滚）、结论（{@code Conclusion}，不滚）与速率
 * （{@code Stats}）三者唯一的维护者。</p>
 *
 * <p>本包不认识节点：规则由表单携带下来，节点只在调用侧存在。不写配置、不写存档。</p>
 */
package io.github.crafteve.idlefactory.factory.io;
