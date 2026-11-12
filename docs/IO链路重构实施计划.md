# IO 链路重构 · 分阶段实施计划

> 依据：`docs/IO链路重构需求清单.md`（下称「需求」）。本文只排期与分工，不重复需求内容。
> 审查口径：`minecraft-mod-strict-code-review`（架构层 / 状态归属 / 依赖方向 / 生命周期 / 失败语义）。
> 状态：**待确认**（第 4 轮，已过一轮自审）。生成日期 2026-11-11。

---

## 0. 一句话结论

先**把主线快进、迁出支线、打完成线 tag**，再按「**收观测 → 立机器状态与表单 → 统一原因/账本/Digest → 合并写入口 → 换规划器 → 队列落档 → 收视图**」七步走。每步以「可编译 + 可进游戏验收 + 可整体 revert」为边界。

三条贯穿全文的判据：

1. **机器状态是世界的镜像，执行状态是执行层的判断。** 机器状态只回答「它在不在」，不回答「它这轮跑没跑」。
2. **先有表单，执行层才不认识节点。** 表单把「一条条规则 + 机器」直接交下来，执行层不需要规则容器；`io → node` 剪断之后，写入口才能放 `io` 而不成环。
3. **原因只回答「一次机器侧搬运尝试为什么是这个结果」，它属于账本行。** 世界态（在不在）不写进原因。

---

## 1. 现状基线（实测 2026-11-11 22:40）

| 项 | 数值 | 出处 |
|---|---|---|
| IO 链路相关文件 | 74 | 需求 §1.1（**P0 要落成文件清单**，否则 P7 指标无法核对） |
| 总行数 | 10695（io 2050 / node 3992） | 同上 |
| 「不搬」出口 | 70（io 28 = return 0L×8 + continue×18 + return false×2；node 42 = 1/19/22） | 本次 grep 复核 |
| 每 tick 全表遍历 | 3 趟 | 需求 §1.1 |
| PROTOCOL_VERSION | `33` | `network/ModNetwork.java:43` |
| 观测预算 | 64/tick（硬上限 256） | `FactoryLimitsConfig.java:37,32` |
| 睡眠 | 连续 100 tick 没搬动 → 每 200 tick 醒 | `RunnerState.java:26,28` |
| 回收节奏 | 活跃 5 tick；空闲退避 20/40/80/128；活跃窗口 200 | `ProviderReturnSweep.java:28-32` |
| 分支关系 | `1.21.1neoforge` 落后 `slim-20261110` **45 个提交**（0 / 45，可纯快进） | `git rev-list --left-right` |
| 未跟踪文档 | 需求清单、草稿 19、本计划 三份 | `git status` |
| tag 现有风格 | `pre-slim`、`pre-blockkey`、`refactor-blockkey`、`phase1-20261027` | `git tag` |

包依赖现状（仅列相关边）：

```
io   → machine(8)  storage(7)  capability(3)  observation(2)  node(2)  location(2)
node → machine(13)  location(8)  probe(5)
io   → node 的两条边：MachineIoRunner→NodeIoGroup、NodeIoRunner→Node
node → io 目前不存在
```

目标终态依赖（无环）：

```
factory(root) → io → {machine, storage, capability}
factory(root) → node → {io, machine}
io 不依赖 node（P2 起永久成立）
```

---

## 2. 已定决策

| # | 决策 | 影响 |
|---|---|---|
| A1 | **机器状态只有 4 个值**：RUN（方块在、已加载、被配置引用）/ GONE（方块没了）/ UNLOADED（区块没加载）/ UNSEEN（还没探到，会自愈）。**没有 SLEEPING。** | `MachineStatus` 定型 |
| A2 | **睡眠属执行状态**：`idleTicks`（连续空转）+ `lastAttemptAt`（上次尝试）；规划器每 tick 用「现在 − 上次尝试」算「这轮跑不跑」；界面「休息中（N tick 后再试）」读执行状态。 | `PassivePlanner` + `UnitDigest`；`MachineState` 不含睡眠 |
| A3 | **七个词**：Form / Unit / Planner / Executor / Ledger / Digest / Analyzer（Digest 是历史结论，Analyzer 是 Diag 的继任者） | 后缀表与类名定型，`History` 不再使用 |
| A4 | **写入口放 `factory/io/MachinePortResolver`**（方案 A），让 `node → io`。前置不是新建规则包，而是 **P2 用表单剪断 `io → node`**。 | 取消「规则下沉」阶段 |
| A5 | **待写入队列落档、跨重启保留**。理由：pushPattern 回 true 时材料已从 ME 网络抽走，AE2 合成任务本身存档；队列丢失 = 任务永久卡死。 | P6 存档字段重写、恢复语义 |
| A6 | **支线命名与现有支线一致**：`io-refactor-20261111`；tag 跟现有风格：起点 `pre-io-refactor`，阶段末 `io-p1`…`io-p7`。 | P0 |
| A7 | **原因清单 = 账本行上的标签，只归执行层，确认 8 种**（你本轮确认）。主动侧的失败不写单元账本，写 `NodeDigest`。 | P3 |

A5 的恢复语义（原话）：

```
队列落档，跨重启保留；
加载后立即参与投递；
机器在 → 送；机器没加载 → 等；机器被删 → 退回网络，退不回才丢。
```

---

## 3. 目标类划分与职责分配

### 3.1 新增的类

