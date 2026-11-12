# IO 执行层重构 · 实施计划

> 依据：[需求清单](IO链路重构需求清单.md)、[草稿 19](草稿纸/19-IO链路重构方向.md)、[草稿 20](草稿纸/20-IO执行层命名与职责收敛.md)。
> 状态：**已确认**（第 7 轮，2026-11-12 用户确认，可交给执行代理按 P1 → P4 落地）。生成日期 2026-11-12；修订：单 `IoRunner`、`CapacityPick`、多机平均分与半份记账、卡单忙不退款、订单 id、`dispatchScanLimit`、`portions` 命名、`RuleKey` 换 `IoSide`、`DispatchMode` / `BlockingMode` 类型归 io、端口缓存失败驱动失效；本轮清空待定项。
> 范围：只动执行层（`io` / `node` 的执行部分），不动界面、不动配置层、不做自动输出。
> 执行状态：P1 → P4 已落地（2026-11-12），提交按阶段分开；`AGENTS.md` 的命名后缀 / 坑条修订仍待用户单独批准（见 `docs/DECISIONS.md` 的修订提案）。

---

## 0. 一句话

把执行层收敛成：**规划器调 Pick 产出可执行 Job → IoExecutor 只执行 → Analyzer 记账**，机器侧统一走 `MEStorage`，主动与被动共用同一套执行组件；`node` 只留协议与队列存档。

**线程边界**：所有规划、执行、记账都在服务端主线程；本轮不引入异步。以后真卡了再考虑异步，那是一次独立重构。

---

## 1. 现状 → 目标

| 现状 | 目标 |
|---|---|
| `IoForm` / `IoUnit` / `IoUnitKey` 三个类 | `Form`（内嵌 `Entry` / `Entry.Key`）一个类 |
| `ResourceAdapter` + 三个 Adapter 自己抹平机器接口 | 统一 `MEStorage`（AE2 已抹平物品/流体/FE/化学品） |
| `IoPortResolver` 同时做「扫面」与「建存储」 | 拆成 `FacePick`（选面）与 `PortExecutor`（建） |
| `IoTransfer` 只服务被动 | `IoExecutor.move/fill/refund`，被动与主动共用 |
| `IoExecutor` 一次跑一台机器的全部规则 | `PassivePlanner` 产出逐条 `IoJob`，执行器逐条执行 |
| `IoLedger` / `UnitDigest` / `LedgerAnalyzer` / `TransferStats` 四个类 | `Analyzer`（内嵌 `Ledger` / `Conclusion`）一个类 |
| `PendingOrder` 绑一台机器、投递 0/1 份 | `DispatchOrder` 存 `perPortion + portions + donePortions + openBatches`，按 `DispatchMode` 分到多台机器；剩余量现算 |
| 派单模式只决定“从哪台开始挑”，一单一台机 | 顺序：逐台塞满；轮询：每台 1 份轮转；都跨 tick 继续 |
| `NodeCraftService.pushPattern` 选面并绑定 | 只校验 + 拆份 + 入队，选面移到投递 |
| `NodeCraftService.deliver` 只投队首 | `ActivePlanner` 扫多单、跳过卡单、超时退回 |
| `ActivePlanner` 只管回收节奏 | 真的管投递规划 + 回收择时 |
| 回收在 `node` 包里 | 回收动作并入 `IoExecutor.sweep`（执行层） |
| 执行顺序：主动 → 回收 → 被动（被动本来就在最后） | 回收 → 主动 → 被动 |

---

## 2. 目标类清单

### 2.1 新增 / 改名

| 包 | 类 | 职责 |
|---|---|---|
| `io` | `Form` | 表单；内嵌 `Entry`（一条规则的一轮状态）与 `Entry.Key`（身份） |
| `io` | `MoveResult` | 一次搬运的结果：实际搬动量 + 原因；由 `IoResult` 改名，**不并入 `Reason`**（数量要单独返回） |
| `io` | `IoJob` | 可直接执行的搬运任务：机器、方向、面、资源 key、量上限；执行结果由 `MoveResult` 返回，Job 不带结果 |
| `io` | `DispatchOrder` | **一单待投递的派单**（AE2 派下来、料已在手，属于节点而不属于某台机器）：`id`（UUID）、`perPortion`（每行资源 + 每份用量，**不带面**）、`portions`、`donePortions`（已完整写入机器的份数）、`openBatches`（机器 → `OpenBatch(UUID machineId, long[] missingPerLine)`，当前半份还差多少）、`nextMachineId`（轮询起点）、`retryAt`、入队/孤儿时刻。**每行已写/剩余量由 `donePortions` 与 `openBatches` 现算，不另存 `delivered`** |
| `io` | `DispatchQueue` | **多单排队**：入队 / 按顺序取队首 `dispatchScanLimit` 条 / 出队 / 更新 / 计数；内存与存档的**唯一写入口**。`RefundReport refundAll(MEStorage network, long now)` 供删节点与以后的手动按钮用 |
| `io` | `DispatchState` | **这一 tick 投递需要的输入**：队列（**活的、可改**，只有 `DispatchQueue` 能改）、在线机器、派单模式、阻挡开关、样板输入、产物白名单；不含存储句柄，网络由 Runner 持有（见 2.4 / 2.6） |
| `io` | `FacePick` | 选面：配置面 / 经验面**直接返回，不模拟**；两者都没有才逐面建存储、`insert(SIMULATE)` 探一次。返回 `PickResult`：`face != null` 表示成功、`failure == null`；失败时 `face == null`、`failure` 是 `FACE_CONFIG_INVALID`（玩家配错）或 `NO_FACE`（机器/加载问题）。只读 |
| `io` | `CapacityPick` | 容量查询：`portionsThatFit(机器, perPortion)`，逐行模拟取最小。主动分配与智能倍率共用；智能倍率按节点所有在线机器求和；只读 |
| `io` | `VariantPick` | 模糊规则的变体查询（含 TTL 缓存）；只读 |
| `io` | `PortExecutor` | 执行侧的**只读端口缓存**：`(机器, 面) → MEStorage`，按机器 + 面缓存，**跨 tick 保留**。不做世界检查；失效靠两个入口：动作失败（`REJECTED` / `NO_FACE` / `ERROR` / 异常）时 Runner 调 `invalidate(machine, face)`，机器/节点删除时 `drop(machineId)`；下次取用发现没了就重建。调用者只有 `FacePick` / `CapacityPick` / `IoExecutor`。Runner 只持有转交，不直接调用 |
| `io` | `IoRunner` | 每工厂一个，接心跳；按 回收 → 主动 → 被动 依次唤醒规划器与执行器，最后调 `Analyzer.tick(now)`；按节点 catch 异常后继续下一个，`tick` 放 `finally`；自身不做决策、不选面 |
| `io` | `Analyzer` | 每工厂一个；账本 `Ledger`（被动每 Entry、主动每节点，各 8 行）+ 结论 `Conclusion`（含 `entryFaceHints` / `machineFaceHints`）+ 统计；只记不判，淘汰与清理自己维护 |

