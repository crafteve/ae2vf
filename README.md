# Idle Factory（放置工厂）

> modid: `idlefactory` · 当前分支 `1.21.1neoforge`

把 Minecraft 科技整合包里「把机器接进自动化网络」这一步，从「摆接口、铺线缆、调 IO 面」变成「在终端里点一下」。

机器仍然是原模组的机器——配方、能量、加工时间全部不变。本模组只接管机器与仓库之间的**搬运**。

## 一句话

> AE2 的自动合成已经很接近放置游戏，只有「摆机器接进去」这一步破坏体验。Idle Factory 补的就是这一步。

## 当前状态

核心逻辑（工厂 / 仓库 / 观测 / 产线 / 搬运）已经跑通，**界面处于 P0 阶段**：能打开、能看骨架，
但还没接数据。开发调试仍以指令为主，界面正在把配置与观测搬进去。

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
- **机器库**：准星接入 / 删除。方块没了只标记失联（UI 画屏障），机器和规则留在库；只有显式删除才移除机器
- **观测层**：全局方块观测（机器与容器共用一张表），预算 8 次/tick、指数退避、能力失效事件标脏；读路径不探世界
- **被动产线**：create / delete / add / remove / start / end / info / manage
- **IO 规则**：资源类型 + 注册名 + 绝对六向面，输入输出各自独立
- **IO 执行**：每 tick 一轮（`/idf diag io` 可调 1–20），`feed` / `pull` 事务，机器 / 线两级睡眠
- **面路由**：记住每条流体 / 能量规则上次搬通的面（玩家配了面就用配置，没配才查路由/扫面）；物品规则不记路由，无向句柄不可用时逐面探测；`REJECTED` 时重扫
- **JEI**：机器列表 / IO 列 / 仓库 / 背包的 R/U；FE 使用独立配料与信息页，不再映射成红石粉
- **诊断**：事件流（命令 / 产线 / 机器 / 仓库）+ 每条产线的搬运计数，`/idf diag` 查
- **界面 P0**：`/idf gui` 打开工作台骨架（底图 + 假数据），用于定尺寸与配方查看器留白

**未实现**

- 界面数据链路（P1：快照包 + 真实表格 + 规则编辑）
- 化学品（Mekanism 的 gas 等，只留枚举位）
- 主动模式（pattern / CPU）
- 区块强制加载（玩家走远后机器与容器所在区块会卸载，产线随之停）

**阶段收尾：多类型（物品 / 流体 / FE）**

- 已跑通：容器仓库（箱子、储罐、能量立方）与 AE2 仓库（物品、流体、FE）
- 资源身份包含物品数据组件；IO 规则分精确 / 模糊，旧规则默认模糊；仓库格数量按 k/M/G… 缩写、流体用 B
- 已知限制：化学品只有枚举位；模糊规则暂不支持 AE2 的百分比档（99/75/50/25）
- 下一步：界面 P1（详见 [docs/ui/P0.md](docs/ui/P0.md)）

## 指令

所有指令挂在 `/idlefactory` 下，短别名 `/idf`。**产线是主语**：`/idf line <线> <动作> <对象>`。

```
/idf gui                             打开工作台（P0：只有骨架，不含数据）
/idf create
/idf delete

/idf factory connect                 准星选机器，接入机器库
/idf factory disconnect [id]         删除机器（有规则时需再加 confirm）
/idf factory disconnect confirm      确认删除准星机器
/idf factory info                    工厂摘要 + 机器库 + 失联计数

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
/idf storage prune                  清理已缺失的容器（未加载的不算）
/idf storage priority <容器> <数字>  容器优先级（高的先填、低的先抽）
/idf storage info                   仓库类型、容器列表、资源
/idf storage get <item> [count]
/idf storage put <item> [count]

/idf diag                            健康摘要
/idf diag why <line> [machine]       体检：这条线为什么不动
/idf diag observe                    观测层状态（条目 / 预算 / 异常机器）
/idf diag tail [n]                   最近 n 条事件（默认 10，上限 20）
/idf diag watch <line> [秒]          汇总：资源速率 / 卡住组 / IO 耗时 / 睡眠
/idf diag watch <line> detail [秒]   详情：资源明细 + 每台机器的阻塞原因
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

`/idf diag watch <line>` 每秒一行汇总：按类型分开的资源速率、卡住组数、IO 轮次耗时（类似 MSPT）、睡眠状态。`detail` 模式每 N 秒推 3–5 行：资源明细 + 每台机器卡在哪一步。

## 资源类型

仓库与 IO 支持三类资源：

| 类型 | 单位 | 来源 |
|---|---|---|
| 物品 | 个 | `IItemHandler` / AE2 `AEItemKey`（含数据组件） |
| 流体 | B（内部 mB） | `IFluidHandler` / AE2 `AEFluidKey` |
| 能量 | FE | `IEnergyStorage`；AE2 侧经 Applied Flux 的 `FluxKey` |

默认搬运量是「塞满」：机器能收多少就搬多少。化学品只留枚举位，配了规则不执行。

Mekanism 的 NeoForge 能量适配器 `canExtract` / `canReceive` 恒为 true，真正的面限制在按面筛选后的 handler 里；而且 FE↔J 换算会在小数值上截断。所以面探测用「模拟搬运 1000 FE」而不是 1，否则会误判成不能抽。流体面探测同理。

## 架构

```
factory/                        工厂主体（服务端权威）
├── Factory / FactoryManager / FactoryStore / FactoryRuntime
├── FactoryService              指令入口
├── MachineActions              机器 IO 规则的写入口
├── FactoryRefs / FactoryMessages / FactorySnapshotBuilder
├── resource/                   资源模型
│   └── ResourceKind / ResourceKey / ResourceSpec
├── location/                   方块位置键
│   └── BlockKey
├── observation/                世界观测层（L1）
│   └── Observation / ObservationService / WorldEventBridge
├── capability/                 能力缓存
│   └── FaceTable / FaceCache / LevelCapabilityCache
├── probe/                      方块探测
│   └── BlockProbe / Health / WorkState
├── storage/                    仓库抽象与地址
│   └── Storage / StorageAnchor / MeAnchor / ContainerAnchor（AE2 / 容器）
│   └── ContainerStorage / DispatchingStorageResolver
├── machine/                    机器库
│   └── MachineLibrary / Machine / MachineIoGroup / MachineRef
├── line/                       被动产线（只存机器引用）
│   └── PassiveLine / LineLibrary / LineService
├── io/                         IO 执行
│   └── IoTransfer / GroupRunner / LineRunner / RunnerState / IoResult
│   └── RuleResolver / ItemCandidates / ItemVariantIndex
│   └── ResourceAdapter / ItemAdapter / FluidAdapter / EnergyAdapter
└── diag/                       诊断
    └── DiagEvent / DiagStats / DiagChecks / DiagRender / DiagService