| 包 | 类 | 职责（一句话） | 阶段 |
|---|---|---|---|
| `factory.machine` | `MachineStatus` | 4 值枚举（A1），穷尽 switch | P2 |
| `factory.machine` | `MachineSignal` | 写入信号：`OBSERVED` / `CONFIG_CHANGED` | P2 |
| `factory.machine` | `MachineState` | 单台机器状态，唯一写入点 `apply(MachineSignal)`；**重算而非增量**；不写档 | P2 |
| `factory.machine` | `MachineStateLibrary` | 状态集合（byId O(1)），随变更集驱动、随机器删除释放 | P2 |
| `factory.io` | `IoUnitKey` | 单元身份 = 机器 id + 方向 + RuleKey | P2 |
| `factory.io` | `IoUnit` | 表单上一行：身份 + 内联事实 + 账本 + Digest；**每 tick 刷新事实**（见 §3.6 风险） | P2 |
| `factory.io` | `IoForm` | 本 tick 唯一工作清单；只含 `MachineStatus.RUN` 且有规则的单元 | P2 |
| `factory.io` | `Reason` | 一次搬运尝试的结果原因（8 值，§3.3） | P3 |
| `factory.io` | `IoLedger` | 环形有界变化序列（8 行），连续同结果合并 | P3 |
| `factory.io` | `LedgerAnalyzer` | 只读账本、增量维护 Digest | P3 |
| `factory.io` | `UnitDigest` | 单元结论：lastAttemptAt / lastSuccessAt / lastFailureAt / idleTicks / rejectStreak / reasonCounts / faceHint | P3 |
| `factory.io` | `MachinePortResolver` | 机器侧唯一写入口：配置面优先 → faceHint 兜底 → 探面；模拟 → 写入 → 退款 | P4 |
| `factory.io` | `IoExecutor` | 无跨轮记忆执行器：给一条执行单就搬一次 | P4 |
| `factory.io` | `PassivePlanner` | 决定表单上哪些单元这轮跑、什么顺序、用哪个面 | P5 |
| `factory.node` | `NodeDigest` | 节点级结论：pendingCount / pendingOldestAt / entryAttempts / entryLastError / deliveredTotal | P3 |
| `factory.node` | `ActivePlanner` | 主动规划器：派单队列、回收择时；产出本 tick 投递/回收清单 | P5 |
| `factory.node` | `PendingOrder` | 一整单（全部资源与数量、目标机器、绑定面），**落档** | P6 |
| `factory`(root) | `IoFormBuilder` | 世界事实 + 配置事实 → `IoForm` | P2 |

### 3.2 删除 / 合并

| 类 | 处置 | 阶段 |
|---|---|---|
| `probe/BlockObservation` | 删（投影层，每 tick 重建两份） | P1 |
| `io/RouteTable` | 删（D10） | P4 |
| `io/RunnerState` | 拆：睡眠/择时迁入 `PassivePlanner` | P4/P5 |
| `io/RuleResolver` + `node/ProviderFaceResolver` | 合并为 `MachinePortResolver` | P4 |
| `io/MachineIoRunner` | 拆：`IoExecutor` + `ItemCandidates` + `MachinePortResolver` | P4 |
| `io/NodeIoRunner` | 退化为薄驱动（表单 → 规划器 → 执行器） | P2/P5 |
| `io/DiagStats` | 删，字段由账本 + Digest 提供 | P7 |
| `node/ProviderOverflow` | 并入整数份投递 | P6 |
| `node/NodeHealthResolver` + `NodeStatusResolver` | 合一（视图层一条派生链） | P7 |
| `io/IoResult.Reason` | 删，换 `Reason` | P3 |

**原地不动**：`node/NodeIoGroup`、`machine/ResourceSpec`、`machine/MatchMode`、`io/RuleKey`、`io/IoSide` 都不搬包（理由见 §3.5）。

### 3.3 原因枚举：8 值（A7 已确认）

```
RUN          这一轮正常执行（搬了 / 跑了没量，有无量看账本 amount）
SLEEPING     执行层择时判定本轮不跑
NO_FACE      探不到可用的面
BUDGET_OUT   候选/探测预算耗尽，本轮没排上
NO_MATERIAL  仓库或机器没有这个资源
FULL         机器或仓库满了
REJECTED     有容量却不收（面不对），当场清 faceHint
ERROR        异常；退款失败也归这里
```

**归属**：`Reason` 是「一次机器侧搬运尝试」的结果，被动与主动都产生它；但**账本只给表单单元（被动）**。主动侧的结果进 `NodeDigest.entryLastError`，不进单元账本——主动侧没有表单单元。

**与需求 §12 的差异**：§12 候选列表里的 GONE / UNLOADED / UNSEEN 属机器状态（A1），不进原因。

### 3.4 后缀与 AGENTS.md 修改项

七个角色词 AGENTS.md §2.1 的固定后缀表里都没有：`Form` · `Unit` · `Planner` · `Executor` · `Ledger` · `Digest` · `Analyzer`。这是**唯一一处** AGENTS.md 修改项，包表不动。

### 3.5 为什么不新建规则包（A4 前置的判定）

判据不是「谁用规则」，而是「谁依赖谁」：

1. 规则是**配置事实**（玩家写的、要存盘），归节点；执行层只读，不拥有。
2. 现在 `io → node` 的两条边，都是因为执行层拿到了**容器**：`MachineIoRunner` 收 `NodeIoGroup`；`NodeIoRunner` 收 `Node`。
3. 表单把两者拆开：`IoUnit` 内联「一台机器 + 一条规则」。执行层拿**单条规则**，不需要容器。
4. 于是 `io → node` 在 P2 自然消失；P4 写入口放 `io`，`node → io` 单向，不成环。
5. 新建 `factory.rule` 只是搬家，概念数没减少，还要改 AGENTS 包表——不是必要动作。