### 2.2 删除 / 合并

| 现状 | 处置 |
|---|---|
| `IoForm` + `IoUnit` + `IoUnitKey` | 合并为 `Form` |
| `ResourceAdapter` / `ItemAdapter` / `FluidAdapter` / `EnergyAdapter` | 删除 |
| `ItemCandidates` / `ItemVariantIndex` | 合并为 `VariantPick` |
| `IoPortResolver` | 拆成 `FacePick` + `PortExecutor`，其余删除 |
| `IoTransfer` | 并入 `IoExecutor`（`move`） |
| `IoLedger` / `UnitDigest` / `LedgerAnalyzer` / `TransferStats` | 合并为 `Analyzer` |
| `IoResult` | 改名 `MoveResult`，保留 moved + reason |
| `IoSide` | **保留**：它是输入/输出方向，`network` 与 `client` 都在用，不是结果 |
| `Reason` | 末尾新增 `FACE_CONFIG_INVALID`，`severity = 4`；`PROTOCOL_VERSION` +1（网络按 ordinal 编码，只能末尾追加） |
| `RuleKey` | `boolean input` 换成 `IoSide side`，消掉布尔盲参 |
| `PendingOrder` | 改造为 `DispatchOrder` |
| `NodeDigest` | 并入 `Analyzer`（主动侧账本 + 节点结论） |
| `ProviderReturnSweep` | 并入 `IoRunner`（回收动作在 `IoExecutor.sweep`） |
| `PassiveIoRunner` | 并入 `IoRunner`（不再单独保留被动执行器） |

### 2.3 保留但瘦身

| 类 | 改动 |
|---|---|
| `NodeCraftService` | 只留 AE2 协议：广告、`pushPattern`（校验+拆份+入队）、`isBusy`；`deliver` 整个删掉。样板缓存与 `patternInputs` / `products` 仍在这里，经 `DispatchState` 交给执行层 |
| `NodeProvider` | 样板 + `DispatchQueue` 的落档；队列结构改用 io 的类型 |
| `NodeRuntime` | 增加 `dispatchState()`，把队列/机器/阻挡/产物白名单打包成中性值对象 |
| `PassivePlanner` | 改为：到点判断 → 调 `FacePick` → 调 `VariantPick` → 产出 `IoJob` |
| `ActivePlanner` | 改为：每工厂一个，内部按节点记回收节奏；按 `dispatchScanLimit` 检查队首几单 → 按 `DispatchMode` 在多台机器上分配（顺序：逐台塞满；平均分：按目标摊开）→ 定面后算份数 → 产出 `IoJob` 列表（详见 2.6） |
| `IoExecutor` | `move` / `fill` / `refund` / `sweep`；机器侧唯一写入口，使用工厂共享的 `PortExecutor`；不决定搬什么、走哪个面 |

### 2.4 执行契约：move / fill / refund / sweep

`move` / `fill` / `refund` 返回 `MoveResult(moved, reason)`；`sweep` 返回 `Map<AEKey, MoveResult>`。`moved` 是**机器侧实际收下 / 交出的量**，不是请求量。这是 P3 记账的唯一依据。

- `move(source, target, key, amount)`：先模拟目标能收多少，按接受量从源扣，再提交；提交少于扣减时把差额退回源。被动搬运走这一条。
- `sweep(machine, network, 白名单)`：逐面取机器存储、抽白名单产物、按网络能收多少插入；插不回机器的按 D21 记日志丢弃。回收走这一条。**按资源分别返回结果**（`Map<AEKey, MoveResult>`），账本才能写清“哪个资源为什么没抽出来”；只给一个总数的话，界面与账本都答不了。
- `fill(machine, jobs)`：同一台机器的一批行。`jobs` 已按行聚合——同一行只出现一次、量 = `perPortion × n`，所以 81 行的一单就是 81 次 `insert`，和份数无关。材料在派单时已从网络抽走，**不从任何源扣、也不再模拟**（份数由 Planner 预先算），逐行直接写；正常返回的 `moved` 就是写进去的量。
- `refund(network, key, amount)`：把没交出去的量退回网络；网络不收时按 D21 记日志丢弃，不生成掉落物。

`fill` 的部分成功是正常路径，不是异常：

- 机器模拟能收 64、提交只收 32（同 tick 有别的写入，或能力实现不一致）时，`moved = 32`。
- 订单只记 `donePortions` 与 `openBatches`；每行已写量 = `donePortions × perPortion[i] + 该行在半份里写过的量`，剩余量现算。
- 一份的所有行必须落在同一台机器；半份补齐才 `donePortions + 1`，**不重交已交付部分**（重交就是复制）。
- 提交抛异常时该行写进去多少是未知的：**按保守策略处理**——该行剩余量留在队里下一 tick 重试（可能重复，但保证这单不卡死），并记 `ERROR`。不做前后库存快照。

没有这两个账，“严格等于 perPortion × n”只是一句愿望；这是 P3 的核心不变量。

**网络句柄与回收**：回收（机器 → 网络）与超时退款（队列 → 网络）就是 `move` / `refund`，不需要 `Node.actionSource()`——网络侧走 `AeKeySupport.SOURCE`（空动作源），机器侧 P2 之后是 `MEStorage` 包装，也不吃动作源。句柄统一用 `factory.live().storage()`，与被动同源：虚拟节点 attach 在锚点网格上（`NodeManager.java:296`），工厂仓库也取同一个锚点网格的库存（`Ae2StorageResolver.java:57`），两者是同一张网，不是“通常相同”。所以 `DispatchState` 只放队列、在线机器、派单模式、阻挡开关、样板、白名单；`IoRunner` 从 Factory 拿网络。P3 验收加一条：主动退款/回收与被动搬运打到同一个库存。

