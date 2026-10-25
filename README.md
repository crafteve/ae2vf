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
  → /idf line create
  → /idf line add
  → /idf line manage            配输入 / 输出
  → /idf line start
```

之后由服务端 tick 驱动：仓库 → 补料 → 机器加工 → 抽产物 → 回仓库。

**已实现**

- **工厂实例**：一人一个，确定性 UUID，`FactoryStore` 存档级持久化
- **ME 仓库**：主手量子纠缠奇点绑定，`Storage` 抽象 + AE2 适配，离线降级为空仓库
- **机器库**：准星接入 / 解除，校验 `IItemHandler` / `IFluidHandler`，每 5 秒对账清悬空引用
- **被动产线**：create / delete / add / remove / start / end / info / manage
- **IO 规则**：物品 id + 绝对六向面，输入输出各自独立
- **IO 执行**：`feed` / `pull` 事务，`LineRunner` 组 / 线两级睡眠

**未实现**

- 终端 UI（M1 的空界面还在，没接新架构）
- 流体 / 能量 / 化学品
- 主动模式（pattern / CPU）

## 指令

所有指令挂在 `/idlefactory` 下，短别名 `/idf`。

```
/idf create
/idf delete
/idf factory connect                 准星选机器，接入机器库
/idf factory disconnect [id]         准星移除 / 按短 id 远程移除
/idf factory info                    工厂摘要 + 机器库
/idf line create <name>
/idf line delete <line>
/idf line add <line> <machine>
/idf line remove <line> <machine>
/idf line start <line>
/idf line end <line>
/idf line info <line>
/idf line manage <line> <machine> info
/idf line manage <line> <machine> input  add <item> [face]
/idf line manage <line> <machine> input  remove <item>
/idf line manage <line> <machine> output add <item> [face]
/idf line manage <line> <machine> output remove <item>
/idf storage connect                 主手奇点绑 ME 仓库
/idf storage info
/idf storage get <item> [count]
/idf storage put <item> [count]
```

`<line>` / `<machine>` 都带 Tab 补全；`face` 取 `any` / `north` / `south` / `east` / `west` / `up` / `down`。

## 架构

```
factory/                        工厂主体（服务端权威）
├── Factory / FactoryManager / FactoryStore / FactoryRuntime
├── FactoryService              工厂、机器库、仓库的指令入口
├── FactorySnapshotBuilder      摘要
├── storage/                    ME 仓库抽象
│   └── Storage / StorageLink / StorageAnchor / StorageResolver / EmptyStorage
├── target/                     机器库
│   └── MachineLibrary / MachineTarget / MachineChecks
├── line/                       被动产线
│   └── PassiveLine / IoGroup / ItemSpec / LineLibrary / LineService
└── io/                         IO 执行
    └── IoTransfer / GroupRunner / LineRunner / RunnerState / IoResult

integration/ae2/                AE2 适配（唯一 import appeng 的地方）
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

## 已知的平衡改变

1.21.1 版 FE 能量走仓库且无损，因此对 FE 而言**移除了长距离输电损耗**这一平衡维度。这是有意的，记录在 ADR-006。EU 等其他能量单位不在支持范围内，相关机器仍需真实线缆。

## 文档

- [架构说明](docs/ARCHITECTURE.md)（旧版设计，部分内容已被 `factory` 包取代）
- [决策记录](docs/DECISIONS.md)

## 分支说明

`refactor/factory-core` 从 M1 终点 `e8ba44b` 拉出，旧 M2/M3 的 WIP 保存在 `stash@{0}`，切回 `1.21.1neoforge` 后可 `git stash pop` 恢复。
