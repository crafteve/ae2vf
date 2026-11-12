/**
 * 工厂的 IO 执行层：表单、规划、搬运事务、账本与历史结论。
 *
 * <p>工作清单是 {@code Form}，一行是 {@code Form.Entry}（机器 + 一条规则，规则自带方向）。
 * {@code IoRunner} 是唯一入口，按 回收 → 主动 → 被动 依次跑：</p>
 * <ul>
 *   <li>回收：{@code ActivePlanner} 定节奏，{@code IoExecutor.sweep} 逐面抽产物；</li>
 *   <li>主动：{@code ActivePlanner} 把 {@code DispatchOrder} 分到多台机器，产出 {@code Fill}；</li>
 *   <li>被动：{@code PassivePlanner} 择时并产出逐条 {@link io.github.crafteve.idlefactory.factory.io.IoJob}。</li>
 * </ul>
 *
 * <p>选面是 {@code FacePick}（配置面 → 经验面 → 逐面探），容量是 {@code CapacityPick}，
 * 变体是 {@code VariantPick}，机器侧存储一律由 {@code PortExecutor} 提供。执行器只跑事务，
 * 不决定搬什么、不选面。</p>
 *
 * <p>{@code Reason} 是执行结果原因，{@code MoveResult} 是「搬了多少 + 为什么」。
 * {@code Analyzer} 是账本（{@code Ledger}，会滚）、结论（{@code Conclusion} / {@code NodeConclusion}）、
 * 速率（{@code Stats}）与经验面（{@code FaceHint}）唯一的维护者。</p>
 *
 * <p>本包不认识节点：节点只把 {@code DispatchState} 交下来，执行层不 import 节点的任何类型。
 * 不写配置、不写存档（队列的落档在节点侧）。</p>
 */
package io.github.crafteve.idlefactory.factory.io;