**回收归属**：回收跟样板走（按样板产物白名单扫回），不跟被动规则走，所以算主动侧的售后：`ActivePlanner` 定节奏，`IoRunner` 调 `IoExecutor.sweep(machine, network, 白名单)`，由执行器逐面取存储、抽白名单产物。Runner 不直接碰 `PortExecutor`。回收与被动共用的是 `IoExecutor` 这个写入口，不是回收的时机与范围。同一产物若又命中被动输出规则，两边都是 `extract`，先到先得，不会复制。

**选面与容量模拟是两件事**：

- **选面**只回答「走哪个面」。配置面、经验面存在时 Planner 直接定死，**不做任何模拟**；两者都没有才由 `FacePick` 逐面建存储、`insert(SIMULATE)` 探一次，拿到第一个能收下的面。`REJECTED` 只清经验面（配置面是玩家意图，不清，只报原因），下次退回探面。
- **容量模拟**只回答「这一轮能搬多少」。被动由 `IoExecutor.move` 在目标面上模拟一次再扣料；主动由 Planner 在定好的面上算 n 份（每行一次模拟），Job 带上 `perPortion × n`，`fill` 不再模拟。

经验面两边是同一个概念，只是作用域不同，统一叫 `FaceHint`：被动存 `entryFaceHints`（键是 `Entry.Key`，Entry 离开表单即释放）；主动存 `machineFaceHints`（键是**机器 + 资源**，不是一台机器一条——同一样板的物品走顶面、流体走底面时，两个资源各记各的面；TTL 1200 tick、每机器 512 条安全栅栏，删机器 / 该资源被拒 / 观测代数变化时清）。执行器不选面、不写经验面，经验面由 Runner 拿到 `TransferResult` 后交 `Analyzer` 记。

### 2.5 分析器契约（Analyzer）

一句话：**只记不判**。Runner 报结果，Planner 与界面读；它不碰世界、不做决策、不写存档。

**三个抽屉**

| 抽屉 | 内容 | 滚不滚 | 容量 |
|---|---|---|---|
| `Ledger` | 变化序列：同原因 + 同面合并成一行 | 滚 | 8 行 |
| `Conclusion` | 摘要：尝试 / 成功 / 空转 / 拒收连击 / 原因计数 / 经验面 | 不滚 | 1 份 / 目标 |
| `Stats` | 每秒入 / 出速率窗口 | 每秒结算 | 规则数有上限 |

账本有两个目标，行结构相同：

- 被动：每个 `Form.Entry` 一本；
- 主动：每个节点一本。节点账本跨单跨机器，所以行里带 `key` 与 `machineId`；被动行这两项为空，Entry 已经唯一确定机器与资源。

主动账本行带 `orderId`、`key`、`machineId`、面、数量、起止 tick；被动行这几项由 Entry 身份推出，不重复存。主动账本不是任务台账（D23）：它只记“最近发生了什么”，订单状态仍在 `DispatchOrder`。

节点结论除队列单数与队首时刻外，补 `lastReason`（最近一次失败原因）与 `deliveredTotal`（累计送达量）——现有 `NodeDigest` 的注释早就这么写，代码只实现了前两个字段。机器结论另带 `blockedUntil`（阻挡缓存与退避时刻）。

玩家改某个资源的输入面时，**不清** `machineFaceHints` 里那条：配置面优先，清了也没用；等配置面被删掉、且该面失效时自然会被 `REJECTED` 清掉。

**经验面的命名**：两边是同一个概念（最后一次搬通的面），只是作用域不同，所以统一叫 `FaceHint`，不再用 `faceMemory` 这个第二种写法：

| 作用域 | 字段 | 键 | 失效 |
|---|---|---|---|
| 被动 | `entryFaceHints` | `Entry.Key` | Entry 离开表单 |
| 主动 | `machineFaceHints` | 机器 + 资源（按 `dropSecondary()` 去组件，模糊变体共用一条） | 删机器 / 该资源 `REJECTED` / 观测代数变化 / TTL 1200 tick / 每机器 512 条安全栅栏 |

**写入规则**（Runner 调一次 `record`，Analyzer 自己分派）

| 结果 | 账本 | 结论 | 经验面 | 统计 |
|---|---|---|---|---|
| 搬到了 | 合并或新开一行 | 更新成功时刻、空转起点、连击清零 | 写入本次面 | 按规则累计 |
| `REJECTED` | 新开一行 | 连击 +1 | 清掉 | — |
| `FULL` | 新开一行 | 连击清零 | 保住 | — |
| 其余原因 | 新开一行 | 原因计数 +1 | — | — |

**读取者**

| 读者 | 读什么 | 用途 |
|---|---|---|
| `PassivePlanner` | 空转时长、拒收连击、ERROR 次数、`entryFaceHints` | 到期判断、退避、先试哪个面 |
| `ActivePlanner` | 节点单数与队首时刻、`machineFaceHints` | 队列状态、先试哪个面 |
| 视图 Builder | `Ledger.current()`、结论、`Stats` | 终端显示 |

退避由 Planner 算，Analyzer 只给原料。

**生命周期**

- 写入只在 Runner 里发生；主线程，无锁。
- 清理：Entry 离开表单 → 清账本与结论；机器删除 → 清 `machineFaceHints` 与 `Stats`；节点删除 → 清节点账本与结论。
- 回收（经验面的主机制）：条目带 `lastUsedTick`；`FACE_HINT_TTL_TICKS = 1200`，读取时惰性判过期并删除，后台再按轮转游标补扫（见下）。
- 安全栅栏（兜底，不是功能上限）：`machineFaceHints` 每机器 512 条，超了按最久未用淘汰并记一条节流日志。正常玩法摸不到这个数，它只防“同一台机器被灌进成千上万个资源”。
- 不做全局条数上限：TTL 把稳态压到“最近一分钟真用过的 (机器, 资源)”，全局量随活跃度收敛。
- 其余上限：账本 8 行 × 目标；原因计数固定 8 个枚举；`Stats` 每机器 × 规则两个窗口。
- 失效只影响一条：某个资源 `REJECTED` 只清它自己的经验面，不动同机器其他资源的条目。