integration/ae2/                AE2 适配（物品 / 流体）
integration/appflux/            Applied Flux 的 FE key（可选依赖）
menu/                           FactoryMenu（只持有 36 个玩家槽位）
client/
├── ClientFactoryLinks / ClientWandInput / FactoryLinkRenderer / ModKeyMappings
└── screen/                     工厂工作台唯一复杂屏幕包（类名统一 Factory 前缀）
    ├── FactoryScreen           壳：生命周期、网络转发、槽位、渲染/输入编排
    ├── FactoryScreenState      状态与派生视图（快照、过滤、排序、滚动、展开）
    ├── FactoryScreenGeometry   几何与命中（行高、卡片、仓库格、规则列）
    ├── FactoryScreenInput      鼠标/键盘/滚轮、IO 卡片、仓库手势
    ├── FactoryScreenContext    渲染/几何共用的只读视图
    ├── FactoryChromeRender     顶栏 + 右区静态底板
    ├── FactoryTableRender      左表
    ├── FactoryStorageRender    仓库格
    ├── FactoryMachineLibraryRender 机器库
    ├── FactoryTooltipRender    全部 tooltip
    ├── FactoryEditController / FactoryLineMenu / FactoryLineNameDialog / FactoryConfirmDialog
    ├── FactoryRuleStrip / FactoryIoCardControls / FactoryIoDetailLayout / FactoryIoDetailRender
    ├── FactorySearchQuery / FactorySort / FactorySortButtons
    ├── FactoryLabels / FactoryLayout / FactoryStatus / FactoryUiText
    ├── FactoryGuiTheme / FactoryGuiLayer / FactoryGuiTextures / FactoryNineSlice
    └── FactorySearchEditBox / FactoryStorageRepo / FactoryUiConfig / FactoryResourceIconRender
```

界面贴图不在手画：由 `tools/gui/gen_textures.py` 离线生成到
`assets/idlefactory/textures/gui/`，色值与 `FactoryGuiTheme` 一一对应。

核心约束：

- 工厂主体只认 `Storage` 接口，不 import `appeng`
- IO 只有一条写路径 `IoTransfer`：搬不进去的东西，永远不离开来源
- 仓库离线 = 空仓库，工厂表现为「没料」，不是「网络断线」
- **三层事实**：配置（写档）→ 观测（可重建）→ 路由/执行（瞬态）。读路径只读观测，不探世界
- **`observation` 包零工厂依赖**：它只认 `BlockKey`（位置），机器和容器都是它的消费者
- **未加载 ≠ 不存在**：存在性判断必须先过 `isLoaded`；失联只标记，不删配置
- **界面的静态外观来自贴图**：Java 只 blit 九宫格，不画边框与斜边

## 构建

```bash
./gradlew build      # 编译 + 打包
./gradlew runClient  # 起开发客户端
```

Mekanism 只用于本地运行期测试流体机器（`localRuntime`），不是编译依赖；首次 `runClient` 会从 Modrinth 下载。Applied Flux 用于测试 AE2 的 FE 仓库，同样是可选运行期依赖；加 `-PwithoutAppFlux` 可验证未安装附属时的降级路径。

## 已知的平衡改变

1.21.1 版 FE 能量走仓库且无损，因此对 FE 而言**移除了长距离输电损耗**这一平衡维度。这是有意的，记录在 ADR-006。EU 等其他能量单位不在支持范围内，相关机器仍需真实线缆。

IO 默认每 tick 一轮，能量 / 流体也是「塞满」；1 tick 一轮对性能有要求，卡顿时用 `/idf diag io 5` 降频。

## 文档

全部文档见 [docs/README.md](docs/README.md)（含「怎么判断一份文档是否过时」）。常用的：

- [决策记录](docs/DECISIONS.md) —— ADR，唯一活的设计约束来源
- [界面 P0](docs/ui/P0.md) —— 尺寸、贴图管线、怎么试、P1 清单
- [界面定稿设计稿](docs/ui/mockup-v2.png)
- [归档区](docs/archive/README.md) —— 画布时代的一切，只作决策留痕，**不要照着实现**

## 分支说明

`1.21.1neoforge` 从 M1 终点 `e8ba44b` 拉出并继续开发，是当前主线。旧 M2/M3 画布线已归档到 `archive/m3-canvas` 分支和 `archive/m3-20261025` tag；其 WIP 仍留在 `stash@{0}`，只作留档，**不要 pop 到新主线**。