**顺序约束**：表单必须排在写入口前面（P2 → P4）。

### 3.6 状态归属表（自审补充）

| 状态 | 谁创建 | 谁写 | 谁读 | 活多久 | 写档 | 线程 |
|---|---|---|---|---|---|---|
| `Observation`（方块事实） | `ObservationService` | 只有 `ObservationService.probe` | 表单生成、视图、节点健康 | 与位置同寿命 | 否 | 主线程 |
| `MachineState`（机器状态） | `MachineStateLibrary` | 只有 `MachineState.apply` | 表单生成、主动规划器、视图 | 机器在配置中存在期间 | 否 | 主线程 |
| `IoForm` / `IoUnit`（工作清单） | `IoFormBuilder` | 每 tick 重建（复用单元） | 规划器、执行器、视图 | 本轮 | 否 | 主线程 |
| `IoLedger`（账本） | `IoUnit` | 执行器追加 | Analyzer、视图 | 单元在表单期间 | 否 | 主线程 |
| `UnitDigest`（单元结论） | Analyzer | Analyzer 增量更新 | 规划器（只读）、视图 | 单元在表单期间，离表即释放 | 否 | 主线程 |
| `NodeDigest`（节点结论） | Analyzer | Analyzer | 主动规划器、视图 | 节点存在期间 | 否 | 主线程 |
| `PendingOrder`（队列） | `NodeProvider` | `NodeCraftService` 入队 / `ActivePlanner` 消费 | 投递、视图 | 跨重启 | **是** | 主线程 |
| 睡眠/择时 | `PassivePlanner` | `PassivePlanner` | 自身 | 运行时 | 否 | 主线程 |

**线程边界**：以上全部只在服务端主线程读写，交接点为零；存档只在 `ServerStoppingEvent` 与显式 `markDirty` 时发生。

**在表单之外的机器怎么显示**：单元离表即释放账本与 Digest，所以**节点卡片结论的优先级必须是「机器状态聚合 > 结论聚合」**——机器 UNLOADED 时显示「离线/未加载」，不许因为 Digest 为空而显示「空闲」。（P7 验收项）

### 3.7 实施方分工

| 阶段 | Lead 亲自 | 子代理 |
|---|---|---|
| P0 | 分支流程与 tag | — |
| P1 | `ObservationService`/`Observation` 单出口与时序 | 删投影层的机械替换、调用点核对 |
| P2 | 表单数据结构、`IoFormBuilder`、tick 两段化、剪 `io → node` | `MachineState` 族样板 |
| P3 | 原因取值与 70 出口映射审定（区分被动账本 / 主动结论） | 协议/界面同步 |
| P4 | `MachinePortResolver` 选面骨架 | `RouteTable` 删除的调用点扫尾 |
| P5 | 退避表、游标、回收择时 | — |
| P6 | 队列条目、整数份投递、恢复语义 | 存档字段重写 |
| P7 | 派生链合一 | 快照字段与客户端同步 |

> 沿用 AGENTS.md §6.2 #19：方向先写死再派活，不造兼容层；机械类型替换用 bash 批量替换 + 编译器收敛。

---

## 4. 阶段总览

| 阶段 | 主题 | 文件量 | 行为变化 |
|---|---|---|---|
| P0 | 分支准备 | 文档 3 份 | 无 |
| P1 | 观测层收口 | 8 改 / 1 删 | 无 |
| P2 | 机器状态 + IO 表单（并剪断 io → node） | 8 新 / 8 改 | 无（纯结构） |
| P3 | 原因枚举 + 账本 + Digest + 分析器 | 7 新 / ~20 改 | 无（可见信息更全） |
| P4 | 合并机器侧写入口 + 删 RouteTable | 1 新 / 8 改 / 2 删 | 无（面选择等价） |
| P5 | 规划器 + 规则级排期 | 2 新 / 4 改 / 1 删 | **有**（唯一允许） |
| P6 | 主动队列落档 + 回收择时 | 2 新 / 6 改 / 1 删 | 有（派单延后一 tick，队列跨重启） |
| P7 | 视图层收口 + 清理 | 2 新 / 6 改 / 3 删 | 无 |

依赖：P0 → P1 → P2 → {P3, P4} → P5 → P6 → P7。

---

## 5. 各阶段明细

### P0 分支准备（不改代码，只动 git）

**步骤**

1. 在 `slim-20261110` 上提交三份文档。
2. `git checkout 1.21.1neoforge && git merge --ff-only slim-20261110`。
3. `git checkout -b io-refactor-20261111`。
4. `git tag pre-io-refactor`（跟 `pre-slim` 一致）。
5. `./gradlew build`，基线落 `.tmp/io-refactor-baseline.md`：74 个文件的**清单**、总行数、70 出口的位置清单、3 趟遍历位置、100 台机器 tick 耗时。

**验收点**
1. `git rev-list --left-right --count 1.21.1neoforge...slim-20261110` = `0 0`。
2. 当前分支 `io-refactor-20261111`，tag `pre-io-refactor` 在起点。
3. `git status` 干净。
4. `build` 通过；基线文件含**可逐个核对的 74 文件清单**（P7 指标靠它）。

**回滚**：只回滚支线（`git checkout slim-20261110` + 删支线/tag）。**主线快进不回滚**——它只是把一个已存在的提交序列指到主线，撤它会让主线丢 45 个提交，风险远大于收益。