**谁维护**：淘汰与清理都在 Analyzer 内部，外部只负责“到点了叫它一声”。

- `tick(now)`：由 `FactoryTickRunner` 在**执行段之后**每工厂每 tick 调一次（写入完成后再结算与清理）。它自己数间隔——每 20 tick 结算速率，每 tick 轮转扫 16 台机器的 `machineFaceHints`（8192 台一轮约 512 tick，对 1200 tick 的 TTL 足够及时），不做全量扫描。
- `retain(entries, machines, nodes)`：`IoRunner` 在被动段之后调一次，把当前存活集合传进来，Analyzer 自己做差集删除。
- 读取路径也惰性判 TTL：命中的条目顺手刷新 `lastUsedTick`，过期的当场删掉，不等后台扫。
- Analyzer 不读 `Level`、不注册事件、不起线程；时间永远是参数，淘汰逻辑可以脱离游戏单测。

### 2.6 主动派单：一单分到多台机器

队列是每节点一个（`NodeProvider`），同一队列的单共享该节点的在线机器。一单不再绑定一台机器，属于节点；每 tick 由 `ActivePlanner` 在节点的在线机器上分配，剩余量留到下一 tick。

**两种模式**（配置值仍留在 `NodeProvider` 里持久化，搬的是类型定义：`io` 不能 import `node`，而 `node → io` 是允许方向。阻塞开关同理）

- **顺序 `SEQUENTIAL`**：从第一台在线机器开始，`portionsThatFit` 能塞几份塞几份；塞满换下一台；剩余 0 或所有机器都塞不下就停。下一 tick 重新从第一台开始（它可能已经腾出空间）。
- **轮询 `ROUND_ROBIN`**：不是一台一份地转，而是**平均分**——先算 `目标 = ceil(剩余份数 / 还有空位的机器数)`，逐台取 `min(目标, portionsThatFit)`；一轮分完还有剩余，就对还有空位的机器再来一轮，直到分完或都满。这样多台机器一起开工、大致同时填满，不会出现 A 满 B 空。`nextMachineId` 只记“这一轮从哪台开始”，避免固定顺序总是同一台先拿。

**分配单位是“份”，不是“行”**：选定一台机器、算出能装 n 份后，把这张样板的**所有行 × n** 一起写给同一台机器。同一份的不同行绝不能分到两台机器——机器要凑齐整份才能开工，拆开就是两份废料。

两种模式都遵守同一条：**先算 `portionsThatFit`，算出来 0 就不写**。但估算可能不准（模拟能收、提交少收），所以仍要处理半份，见下。

平均分模式每 tick 结束后，`nextMachineId` 指向本轮最后一台的后一台；顺序模式忽略它。

**每 tick 的预算**（进 `FactoryLimitsConfig`）

- **订单扫描上限** `dispatchScanLimit`（默认 8）：每节点每 tick 只检查队首这么多单。队列是节点级的，**不轮转、不跳序**——前面卡住说明整批活没干完，后面的单即使塞得进去也没意义；
- **暂不做时间护栏**：本轮先不加每 tick 的时间/行数上限；写入次数按行聚合，正常输入量级可控。真出现病态输入再单独加。
- 不需要“每单每 tick 交几份”的上限：写入次数按**行**算，不按份算——同一行一次 `insert`，量 = `perPortion × n`，81 行 × 1000 份也只有 81 次；
- 平均分不设“最多探测几台”：从 `nextMachineId` 开始逐台探测，凑够剩余份数就停，天然按需要收尾。

**阻挡判定**：阻挡是节点配置，但判定要跟着“这一单要去哪台机器”走，所以下移到投递——`DispatchState` 带阻挡开关与样板输入集合，`ActivePlanner` 逐台过滤掉“机器库存里还有这批样板输入”的机器。`pushPattern` **不做阻挡判定**（“所有机器都挡”要先扫一遍所有机器库存，一点都不便宜）；只校验样板、在线、队列容量，入队后由投递逐台过滤。全挡时就卡着、`isBusy` 亮黄灯，符合“让玩家检修”的语义。

判据：目标机器的库存里只要还有**这批样板的任一输入 key**（按 `dropSecondary()` 去组件比较），就算被挡；目的就是保证一台机器一次只跑一张同类型配方，不把 A、B 两套输入混在一起。

读库存按机器缓存 + 退避：每台机器每 tick 最多查一次；查到“被挡”就记 `blockedUntil = now + 20 tick`，期间直接跳过、不再读库存；机器删除 / 观测代数变化时清掉这条。缓存放 `Analyzer` 的机器结论里。

**卡与退避**

机器不在 / 未加载 / 面拿不到 / `portionsThatFit` 返回 0（含 `FULL`）都算卡。卡一次就 `retryAt = now + 退避`，退避按连续卡次数 5 / 10 / 20 / 40 / 80 tick 封顶 100；只要有进展（`moved > 0`）立刻清零。退避期间只做“跳过”判断，到点后仍按队列顺序再试。扫描预算按“被检查的订单数”计，不按“成功投递数”计。

卡单期间 `isBusy` 保持 `true`：AE 亮黄灯、不给这个节点派新单，这是预期信号，不是 bug。**不做“卡太久就自动退款”**——那会把玩家该修的故障藏起来。

**Node 传给 io 的东西**：派单模式、阻挡开关、样板输入集合、产物白名单、在线机器列表、队列。Node 只交“值”，怎么用由 io 决定；io 不 import `node` 的任何类型。

**`PortExecutor` 只有一个实例**：`FactoryRuntime` 建一份、随工厂存活，`NodeRuntime` / `NodeCraftService` 拿到的是同一个实例。缓存是绑在实例上的——再 new 一个就有第二份缓存。

**缓存怎么失效**（跨 tick，不重建）：`PortExecutor` 不做任何世界检查，取用直接拿缓存。失效只有两个入口：

- **动作失败**：`REJECTED` / `ERROR` 只失效那个面；`NO_FACE` 失效整台机器的 6 个面（连面都没选出来）；`FULL` 不失效（面是对的，只是满了）；`sweep` 某一面抽取异常时只失效那一面；`fill` 抛异常失效该面。
- **生命周期**：机器删除 / 节点删除 → `drop(machineId)`，一次清掉它的 6 个面。

