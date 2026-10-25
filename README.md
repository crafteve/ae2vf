# Idle Factory（放置工厂）

> modid: `idlefactory` · 当前分支 `refactor/factory-core`

把 Minecraft 科技整合包里「把机器接进自动化网络」这一步，从「摆接口、铺线缆、调 IO 面」变成「在终端里点一下」。

机器仍然是原模组的机器——配方、能量、加工时间全部不变。本模组只接管机器与仓库之间的**搬运**。

## 一句话

> AE2 的自动合成已经很接近放置游戏，只有「摆机器接进去」这一步破坏体验。Idle Factory 补的就是这一步。

## 当前状态

早期实现，**没有 UI，全部靠指令调试**。已经跑通的链路：

```
/idf create
  → /idf factory connect        准星选机器，接入机器库
  → /idf line create iron
  → /idf line iron add <机器>
  → /idf line iron manage <机器> input add minecraft:iron_ore
  → /idf line iron start
```

之后由服务端 tick 驱动：仓库 → 补料 → 机器加工 → 抽产物 → 回仓库。

**已实现**

- **工厂实例**：一人一个，确定性 UUID，`FactoryStore` 存档级持久化
- **仓库**：AE2 量子环或方块容器组，支持物品 / 流体 / FE，离线降级为空仓库
- **机器库**：准星接入 / 解除，校验 `IItemHandler` / `IFluidHandler`，每 5 秒对账清悬空引用
- **被动产线**：create / delete / add / remove / start / end / info / manage
- **IO 规则**：资源类型 + 注册名 + 绝对六向面，输入输出各自独立
- **IO 执行**：每 tick 一轮（`/idf diag io` 可调 1–20），`feed` / `pull` 事务，组 / 线两级睡眠
- **诊断**：事件流（命令 / 产线 / 机器 / 仓库）+ 每条产线的搬运计数，`/idf diag` 查

**未实现**

- 终端 UI（M1 的空界面还在，没接新架构）
- 化学品（Mekanism 的 gas 等，只留枚举位）
- 主动模式（pattern / CPU）

**阶段收尾：多类型（物品 / 流体 / FE）**

- 已跑通：容器仓库（箱子、储罐、能量立方）与 AE2 仓库（物品、流体）
- 已知限制：AE2 的 FE 仍等 Applied Flux；化学品只有枚举位；旧存档不兼容
- 下一步候选：化学品、Applied Flux FE、终端 UI

## 指令

所有指令挂在 `/idlefactory` 下，短别名 `/idf`。**产线是主语**：`/idf line <线> <动作> <对象>`。

```
/idf create
/idf delete

/idf factory connect                 准星选机器，接入机器库
/idf factory disconnect [id]         准星移除 / 按短 id 远程移除
/idf factory info                    工厂摘要 + 机器库

/idf line                            列出所有产线
/idf line create <name>              新建产线
/idf line <line>                     查看这条线（等于 info）
/idf line <line> info
/idf line <line> start
/idf line <line> end
/idf line <line> delete
/idf line <line> add <machine>
/idf line <line> remove <machine>
/idf line <line> manage <machine> info
/idf line <line> manage <machine> input  add item <item> [face]
/idf line <line> manage <machine> input  add fluid <fluid> [face]
/idf line <line> manage <machine> input  add energy [face]
/idf line <line> manage <machine> input  remove item <item>
/idf line <line> manage <machine> input  remove fluid <fluid>
/idf line <line> manage <machine> input  remove energy
（output 同构）

/idf storage connect                主手奇点绑 AE2 仓库
/idf storage bind                   准星选容器，加入仓库
/idf storage unbind [容器]          解绑整个仓库 / 移除一个容器
/idf storage priority <容器> <数字>  容器优先级（高的先填、低的先抽）
/idf storage info                   仓库类型、容器列表、资源
/idf storage get <item> [count]
/idf storage put <item> [count]

/idf diag                            健康摘要
/idf diag why <line> [machine]       体检：这条线为什么不动
/idf diag tail [n]                   最近 n 条事件（默认 10，上限 20）
/idf diag watch <line> [秒]          实时速率推到 actionbar，再敲一次关闭
/idf diag io [ticks]                 查看 / 调整 IO 间隔（1–20 tick）
/idf diag export                     把事件导出到 logs/idlefactory/
```

Tab 补全按上下文过滤：`add` 只补还没进任何产线的机器，`remove` / `manage` 只补这条线里的机器，`remove` 的物品只补已经配过的，`face` 会标出哪个面真的有物品能力。`<line>` / `<machine>` 都带补全；`face` 取 `any` / `north` / `south` / `east` / `west` / `up` / `down`。