**风险**：无远程仓库；唯一不可找回的是误删支线。

---

### P1 观测层收口：事实一份、通知一个出口

**改哪些文件**

| 文件 | 改动 |
|---|---|
| `observation/ObservationService.java`（465） | 一趟遍历：收候选（脏 ∪ 引用位置）→ 预算内探测 → 产出 `List<BlockChange>`；`drainChanges()` 替代 `drainTransitions()` |
| `observation/Observation.java`（208） | 字段不动；注释：`lastBlockId` 只供视图分组 |
| `probe/BlockObservation.java`（66） | **删** |
| `FactoryObservationRunner.java`（199） | 删投影与 `snapshotBlocks`；保留候选分桶顺序；`handleTransition` → `handleChange` |
| `FactorySnapshotBuilder.java`（412） | 删 `buildBlockObservations`，直读 `Observation` |
| `node/NodeHealthResolver.java`（153） | 入参改直读 `Observation` |
| `node/NodeTypeResolver.java`（69） | 同上 |
| `FactoryNodeRunner.java`（123） | reconcile 的 available 改读新数据源 |

**与需求 §7.1 的偏差（需你认）**：需求要求「dirty / Transition / generation / 监听器合并成一条」。本阶段只合并前两条；`generation` 留到 P4 与 `RouteTable` 一起删（先删一半会留死参数），能力监听器保留（它是「缓存失效入口」，不是「通知出口」）。

**验收点**
1. `build` 通过；`grep -r BlockObservation src` 为空。
2. 三态显示正常；失联通知 10 秒节流；`UNLOADED` 不进失联通知。
3. 同存档 5 分钟，搬运计数与 P0 同量级；观测开销无退化。
4. `observation` + `probe` 行数下降 ≥ 200。

**回滚**：revert 或 `reset --hard pre-io-refactor`。

**风险**：`NodeHealthResolver` 入参是两解析器共用签名，必须一次改齐；`probed`/`isStale` 判据照抄。

---

### P2 机器状态 + IO 表单（并剪断 `io → node`）

**改哪些文件**

| 文件 | 改动 |
|---|---|
| 新增 `machine/MachineStatus.java` | 4 值；注释写清「世界镜像，不表达跑没跑」 |
| 新增 `machine/MachineSignal.java` | `OBSERVED` / `CONFIG_CHANGED` |
| 新增 `machine/MachineState.java` | 唯一写入点 `apply(MachineSignal, Observation)`；**由完整输入重算，不依赖信号顺序** |
| 新增 `machine/MachineStateLibrary.java` | byId；观测变更集 + 配置变更驱动；不再被引用的机器在这里释放 |
| 新增 `io/IoUnitKey.java`/`IoUnit.java`/`IoForm.java` | 单元 + 表单；单元复用、**每 tick 刷新内联事实** |
| 新增 `IoFormBuilder.java` | 观测 + 配置 → 表单；过滤：无规则 / 非 RUN |
| 改 `FactoryTickRunner.java`（86） | 两段化：准备（观测 → 仓库 → 机器状态 → 表单）→ 执行（回收 → 队列 → 被动） |
| 改 `io/NodeIoRunner.java`（270） | 只遍历表单；**删 `factory.node.Node` 依赖**；睡眠暂留 `RunnerState` |
| 改 `io/MachineIoRunner.java`（348） | `runBlock` 收 `IoUnit`；**删 `NodeIoGroup` 入参** |
| 改 `FactoryRuntime.java`（347） | 装配 `MachineStateLibrary`/`IoFormBuilder` |

**关键细节（自审发现）**：`RuleKey` 不含 `amount`（`RuleKey.java:21-22` 只有 input/key/face/mode）。因此玩家只改规则数量时，单元 key 不变、单元被复用——**若不在每 tick 刷新内联事实，执行器会一直用旧数量**。两条约束：单元每 tick 用当前 `ResourceSpec` 刷新；`amount` 变化不重建单元。

**职责边界**
- 机器状态只回答「在不在」；睡眠、拒收、计数在执行状态（A2）。
- 表单生成放 root：它是「观测 + 配置」唯一汇合点。
- 单元跨 tick 复用（1024 台 × 16 规则 ≈ 1.6 万单元），每 tick 只 mark-seen/add/drop。

**验收点**
1. `build` 通过；`grep -rn "factory.node" factory/io/` 为空。
2. 行为等价：搬运计数、睡眠节奏、失联停摆与 P1 一致。
3. 四态可验：RUN / GONE / UNLOADED / UNSEEN（新加机器首次探测前 UNSEEN，自愈为 RUN）。
4. **改数量专项**：同一条规则把数量从「塞满」改成固定 8，下一轮搬运量即为 8（验证 §3.1 的刷新约束）。
5. 表单正确性：删机器 / 拔方块 / 卸载区块后单元消失；表外单元不产生每 tick 的「门禁 return 0L」。
6. `grep` 确认 `MachineState` 字段除 `apply` 外无写入点；无 `sleeping`/`idleTicks` 字段。
7. 每 tick 新建对象数与 P1 同量级。

**回滚**：revert 到 P1 tag（无存档、无协议改动）。

**风险**：签名变化会让全包短期编译不过——允许，但一次提交改到能编译。表单过滤写错会静默停搬，验收 2/3 必须实测。

---

### P3 原因枚举 + 账本 + Digest + 分析器

**改哪些文件**