下一次 `storage(machine, face)` 发现条目没了就重建；想当场强制重建，给 `storage(machine, face, rebuild)` 一个参数，Planner 探面时可以传 `true`。工厂级再加 16384 条栅栏，超出按最久未用丢。这样 PoE 里不掺观测侧的逻辑，失效由“失败”和“删除”两个事实驱动。

**为什么 `perPortion` 不带面**：面要按“这次写给哪台机器”来定，同一行这一 tick 可能写给机器 A、下一 tick 写给机器 B；面记忆是 `(机器, 资源) → 面`，所以面只能在投递时按目标机器解析，不能在派单时钉死。

**配置面无效不兜底**：玩家给规则配的面如果没能力，就记 `NO_FACE`，不自动换别的面重探。这是玩家配置问题，替玩家猜会把问题藏起来。

**超时与退回**（没有“投递超时”）

- 卡单**不超时**：一直重试、一直忙，直到玩家修好机器、拆掉机器或拆节点。自动退款会让黄灯消失，问题被藏起来。
- **成员机器被删光**：节点还在但 `machines()` 为空 → 队列立即尽力退回网络（退不回按 D21 记日志丢弃）。这是玩家明确删机器。
- **节点被删除 / 解绑**：拆节点时把队列退回网络（`detachReturning`）。
- **节点暂时离线**（锚点区块卸载、频道丢失）：队列随节点记录保留，节点回来继续投，不算孤儿。
- **孤儿超时** `ORPHAN_TIMEOUT_TICKS`：队列已标记孤儿、网络长时间收不回 → 超时丢弃（D21）。初值 1200。

**跨机器记账**

进度是整单的，不按机器拆：`donePortions` 是已完整进机器的份数，`openBatches` 是几台机器上还没补完的半份。机器被删：已完整进机器的份不退款；该机器半份里没写进去的量退回网络，写进去的随方块消失。只有节点销毁、成员机器被删光、或孤儿超时才退整个剩余量。

**半份怎么处理**：估算和实际写入可能不一致，那台机器上会留下“半份”。半份记在订单的 `openBatches` 里（`机器 id → 每行还差多少`），下一 tick **优先补齐**；补齐后 `donePortions + 1`，补不齐就一直卡着（`isBusy`），**不把剩余行挪到别的机器**——挪了就是两台机器各拿半份，两份都废。这不是“每台机器一个队列”，是每单每机器一条半份记录；一台机器同一时刻最多一个半份，补完才拿下一份。机器删了，半份跟着方块消失，已经写进去的部分记在 `donePortions` 与半份记录里，退款只退没写的。

**机器带半份被删时**：这一份记成结束（`donePortions + 1`），把 `OpenBatch` 里每行 **missing 的量退回网络**；已经写进去的部分随方块消失，不退款。这样玩家拿回没写进去的半份，也不会把已经进方块的部分再退一次。退不回网络按 D21 记日志丢弃。

读档 / 对账时如果发现 `openBatches` 里的机器已经不在节点成员里，按同一条处理：`donePortions + 1`、退 missing、删这条 batch。

**fill 抛异常时怎么退队重试**

异常时“写进去多少”是未知的，直接重试可能重复。这里选**宁可多给、不让单卡死**：该行剩余量留队下一 tick 重试，记 `ERROR`。不做库存快照——读两次库存既贵，读错还会把账算得更歪。

`move` 的异常路径同样按这个口径统一：`insert` 抛异常时，把本次从源扣走的量整笔退回去（可能重复），记 `ERROR`，不让料悬空。两边一致，别只修主动侧。

### 2.7 配置常量（FactoryLimitsConfig）

| 常量 | 值 | 说明 |
|---|---|---|
| `DISPATCH_SCAN_LIMIT` | 8 | 每节点每 tick 检查队首几单 |
| `DISPATCH_BACKOFF_TICKS` | 5 / 10 / 20 / 40 / 80，封顶 100 | 卡单退避阶梯 |
| `ORPHAN_TIMEOUT_TICKS` | 1200 | 孤儿超时 |
| `FACE_HINT_TTL_TICKS` | 1200 | 主动经验面过期 |
| `FACE_HINT_FENCE` | 512 | 每机器经验面条数栅栏 |
| `ANALYZER_SWEEP_BATCH` | 16 | 分析器每 tick 补扫机器数 |
| `ANALYZER_SAMPLE_INTERVAL` | 20 | 速率结算间隔 |
| `PORT_CACHE_FENCE` | 16384 | 端口缓存条数栅栏 |
| `MAX_SWEEPS_PER_TICK` | 64（沿用） | 回收每 tick 预算 |
| `RECYCLE_DELAYS` / `RECYCLE_ACTIVE_WINDOW` | 5 / 20 / 40 / 80 / 128；200（沿用） | 回收阶梯与活跃窗口 |
| `MAX_PENDING` | 64（沿用） | 每节点队列上限 |
| `LEDGER_CAPACITY` | 8（沿用） | 账本行数 |

新增的进 `FactoryLimitsConfig`；标“沿用”的留在原类，不搬家。

---

## 3. 分阶段计划

### P1 数据契约与命名（无行为变化）

**做什么**

1. `IoUnit` 并入 `Form.Entry`（同 2.2）；`IoJob` 是 P2 才新增的一次性任务，两个概念不能共用一个名字。
2. `IoForm` + `IoUnit` + `IoUnitKey` 合并成 `Form`（内嵌 `Entry` / `Entry.Key`）。
3. `IoResult` → `MoveResult`（保留 moved + reason）；`IoSide` 保留不动。
4. `IoLedger` + `UnitDigest` + `LedgerAnalyzer` + `TransferStats` 合并成 `Analyzer`。
5. `RuleKey` 的 `boolean input` 换成 `IoSide side`；调用点同步（`Form.Entry.Key` / 账本 / 统计）。

**验收**

1. `gradlew build` 通过。
2. 进游戏：接一台机器配输入/输出规则，搬运行为与改前一致。
3. 主动：放样板、下单，合成行为与改前一致。

**回滚**：revert 本阶段提交（无存档变化）。

### P2 统一 MEStorage（有行为变化）

**做什么**