回执里的产线短 id 可以直接点。产线名不能占用子命令字面量（`create` / `delete` / `info` / `start` / `end` / `add` / `remove` / `manage` / `list`），建线时会被拒绝。

## 诊断

聊天栏就是诊断面板：一行一条，`-8s [!!] MACH 9c1d 区块未加载`。

记的是**状态变化**，不是流水账：命令成败、产线启停与睡眠、机器被对账删除、仓库上下线。成功搬运只进计数器（`/idf diag` 里的 `1.6/s`），否则一分钟几百条会把 `tail` 淹掉。

`/idf diag why <line>` 是现场体检，不回放历史：机器还在吗、区块加载了吗、面有对应能力吗、仓库有料吗、机器收得下吗、输出槽有东西吗。六问按固定顺序答，「为什么不动」不用猜。

事件只存内存，每个工厂 200 条，不写存档；要留证据用 `/idf diag export`，导到 `logs/idlefactory/`。

## 资源类型

仓库与 IO 支持三类资源：

| 类型 | 单位 | 来源 |
|---|---|---|
| 物品 | 个 | `IItemHandler` / AE2 `AEItemKey` |
| 流体 | mB | `IFluidHandler` / AE2 `AEFluidKey` |
| 能量 | FE | `IEnergyStorage`；AE2 侧需要 Applied Flux（目前占位） |

默认搬运量是「塞满」：机器能收多少就搬多少。化学品只留枚举位，配了规则不执行。

Mekanism 的 NeoForge 能量适配器 `canExtract` / `canReceive` 恒为 true，真正的面限制在按面筛选后的 handler 里；而且 FE↔J 换算会在小数值上截断。所以面探测用「模拟搬运 1000 FE」而不是 1，否则会误判成不能抽。流体面探测同理。

## 架构

```
factory/                        工厂主体（服务端权威）
├── Factory / FactoryManager / FactoryStore / FactoryRuntime
├── FactoryService              指令入口
├── FactoryRefs / FactoryMessages / FactorySnapshotBuilder
├── resource/                   资源模型
│   └── ResourceKind / ResourceKey / ResourceSpec
├── storage/                    仓库抽象与地址
│   └── Storage / StorageLink / StorageAnchor（AE2 / 容器）
│   └── Ae2Storage / ContainerStorage / DispatchingStorageResolver
├── target/                     机器库
│   └── MachineLibrary / MachineTarget / MachineChecks
├── line/                       被动产线
│   └── PassiveLine / IoGroup / LineLibrary / LineService
├── io/                         IO 执行
│   └── IoTransfer / GroupRunner / LineRunner / RunnerState / IoResult
│   └── ResourceAdapter / ItemAdapter / FluidAdapter / EnergyAdapter
└── diag/                       诊断
    └── DiagEvent / Diagnostics / LineDiagnostics / DiagRender / DiagService

integration/ae2/                AE2 适配（物品 / 流体）
integration/appflux/            Applied Flux 的 FE key（占位）
menu/ client/                   M1 遗留的终端界面，尚未接新架构
```

核心约束：

- 工厂主体只认 `Storage` 接口，不 import `appeng`
- IO 只有一条写路径 `IoTransfer`：搬不进去的东西，永远不离开来源
- 仓库离线 = 空仓库，工厂表现为「没料」，不是「网络断线」

## 构建

```bash
./gradlew build      # 编译 + 打包
./gradlew runClient  # 起开发客户端
```

Mekanism 只用于本地运行期测试流体机器（`localRuntime`），不是编译依赖；首次 `runClient` 会从 Modrinth 下载。

## 已知的平衡改变

1.21.1 版 FE 能量走仓库且无损，因此对 FE 而言**移除了长距离输电损耗**这一平衡维度。这是有意的，记录在 ADR-006。EU 等其他能量单位不在支持范围内，相关机器仍需真实线缆。

IO 默认每 tick 一轮，能量 / 流体也是「塞满」；1 tick 一轮对性能有要求，卡顿时用 `/idf diag io 5` 降频。

## 文档

- [架构说明](docs/ARCHITECTURE.md)（旧版设计，部分内容已被 `factory` 包取代）
- [决策记录](docs/DECISIONS.md)

## 分支说明

`refactor/factory-core` 从 M1 终点 `e8ba44b` 拉出，旧 M2/M3 的 WIP 保存在 `stash@{0}`，切回 `1.21.1neoforge` 后可 `git stash pop` 恢复。