| 文件 | 改动 |
|---|---|
| 新增 `io/Reason.java` | 8 值（§3.3） |
| 新增 `io/IoLedger.java` | 环 8 行：(Reason, firstTick, lastTick, count, amount, face)；连续同原因同面合并 |
| 新增 `io/LedgerAnalyzer.java` | 只读账本、增量维护 Digest |
| 新增 `io/UnitDigest.java` | 值对象；后缀语义固定 At / Ticks / Streak / Counts / Hint |
| 新增 `node/NodeDigest.java` | 节点级结论（主动侧失败与回收结果都记这里） |
| 改 `io/IoResult.java` | 删内嵌 `Reason` |
| 改 `io/IoTransfer.java`（107） | 映射：OK→RUN、NO_ITEM→NO_MATERIAL、NO_SPACE→FULL、NO_HANDLER→NO_FACE、REJECTED→REJECTED、退款失败→ERROR |
| 改 70 处出口 | **分开落点**：被动侧 → 单元账本；主动侧（接单/投递/回收/余量）→ `NodeDigest` |
| 改 `io/DiagStats.java`（220） | 只留速率采样，逐规则原因立即删（P7 整类删） |
| 改 `FactorySnapshotBuilder.java` | 字段：reason / lastSuccessAt / idleTicks / rejectStreak / faceHint / 最近 8 行账本；删 sinceMoveTicks 旧语义 |
| 改 `ModNetwork.java` | PROTOCOL_VERSION 33 → 34 |
| 改 `FactoryTableS2C`（336）、`NodeStatusCodec`（59） | 字段重排，写读顺序同步 |
| 改 `FactoryLabels`、`FactoryPassiveIoTooltip`、`FactoryPassiveIoRender` | 原因文案、状态条、睡眠倒计时 |

**职责边界**：Analyzer 维护账本与 Digest、只读账本、不做判断；Planner 只读 Digest；Executor 只写账本。

**验收点**
1. `build` 通过；`grep -rn "return 0L;\|return false;" io/ node/` 剩余 ≤ 12，每处注释说明为何不产生原因。
2. 界面：不动的原因 = 机器状态 + 单元原因，一处可读；睡眠显示「休息中（N tick 后再试）」。
3. 账本合并：连续 3 轮同原因同面 → 一行 count=3；原因变化 → 新行；8 行满滚最旧。
4. Digest：lastSuccessAt 与实测成功 tick 一致；idleTicks 单调；REJECTED 后 faceHint 就地清除。
5. 重启语义（新补）：执行结论不写档 → 重启后所有单元从零开始、首轮全速尝试一次；这是可接受观感，写进测试记录。
6. 协议同版本可连；`DiagStats` 无逐规则原因字段。

**回滚**：revert 到 P2 tag；协议需客户端同步回滚。

**风险**：70 出口一次收敛，diff 大；按「文件 + 现行语义 + 目标落点（账本/节点结论）+ 目标原因」四列清单逐个过。协议字段一次改完。

---

### P4 合并机器侧写入口 + 取消 RouteTable

**改哪些文件**

| 文件 | 改动 |
|---|---|
| 新增 `io/MachinePortResolver.java` | 唯一机器侧入口：配置面优先 → faceHint 兜底 → 探面；物品先无向、不可用再逐面；流体/FE 按面；模拟→写入→退款原样搬入 |
| 删 `io/RouteTable.java`（109） | D10 |
| 删 `node/ProviderFaceResolver.java`（281） | 合并入上者；两套后端（AE2 `PatternProviderTarget` / NeoForge `Capabilities`）留在内部 |
| 改 `io/RuleResolver.java`（289） | 下线 |
| 拆 `io/MachineIoRunner.java` | → `IoExecutor` + `ItemCandidates` |
| 改 `NodeIoRunner`/`RunnerState` | 删 routes 与 `forgetRoutes` |
| 改 `ObservationService` | 删 `noteRejected`、`facesGeneration` |
| 改 `capability/FaceCache.java`（93） | 删 generation；保留失效链 |
| 改 node 侧 | `NodeCraftService`/`ProviderOverflow`/`ProviderReturnSweep`/`NodeRuntime` 改调 `MachinePortResolver` |

**职责边界（自审补充）**：写入口在 `io`，不能读节点规则。**由调用方把配置面传进来**：被动侧 `ResourceSpec.face` 由表单携带；主动侧 `ActivePlanner` 负责把样板输入映射到该资源在节点里的规则、取出配置面，再交给 resolver。resolver 只认「资源 + 想要的面向（可空）」。

**验收点**
1. `build` 通过；`grep -rn "RouteTable\|facesGeneration\|noteRejected" src` 为空。
2. 六个用例：物品输入无向只读→逐面；显式配面→只用该面；REJECTED→清 faceHint 下轮重探成功；流体按面；FE 探面 1000 不被截断；主动与被动同机同面不打架。
3. 六面遍历只剩一处实现。
4. 依赖方向：`node → io` 存在，`io` 不 import `factory.node`。

**回滚**：revert 到 P3 tag。风险最高的一步，回滚前跑完六个用例。

**风险**：容易顺手简化掉物品「无向→逐面」回退（AGENTS §6.2 #6）。`ProviderFaceResolver` 的「一次派单共用面快照」保留为调用方可选。

---

### P5 规划器 + 规则级排期（唯一允许的行为变化）

**改哪些文件**