1. 新增 `PortExecutor`：从 `IoPortResolver.Faces.storage(face)` 提取，负责按面建 `MEStorage`，按机器缓存。
2. 新增 `FacePick`：配置面 → 经验面 → 六面模拟；内部按需调 `PortExecutor`。
3. 新增 `CapacityPick`：`portionsThatFit(机器, perPortion)`，逐行模拟取最小；调 `PortExecutor`。
4. 新增 `VariantPick`：合并 `ItemCandidates` 与 `ItemVariantIndex`。
5. `IoExecutor` 改用 `MEStorage`：`move`（源→目标）、`fill`（凭空补）、`refund`（退回）；删除 `IoTransfer`。
6. `PassivePlanner` 改为调 `FacePick` / `VariantPick`，产出 `IoJob`。
7. `NodeCraftService.smartCapacity` 改为：**按样板逐个算**——每张样板用自己的 `perPortion` 求各在线机器 `portionsThatFit` 之和，再取 **25%（向下取整，至少 1）**，所以大小不同的样板会得到不同倍数；去掉 `Multiplier.HARD_MAX_FACTOR` 的业务截断，只保留按样板数量算出的安全上限（防 long 溢出）；固定倍率只校验 `≥1`。缓存不设 TTL；重建触发条件是样板增删、机器上下线、倍率模式切换；重建时按当前样板集合 `retain`，把已删样板的条目清掉。
8. 删除 `ResourceAdapter`、三个 Adapter、`IoPortResolver` 的扫描部分。

**验收**

1. `build` 通过。
2. 物品 / 流体 / FE 各搬一次，结果与改前一致。
3. 模糊规则（同名不同组件）能按变体搬，名额上限仍然生效。
4. 不分区机器：六面都能拿到存储（若拿不到，记录后单独讨论，不擅自加无向兜底）。
5. 化学品：装上 Applied Mekanistics 时能搬；界面不保证。
6. 阻塞模式仍能命中（清单求交集，按去组件 key 比较）。
7. 智能倍率：倍数 = 在线机器容量之和的 25%（向下取整、至少 1）；不再有 64 倍截断；数值大到会溢出的样板被安全夹回，而不是整张样板消失。

**回滚**：revert（无存档变化）。

**风险**

- 抽取顺序改由 AE2 决定（数量不受影响）。
- FE 探面模拟量必须沿用 1000。

### P3 主动队列与执行顺序（有行为变化）

**做什么**

1. `PendingOrder` → `DispatchOrder`：`id`（UUID）+ `perPortion`（每份用量，不带面）+ `portions` + `donePortions` + `openBatches` + `nextMachineId` + `retryAt`；不再绑机器。
2. `DispatchQueue` 从 `NodeProvider` 抽出，作为独立对象；`NodeProvider` 只负责序列化。
3. `NodeCraftService.pushPattern` 瘦身：校验 → 拆份（交叉校验）→ 入队 → true；**不选面**。
4. `ActivePlanner` 接管投递规划：按 `dispatchScanLimit` 检查队首几单、按 `DispatchMode` 在多台机器上分配、定面后算份数、产出 `IoJob` 列表、按连续卡次数退避、标记孤儿；回收节奏也在这里。
5. `IoRunner`：吸收 `PassiveIoRunner` 与主动驱动，接心跳后按 回收 → 主动 → 被动 依次跑；执行 `IoJob`（`fill` / `move` / `sweep`）、回写 `donePortions` / `openBatches`、有进展清退避、孤儿按剩余量 `refund` 并出队；最后调 `Analyzer.tick(now)`。
6. `FactoryTickRunner` 调整执行顺序：回收 → 主动 → 被动。
7. `NodeRuntime` 增加 `dispatchState()`；`NodeDigest` 并入 `Analyzer` 作为主动侧节点账本与结论。
8. `DispatchMode` / `BlockingMode` 的类型定义移到 `io`（`NodeProvider` 继续持久化字段值，`Node → io` 允许）；`NodeRuntime.pickMachine` 删除（一单不再选一台机器）。

**验收**

1. `build` 通过。
2. 一单 100 份、两台机器：顺序模式先塞满机器 1 再塞机器 2；轮询模式两台各 1 份轮转；剩余留到下一 tick，`donePortions` 与 `openBatches` 对得上。
3. 队首卡住时后续单能继续投（不再头阻塞）。
4. 删光成员机器 / 删节点：队列退回网络；退不回的按 D21 记日志丢弃，`isBusy` 解开。
5. 落档：写队列 → 关服 → 重启 → 队列恢复 → 继续投递。
6. 删机器 / 删节点：队列退回网络，退不回静默丢并记日志。
7. 回收仍正常，执行顺序为回收 → 主动 → 被动。
8. 主动选面：有经验面时直接定面、不探面（不产生额外的 `SIMULATE`）；只有配置面与经验面都没有才探面；`REJECTED` 清经验面，下一轮重探。
9. 回收走 `IoExecutor.sweep`；与被动输出规则同时命中同一产物时不复制。
10. 轮询起点跨 tick 继续，不是每 tick 从第一台重来；卡单按退避时间重试，退避期间只做跳过判断。
11. 半份：模拟能收、提交少收时，机器留下半份；下一 tick 先补满这台机器的半份再继续，不会把剩余行发到别的机器。
12. 机器带 2.5 份被拆：两份不退；半份里没写进去的部分回到网络，已写进去的部分丢失，订单继续投剩下的部分。
13. 阻挡开启时，带这批输入的机器被跳过；全挡时订单卡着、`isBusy` 为 true，`pushPattern` 仍然入队（不提前拒绝）。

**回滚**：revert + 重建世界（队列存档格式变化；D26 允许）。

**风险**

- 主动投递是“凭空补”（材料在派单时已被扣走），记账必须逐行按实际 `moved` 累加（§2.4）；按“整单成功”记一次就会复制或丢失。
- 平均分模式每台只探测一次；写入次数按行算，正常输入量级可控；本轮不加时间护栏。
- 退回会把材料还回网络，AE2 那单会失败或超时（已接受）；卡单不自动退，AE 会一直黄灯，靠玩家修。

### P4 收尾

1. 删剩余死代码、陈旧注释。
2. 更新 `docs/DECISIONS.md`（执行层职责、MEStorage 统一、队列落档语义）。
3. 更新 `AGENTS.md`：新增 `Form` 后缀，定位 `Job` / `Order` / `Queue` / `State`；改掉 §6.2 第 6 条“无向兜底”。按 §5.6 单独审批，不随本计划自动改。
4. 核对指标：`io` 23 → 18、`node` 22 → 18；行数不设目标（见 4.4）。