| 文件 | 改动 |
|---|---|
| 新增 `io/PassivePlanner.java` | 择时（退避表）、游标、阻挡；只排这一 tick |
| 新增 `node/ActivePlanner.java` | 主动侧：派单投递清单 + **回收择时**（接管原 `ProviderReturnSweep` 的 5/200/退避参数，不删节奏） |
| 删 `io/RunnerState.java`（139） | 睡眠/择时迁入 `PassivePlanner`，按**单元**计时 |
| 改 `io/NodeIoRunner.java` | 退化为薄驱动：表单 → 规划器 → 执行器 |
| 改 `FactoryTickRunner.java` | 执行段顺序固定：回收 → 队列 → 被动 |

**退避表**

| 上一轮原因 | 下次最早尝试 | 理由 |
|---|---|---|
| RUN（有量） | 下 tick | — |
| NO_MATERIAL | +5 tick | 仓库无事件（D15），只能定时抬头 |
| FULL | +10 tick | 等机器自己消耗 |
| REJECTED / NO_FACE | +20 tick | 探面贵；同 tick 已清 faceHint |
| BUDGET_OUT | 下 tick | 预算只影响探面 |
| SLEEPING | 唤醒时刻 | 有量或配置变更立即醒 |
| ERROR | +20 tick，按 rejectStreak 翻倍封顶 100 | 坏 handler 不许刷满日志 |
| GONE / UNLOADED / UNSEEN | 不进规划器 | 已在表单外 |

**回收择时（自审补充）**：原 `ProviderReturnSweep` 有「活跃 5 tick / 空闲退避 20·40·80·128 / 活跃窗口 200」——这不是可以删的花活，是扫面成本的来源。本阶段**把参数搬进 `ActivePlanner`**，决策权从 sweep 自维护改成规划器统一择时；不许变成「每 tick 全量回收」。

**游标**：按规则在清单里的稳定位置轮转；玩家调整规则顺序时游标从当前位继续，不重置（否则顺序一变就永远只跑第一条）。

**验收点**
1. `build` 通过。
2. **规则级排期专项**：同机两输入规则，A 缺料、B 有料 → B 照常搬（P4 及之前会整机睡 10 秒）。
3. 睡眠仍生效：全部无料时整机 SLEEPING，界面可读；配置变更立即唤醒。
4. 游标公平：4 条输出规则轮转，每轮各一次。
5. 回收节奏不回退：空闲时扫面间隔不小于原退避阶梯，活跃期仍是 5 tick；`tick` 耗时对比 P0 无数量级恶化。
6. 1024 台 tick 耗时对比 P0 基线。

**回滚**：revert 到 P4 tag。**不需要重建世界**（无存档结构变化；仓库余量观感差异随运行自然收敛）。若之前已按 P6 落过档，则另算。

**风险**：唯一改观感的阶段，容易被验收当成 bug；提交信息与验收清单写明「预期行为变化」。

---

### P6 主动队列落档 + 回收

**改哪些文件**

| 文件 | 改动 |
|---|---|
| 改 `node/NodeCraftService.java`（324） | `pushPattern` = 校验（忙/样板归属/阻挡/在线机器/能选面）→ 整单入队 → true |
| 新增 `node/PendingOrder.java` | 一整单：全部资源与数量、目标机器、绑定面（可空）、入队 tick；可存可读 |
| 改 `node/NodeManager.java`（304） | 每 tick 消费队列：按「能完整做几份」n 投递，n=0 不写；不退避；按 `MachineStatus` 决定送/等/退回 |
| 改 `node/NodeProvider.java`（430） | 存档从「零头余量」改「整单」；`MAX_PENDING=64` 保留为订单数上限；落档 |
| 删 `node/ProviderOverflow.java`（236） | 余量并入整数份投递；`dropAtMachine` 的 `Block.popResource` 删（D21） |
| 改 `node/ProviderReturnSweep.java`（222） | 回收 = 选面 + 模拟（共用 `MachinePortResolver`）；节奏参数移交 `ActivePlanner` |
| 改 `node/NodeRuntime.java`（532） | 删 flush；pickMachine 移交 `ActivePlanner`；tick() 的 boolean 语义重定义 |
| 改网络 | `FactoryNodeTableS2C`/`NodeStatusCodec` 加 pendingCount/pendingOldestAt/entryAttempts/entryLastError；PROTOCOL_VERSION +1 |

**存档与恢复语义（A5）**

```
存档：NodeProvider 的 Pending 列表改写为「整单」列表，随节点 NBT 落盘（键沿用 Pending）。
加载：Node.load → NodeProvider.load 原样恢复；不等待、不延迟，下一 tick 即参与投递。
投递时按目标机器状态三选一：
  RUN                    → 送
  UNLOADED / UNSEEN      → 等（每 tick 都试，不退避，D19）
  GONE / 目标不在成员     → 退回 ME 网络
退回网络用 AeKeySupport.insert；网络离线（= 空仓库）时退回失败
  → §9 缺口：默认「保留为孤儿订单、每 tick 重试、超 1200 tick 才丢弃 + 日志」
```

**自审补充（删除路径）**：玩家删除机器或删除整个节点时，队列里的订单必须先退回网络，退不回按孤儿处理——否则「配置一删，材料凭空蒸发」。这一条必须在 P6 一并处理，不能只做重启恢复。

**职责边界**：AE2 只跟节点层对话；接单门禁在节点层（D20）；队列不退避（D19）；主动尽量塞满，不让位。订单**自包含**：不依赖样板仍在。