---

## 4. 逐文件迁移清单

口径：`io` 现 23 个文件 2520 行；`node` 现 22 个文件 3876 行。下表只列本计划会动的文件，共 54 个：新建 10 / 删除 18 / 改名 2 / 搬家 3 / 改动 21。`IoSide` 不动；`Reason` 末尾加一个值；`RuleKey` 只改字段类型（`boolean input` → `IoSide side`）。

### 4.1 P1：改名与合并（无行为变化）

| 文件 | 动作 | 说明 |
|---|---|---|
| `io/IoForm.java` → `io/Form.java` | 改名 + 吸收 | 内嵌 `Entry` / `Entry.Key`，替换 `IoUnit` / `IoUnitKey` |
| `io/IoUnit.java`、`io/IoUnitKey.java` | 删除 | 并入 `Form.Entry` / `Form.Entry.Key` |
| `io/IoResult.java` → `io/MoveResult.java` | 改名 | 保留 `moved` + `reason`；**不并入 `Reason`** |
| `io/IoLedger.java`、`UnitDigest.java`、`LedgerAnalyzer.java`、`TransferStats.java` → `io/Analyzer.java` | 合并 | 嵌套 `Ledger` / `Conclusion` / `Stats`；`entryFaceHints` / `machineFaceHints` 放 `Conclusion`；主动节点账本也在这里 |
| `io/IoExecutor.java` | 改引用 | `Form.Entry`、`MoveResult`、`Analyzer`；逻辑不动 |
| `io/RuleKey.java` | 改签名 | `boolean input` → `IoSide side`；`Form`、`Analyzer`、`FactorySnapshotBuilder` 等调用点同步 |
| `io/PassivePlanner.java` | 改引用 | 同上；睡眠/退避逻辑不动 |
| `io/package-info.java` | 改说明 | 类清单重写 |
| `factory/IoFormBuilder.java` | 改引用 | 构建 `Form` |
| `factory/FactoryTickRunner.java` | 改引用 | 每工厂调 `analyzer.tick(now)`，删掉原 `stats.sample()` / `stats.retain()` 调用；P3 再接 `IoRunner` |
| `factory/FactoryRuntime.java` | 改引用 | P3 装配 `IoRunner`（P1 先不动） |
| `factory/FactorySnapshotBuilder.java` | 改引用 | `Form.Entry` + `Analyzer.Conclusion` |
| `factory/FactoryNodeSnapshotBuilder.java` | 改引用 | 同上 |
| `factory/FactoryLive.java` | 改引用 | 持有一个 `Analyzer`（替代 `TransferStats` 字段） |

### 4.2 P2：统一 MEStorage

| 文件 | 动作 | 说明 |
|---|---|---|
| `io/Reason.java` | 改 | 末尾新增 `FACE_CONFIG_INVALID`；`severity()` 补一条（P2 的 `FacePick` 开始用到） |
| `client/screen/FactoryLabels.java` | 改 | 给新原因补文案与颜色（只加一条，不动布局） |
| `network/ModNetwork.java` | 改 | `PROTOCOL_VERSION` +1 |
| `io/PortExecutor.java` | 新增 | 从 `IoPortResolver.Faces.storage` 与 `build` 抽出；`(方块, 面) → MEStorage`，按机器缓存 |
| `io/FacePick.java` | 新增 | 从 `IoPortResolver` 抽出：配置面 / 经验面直接返回，两者都没有才六面模拟；只返回面 |
| `io/CapacityPick.java` | 新增 | 容量查询 `portionsThatFit`：主动分配与智能倍率共用；调 `PortExecutor` |
| `io/VariantPick.java` | 新增 | 合并 `ItemCandidates` + `ItemVariantIndex`，吸收 `ItemAdapter` 的变体扫描与容量模拟 |
| `io/IoJob.java` | 新增 | 一次性任务；面与具体 key 由 Planner 定 |
| `io/IoPortResolver.java` | 删除 | 选面 → `FacePick`，建存储 → `PortExecutor`，模拟 → `VariantPick` / `IoExecutor` |
| `io/ResourceAdapter.java`、`ItemAdapter.java`、`FluidAdapter.java`、`EnergyAdapter.java` | 删除 | 机器侧统一 `MEStorage` |
| `io/IoTransfer.java` | 删除 | 并入 `IoExecutor.move` |
| `io/ItemCandidates.java`、`io/ItemVariantIndex.java` | 删除 | 并入 `VariantPick` |
| `io/IoExecutor.java` | 重写 | `move` / `fill` / `refund` / `sweep`，全走 `MEStorage`；使用工厂共享的 `PortExecutor` |
| `io/PassivePlanner.java` | 重写 | 调 `FacePick` / `VariantPick`，产出 `IoJob` |
| `node/NodeCraftService.java` | 改 | `faces` / `blockingHit` 改调 `FacePick` / `CapacityPick`；`smartCapacity` 改为按在线机器求和 × 25% |
| `node/Multiplier.java` | 改 | 删 `HARD_MAX_FACTOR` 的业务夹取，只留 `MIN_FACTOR` 与解码校验 |
| `node/ScaledPatternDetails.java` | 改 | 倍数按样板数量算出安全上限，溢出时夹回而不是返回 `null` |
| `node/NodeRuntime.java` | 改 | `faces()` 删除或改薄 |

### 4.3 P3：主动队列与执行顺序