**验收点**
1. `build` 通过。
2. 派单后下一 tick 投递；单机与多机轮询各一例。
3. n=0 不写：只剩半格空间时订单整单留队。
4. **落档**：写入订单 → 关服 → 重启 → pendingCount 恢复 → 下一 tick 投递成功。
5. **恢复三态**：目标机器被删 → 退回网络；目标区块未加载 → 等待、加载后投递；网络离线 → 孤儿处理，不丢、不生成掉落物。
6. **删除路径**：删机器 / 删节点时订单退回网络。
7. `grep -rn popResource node/` 为空。
8. 回收正常，不影响被动。

**回滚**：revert 到 P5 tag + **重建世界**（`NodeProvider` 存档语义变了）。

**风险**：`NodeRuntime.tick()` 的 boolean 返回值被 `NodeManager` 用来汇总落盘，改队列后要用「队列版本号」判断是否写盘。旧格式必须先识别再丢弃，不许误读成整单。

---

### P7 视图层收口 + 清理（收指标）

**改哪些文件**

| 文件 | 改动 |
|---|---|
| 改 `node/NodeHealthResolver` + `NodeStatusResolver`（137）+ `NodeStatus` | 合成一条派生链（成员机器状态聚合 → 节点健康 → 节点状态） |
| 改 `FactoryObservationRunner`/`FactoryNodeRunner` | 删自算 available，改读这条链 |
| 删 `io/DiagStats.java`（220） | 速率与原因全由账本/分析器提供 |
| 改 `FactorySnapshotBuilder`（412）+ `FactoryNodeSnapshotBuilder`（156） | 字段来源改为三份真相；节点卡片三段（在线/部分/离线 + 机器数/规则数 + 正在搬/等料/堵塞/空闲） |
| 改 `FactoryPassiveIoModel`/`FactoryNodeModel`/`FactoryStatus` | 删客户端本地聚合 |
| 改 `*Render`/`*Tooltip` | 折叠展开账本 |
| 改 `package-info` 等 | 描述对齐 |

**验收点**
1. `build` 通过。
2. 指标：文件 74 → 约 45；行数 10695 → 约 6000；出口 70 → 约 12（对照 P0 的 74 文件清单逐项打勾）。
3. 无重复真相：`grep` 确认「在线/离线」只有一处计算、「健康过期」只有一处计算。
4. 节点卡片优先级：机器 UNLOADED/GONE 时显示世界态，不因 Digest 为空显示「空闲」。
5. 视图不存：`NodeStatus` 按请求现算；客户端不跑服务端聚合。

**回滚**：revert 到 P6 tag；协议字段若已删需客户端同步回滚。

---

## 6. 跨阶段红线

1. **行为等价**：P1–P4、P7 是重构；行为变化只允许出现在 P5、P6。
2. **唯一真相**：`Observation` 是方块事实唯一一份；`MachineState` 是机器状态唯一写入口；账本 → Digest 是执行状态唯一聚合路径。
3. **依赖方向**：`io` 不依赖 `node` 从 P2 起永久成立。
4. **机器状态不表达执行**：`MachineStatus` 出现 SLEEPING 或睡眠字段即违规。
5. **不加兼容层**：不留过渡方法（AGENTS §6.2 #19）。
6. **提交纪律**：编译通过即提交；阶段末 tag `io-pN`；不提交 run/、build/、.tmp/。
7. **注释与常量**：中文 Javadoc；上限/间隔/容量具名常量，解码侧校验。

---

## 7. 回滚总表

| 阶段 | 回滚动作 | 需重建世界 |
|---|---|---|
| P0 | 只回滚支线与 tag，主线不动 | 否 |
| P1 | `reset --hard pre-io-refactor` / revert | 否 |
| P2 | revert 到 P1 tag | 否 |
| P3 | revert 到 P2 tag + 客户端同步 | 否 |
| P4 | revert 到 P3 tag | 否 |
| P5 | revert 到 P4 tag | **否**（仅观感差异） |
| P6 | revert 到 P5 tag | 是（存档语义变了） |
| P7 | revert 到 P6 tag | 否 |

---

## 8. 需要你确认的 2 件事

1. **P1 与需求 §7.1 的偏差**：需求要求四路通知合并成一条。本计划只在 P1 合并 dirty + Transition；`generation` 留到 P4 随 `RouteTable` 一起删，能力监听器保留（它是缓存失效入口，不是通知出口）。理由：先删一半会留下死参数。是否接受这个分批。
2. **UNLOADED 抖动会重置结论**：单元离开表单即释放账本与 Digest（需求 7.6 定的）。玩家跨区块边界时机器短暂 UNLOADED 再回来，会从零开始——faceHint 丢、idleTicks 清零、退避重置，表现为「回来第一轮全速重探面」。是否接受；不接受就要给单元加「离表后保留 N tick」的宽限，那是需求层面的改动。

---

## 9. 计划自身的缺口（先说明）

- **P6 退回网络失败**：仓库离线 = 空仓库（AGENTS §6.2 #9），「退回网络」必然失败，而 A5 只说「退不回才丢」。默认「孤儿订单每 tick 重试、超 1200 tick 丢弃 + 日志」，**这是我的判断**。要「永不丢弃」需单独计数与不设上限，工作量增加。
- **性能判据只能是相对值**：需求不设每 tick 硬指标（§9）。
- **P0 基线文件清单必须落**：否则 P7 的「74 → 45」无从核对。
- **主动订单与样板解耦后的一致性问题**：订单自包含意味着样板被删也照常投递；若你的本意是「样板删了就该退单」，这里要改。

---

## 10. 执行记录（2026-11-11 完成）

分支 `io-refactor-20261111`，起点 tag `pre-io-refactor`，阶段 tag `io-p1` … `io-p7`。
每个阶段都以 `gradlew build` 通过后提交，共 11 个提交。