| 文件 | 动作 | 说明 |
|---|---|---|
| `io/DispatchOrder.java` | 新增 | 由 `node/PendingOrder` 迁入并改结构：`perPortion`（不带面）、`portions`、`donePortions`、`openBatches`、`nextMachineId`、`retryAt`；不再绑单台机器 |
| `io/DispatchQueue.java` | 新增 | 从 `NodeProvider` 抽出队列操作；唯一内存写入口；`RefundReport refundAll(MEStorage network, long now)` 供删节点与以后的手动按钮用 |
| `io/DispatchState.java` | 新增 | 一 tick 输入；队列是活引用（只有 `DispatchQueue` 能改），不含存储句柄 |
| `io/IoRunner.java` | 新增 | 吸收 `PassiveIoRunner` 与 `NodeCraftService.deliver` 的驱动；按 回收 → 主动 → 被动 跑，最后调 `Analyzer.tick` |
| `io/ActivePlanner.java` | 迁入 | 从 `node/ActivePlanner.java` 移到 io；多机分配（顺序 / 轮询）、退避、超时、回收择时 |
| `node/PendingOrder.java`、`node/ProviderReturnSweep.java`、`node/NodeDigest.java`、`io/PassiveIoRunner.java` | 删除 | 前三者依次并入 `DispatchOrder`、`IoRunner`、`Analyzer`；`PassiveIoRunner` 并入 `IoRunner` |
| `node/NodeProvider.java` | 改 | 队列字段换 `DispatchQueue`；`toTag` / `fromTag` 按 §6 新字段重写（`TAG_PENDING` 格式变化） |
| `node/NodeCraftService.java` | 瘦身 | 删 `deliver` / `returnOrDiscard` / `target` / `returnAllOrders`；留广告、`pushPattern`（不再选机选面）、`isBusy`；`smartCapacity` 改调 `CapacityPick` |
| `node/NodeRuntime.java` | 改 | 加 `dispatchState()`；删 `deliver` / `returnSweep` 调用、删 `pickMachine` / `faces`；`digest()` 改读 `Analyzer` |
| `node/NodeManager.java` | 改 | 回收扫描预算从节点 tick 移出，交给 `IoRunner` |
| `node/DispatchMode.java` → `io/DispatchMode.java` | 搬家 | 派单模式是执行语义；留在 node 会让 io 反向依赖 node |
| `node/BlockingMode.java` → `io/BlockingMode.java` | 搬家 | 同理；节点只是持久化这个值，执行时由 `DispatchState` 传给 io |
| `factory/FactoryTickRunner.java` | 改 | 顺序改“回收 → 主动 → 被动”；持有 `IoRunner` |
| `factory/FactoryRuntime.java` | 改 | 装配 `IoRunner`，网络句柄注入（见 2.4） |
| `factory/MachineLinkService.java`、`factory/FactoryObservationRunner.java` | 改引用 | `PassiveIoRunner` → `IoRunner` |
| `factory/FactoryNodeSnapshotBuilder.java` | 改 | `pendingCount` / `pendingOldestAt` 改读 `DispatchQueue` + `Analyzer.Conclusion` |

### 4.4 数量账（用来核对 P4）

- 新增 10：`Analyzer`、`PortExecutor`、`FacePick`、`CapacityPick`、`VariantPick`、`IoJob`、`DispatchOrder`、`DispatchQueue`、`DispatchState`、`IoRunner`。
- 删除 18：`IoUnit`、`IoUnitKey`、`IoLedger`、`UnitDigest`、`LedgerAnalyzer`、`TransferStats`、`IoPortResolver`、`ResourceAdapter`、`ItemAdapter`、`FluidAdapter`、`EnergyAdapter`、`IoTransfer`、`ItemCandidates`、`ItemVariantIndex`、`PassiveIoRunner`、`PendingOrder`、`ProviderReturnSweep`、`NodeDigest`。
- 改名 2：`IoForm → Form`、`IoResult → MoveResult`。
- 搬家 3：`node/ActivePlanner` → `io/ActivePlanner`、`node/DispatchMode` → `io/DispatchMode`、`node/BlockingMode` → `io/BlockingMode`。
- `io` 文件数：23 → **18**；`node`：22 → **17**。
- 行数：删除约 1550 行，新增按职责估算约 1400 行，净减有限（约 2400），**到不了 2000**。P4 已把行数从验收里拿掉，只看职责与文件数。

---

## 5. 依赖方向

```
factory(root) → IoRunner
IoRunner ├─ PassivePlanner / ActivePlanner ─→ FacePick / CapacityPick / VariantPick ─→ PortExecutor
         ├─ IoExecutor（move / fill / refund / sweep）─────────→ PortExecutor
         ├─ Analyzer
         └─ DispatchQueue / DispatchState
node → io（DispatchOrder / DispatchQueue / DispatchState / DispatchMode / BlockingMode / MoveResult）
io 不依赖 node
```

---

## 6. 存档与协议

- `DispatchOrder` 落档字段：`id`（UUID）、`perPortion`（列表：key + 每份用量，**不带面**）、`portions`、`donePortions`、`openBatches`（机器 id → 每行还差多少）、`nextMachineId`（轮询起点）、`retryAt`、`enqueuedAt`、`orphanedAt`。
- 队列属于节点，不属于某台机器：删一台机器不影响订单；只有节点销毁或整单超时才退剩余量。
- 队列**落档、跨重启恢复**（D28 已废止）。旧 `Pending` 格式直接丢弃（允许重建世界，D26）。
- 恢复语义：`donePortions` 与 `openBatches` 落档；读档后先补半份，再继续交新份，已交部分不重交。
- 网络协议：`Reason` 末尾新增 `FACE_CONFIG_INVALID`（网络按 ordinal 编码，只能末尾追加），`PROTOCOL_VERSION` +1；视图字段其余不变。

---

## 7. 全局验证清单（进游戏）

1. 被动输入 / 输出各一条规则，物品、流体、FE 各一例。
2. 模糊规则：同名不同组件，确认只搬名额内的量。
3. 主动：单份、倍率 4 份（机器一轮只放得下 2 份时，分两轮交）、队列两单。
4. 队首卡住 + 后面能投的单，确认不头阻塞。
5. 超时退回，确认队列前进且材料回到网络。
6. 关服重启，确认队列恢复并继续投递。
7. 删机器 / 删节点，确认队列退回网络。
8. 回收：产物能回网络，插不进的退回机器。
9. 执行顺序：同一 tick 内回收先于主动、主动先于被动。

---

## 8. 本计划明确不做

- 界面（节点卡片「+」按钮、账本展开、投递错误展示）。
- 无白名单自动输出。
- 规则执行限制（玩家配置每条规则维持数量）。
- 配置层独立成层。
- 化学品界面兼容。
- 玩家取消 AE 任务后的自动退回；手动“清空队列并退回网络”按钮与配置层重做一起做（底层 `DispatchQueue.refundAll` 本次做出来）。

---

## 9. 状态

**无待定项。** 所有决定都已落进正文；实现中再出现的细节按 §2.4 – §2.7 的契约处理。