| 阶段 | 实际落地 | 与计划的差异 |
|---|---|---|
| P0 | 文档提交、主线快进、迁支线、打 tag、基线指标 | tag 名按现有风格用 `pre-io-refactor` / `io-pN` |
| P1 | 删 `BlockObservation`，`Observation` 实现 `BlockFacts`，`drainChanges` 单出口 | `generation` 按计划留到 P4 随 `RouteTable` 删 |
| P2 | `MachineStatus/MachineSignal/MachineState/MachineStateLibrary`、`IoForm/IoUnit/IoUnitKey/IoFormBuilder`，两段化，删 `io→node` 两条依赖 | 无 |
| P3 | `Reason` 8 值替换旧 5 值、`IoLedger`/`UnitDigest`/`LedgerAnalyzer`、`NodeDigest` | 界面协议字段延后到 P7 一次改完（避免两次升协议） |
| P4 | `MachinePortResolver` 合并两套取面，删 `RouteTable`/`RuleResolver`/`ProviderFaceResolver`，面记忆改配置面 + 经验面 | 无 `IoExecutor` 类：执行器仍是 `MachineIoRunner`，职责已收窄 |
| P5 | `PassivePlanner`（规则级退避 + 游标 + 唤醒）、删 `RunnerState`、`ActivePlanner` 接管回收择时 | 无 |
| P6 | `PendingOrder` 整单落档、整数份投递、删 `ProviderOverflow`、孤儿订单超时丢弃 | 无 |
| P7 | `DiagStats` 收缩为 `TransferStats`、视图读账本/结论、节点表暴露待投递、节点对账单链 | 账本明细展开、`NodeDigest` 的投递次数/错误未上界面（需再加协议字段） |

**未完成 / 已知缺口**（不计入本次完成）：

1. 节点 tooltip 只显示待投递单数与最久等待；`NodeDigest` 的投递尝试次数、最近错误、累计投递量已建模但未上界面。
2. 单元账本（`IoLedger.rows()`）未接界面展开——视图只读当前行。
3. 需求 §10.5 的「文件 74 → 45 / 行 10695 → 6000 / 出口 70 → 12」按字面口径未达成：文件数与行数因新增类与视图改造口径不可直接对比；核心六包从 74 文件 / 10695 行变为 65 文件 / 8329 行；「不搬出口」按正则统计仍为 70，但语义已改为「每处都落账本原因」而非静默跳过。

**独立审查（子代理加载严格审查 skill）与修复**：审查给出 4 条「不可提交级」问题，全部已修：

1. 队列增删改不标脏 → 崩服时「投递成功但存档仍是旧队列」导致重启重投复制材料。已让所有队列写入口调 `markDataChanged()`。
2. 多行投递非原子 → 第二行抛异常时第一行已进机器、订单原样留队，下一轮重投复制。已改为逐行 try/catch：当前行按已交付处理（不重投），后续行留回队首。
3. `inserted < want` 差额被静默销毁 → 已把差额按固定数量写回队首，下一轮精确补齐。
4. `IoForm.Slot.endRebuild()` 从未被调用，单元池只增不减、旧单元结论复活 → 已接上槽位级回收。

建议项一并处理：删 `MachineSignal` 假抽象与 `BlockRun` 无读者分量、节点结论收窄到队列深度、速率统计随机器删除清理、严重度表收敛到 `Reason.severity()`、面快照收成单槽位、校正回收/编排注释、删死重载与重复 import。

**最终数字**：核心六包 64 文件 / 8211 行（基线 74 文件 / 10695 行）；`io+node` 的 `return 0L/continue/return false` 共 70 处，但语义已从「静默跳过」改为「每处都落账本原因或只是扫面循环内的`continue`」；协议版本 34。

**仍未做**：单元账本明细展开（`IoLedger.rows()`）未接界面；节点「投递尝试次数 / 最近错误 / 累计投递量」未上界面；`IoExecutor` 未单独成类（执行器仍是收窄后的 `MachineIoRunner`）。

**命名统一（2026-11-11 追加）**：按「数据结构带 Io 前缀、角色用语义前缀+角色后缀、执行入口用 Io+角色」的分族规则，改名三处（纯机械改名，不动行为与协议）：

- `MachineIoRunner` → `IoExecutor`（执行器，无跨轮记忆）
- `NodeIoRunner` → `PassiveIoRunner`（被动腿的驱动，与 `PassivePlanner` 配对）
- `MachinePortResolver` → `IoPortResolver`（机器侧唯一写入口，被动与主动共用）

`AGENTS.md` §6.1 的关键文件表仍列着旧类名（以及已删除的 `RuleResolver` / `RunnerState` / `RouteTable`），按 §5.6 需要单独批准后才能改。

**已知行为（2026-11-12 确认）**：主动合成的任务完成判据由 AE2 按「网络收到的产物数量」记账，不区分来源。因此同一网络里如果有被动产线在产同种物品，计划里的被动输出规则（以及节点级产物回收）写进网络的产物会被记到在途任务上，任务可能提前完成。这是 AE2 语义，不是队列/被动模式冲突；本次按「特性」保留，需要隔离时把被动产线放到子网或独立存储。

**Bug 修复（2026-11-12）**：主动投递曾把「机器剩余容量能装几份」当成「手里有几份」相乘，导致下单 1 份却往机器塞满整格（复制物品）。已改为只按订单里的真实数量投递：整份放得下就写、放不下就一个都不写等下一轮；选面与绑面都要求「能完整收下这一份」。
