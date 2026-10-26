# 开发计划与代码分包（已归档）

> **🗄 已归档（2026-10-26）** —— M0–M6 分期是围绕 `core/graph`（节点 / 边 / 端口 / 带宽）
> 排的，那个模型已经被 `factory/` 包取代：机器之间不连线，产线是「机器 + 规则」的集合。
> 同时「终端物品」「装备奇点」两条入口也已在 P0 删除。
>
> **现行**：仓库根 [README.md](../../README.md) 的「当前状态」与「未实现」两节；
> 界面进度见 [../ui/P0.md](../ui/P0.md)。
>
> 状态：v0.1（2026-10-24）
> 关联：[ARCHITECTURE.md](ARCHITECTURE.md)、[DECISIONS.md](../DECISIONS.md)、[GUI.md](GUI.md)

## 0. 当前环境

| 项 | 值 |
|---|---|
| MC / NeoForge | 1.21.1 / 21.1.264 |
| ModDevGradle | 2.0.148 |
| Gradle | 9.2.1 |
| AE2 | 19.2.10（`modmaven.dev`，required） |
| JEI | 19.21.1.304（`maven.blamejared.com`，optional） |
| 资源类型 | 物品 + 流体（第一版） |

已完成：MDK、依赖、设计文档、GUI 原型（[gui-mockup.html](gui-mockup.html)）。

## 1. 开发阶段

阶段划分是**讨论产物**，不是承诺。每阶段结束都能跑、能看、能验。

### M0 骨架（详细计划见 [PLAN-M0-M1.md](PLAN-M0-M1.md)）

- 清掉 MDK 示例残留
- 注册终端物品 `TerminalItem`
- 注册 `MenuType`（`FactoryMenu`）与 `FactoryScreen`
- 网络包基础设施（`CustomPacketPayload` 注册）
- `core` 包的图模型骨架（`FactoryGraph` / `FactoryNode` / `PortRef`）

**验收**：右键终端打开空工作台，ESC 能关。

### M1 绑定与存储浏览（详细计划见 [PLAN-M0-M1.md](PLAN-M0-M1.md)）

- 奇点频率读取（`ENTANGLED_SINGULARITY_ID`）
- `Locatables.quantumNetworkBridges()` 查询 → `IGrid`
- Menu 持有 `IGrid`，服务端校验
- 存储栏：ME 库存快照（S2C）
- 绑定状态：未绑定 / 已绑定 / 网络丢失

**验收**：放奇点 → 打开终端 → 看到 ME 库存列表，能搜索。

### M2 机器接入与被动配置

- 终端右键机器 → 记录 `(dimension, BlockPos)`
- 机器数据落库（`SavedData`）
- 机器栏列表（S2C）
- 机器卡片：输入/输出端口配置（幽灵槽）
- 编辑意图包（C2S：加机器、改端口、删机器）

**验收**：接入一台熔炉，配置「输入铁矿石、输出铁锭」，重开 GUI 还在。

### M3 IO 执行

- 虚拟 ImportBus：从机器输出槽抽产物 → `MEStorage.insert`
- 虚拟 ExportBus：从 `MEStorage.extract` → 塞进机器输入槽
- 目标存量 / 积压停机
- 接入机器的区块加载
- 搬运速率上限

**验收**：机器真的开始工作，库存上涨，到达目标存量后停。

### M4 画布与表格

- 画布渲染：节点卡片、正交连线、网格
- 拖拽、选中、缩放、平移
- 表格视图（可编辑）
- 图/表联动

**验收**：能画一条 3 台机器的产线，切表能改配置。

### M5 主动模式

- pattern 绑定 UI（给机器写 pattern）
- 实现 `ICraftingProvider`，注册到 `IGrid`
- `pushPattern`：把输入塞进机器，塞不下的进 `sendList`
- 主动产线表格（队列、状态）

**验收**：AE2 CPU 下单，我们的机器执行，产物回 ME。

### M6 诊断与打磨

- 吞吐统计（滚动窗口）
- 瓶颈分析
- 积压 / 缺料提醒
- JEI R/U 浮层
- 视觉打磨（按 GUI.md）

**验收**：能看出哪台机器是瓶颈。

## 2. 分包计划

### 目标结构

```
src/main/java/io/github/crafteve/idlefactory/
├── IdleFactory.java              主类
├── IdleFactoryClient.java        客户端入口
├── Config.java                   配置
│
├── core/                         ★ 纯 Java，零 MC 依赖
│   ├── graph/                    FactoryGraph / FactoryNode / FactoryEdge / PortRef
│   ├── machine/                  MachineSpec / PortSpec / MachineConfig / StockLimit
│   ├── analysis/                 Throughput / Bottleneck
│   └── intent/                   编辑意图（纯数据）
│
├── menu/                         FactoryMenu（会话 + 权限 + 生命周期）
│
├── network/                      ModNetwork / Payloads（S2C 快照、C2S 意图）
│
├── registry/                     ModItems / ModMenus / ModCreativeTabs
│
├── machine/                      接入的机器与 IO
│   ├── MachineRegistry.java      已接入机器的索引
│   ├── MachineSlot.java          虚拟端口（读/写 capability）
│   └── MachineIO.java            每 tick 搬运
│
├── integration/ae2/
│   ├── Ae2Binder.java            奇点频率 → IGrid
│   ├── Ae2StorageView.java       ME 库存读取
│   └── Ae2CraftingProvider.java  ICraftingProvider 实现
│
└── client/
    ├── screen/                   FactoryScreen
    ├── widget/                   Widget / ItemSlotWidget / ScrollList
    ├── layout/                   Layout / Stack / Row / Column
    ├── canvas/                   FactoryCanvas / NodeRenderer / EdgeRenderer
    └── view/                     ClientFactoryView / SelectionState
```

### M0 实际创建的结构

第一版不铺满，只建需要的最小集合：

```
dev/idlefactory/
├── IdleFactory.java
├── IdleFactoryClient.java
├── Config.java
├── core/
│   └── graph/
│       ├── FactoryGraph.java
│       ├── FactoryNode.java
│       └── PortRef.java
├── menu/
│   └── FactoryMenu.java
├── network/
│   ├── ModNetwork.java
│   └── Payloads.java
├── registry/
│   ├── ModItems.java
│   └── ModMenus.java
└── client/
    └── screen/
        └── FactoryScreen.java
```

## 3. 包依赖规则

```
client → menu → network → core
  ↓        ↓
  └──── integration/ae2
           ↓
        machine
```

- `core` 不 import 任何 `net.minecraft.*`、`net.neoforged.*`、`appeng.*`
- `client` 不 import `machine`、`integration`（只通过包拿数据）
- `menu` 不 import `client`
- `integration/ae2` 是唯一允许 `import appeng.*` 的地方（`machine` 可以依赖它，但 `core` 不行）

**校验方式**：M0 先靠约定；M2 之后加一个 Gradle 任务，扫描 `core/**` 里是否出现 `net.minecraft` / `appeng`。

## 4. 明确不做（第一版）

- 自建存储后端（直接用 AE2）
- FE 能量、化学品（只做物品 + 流体）
- 自动读配方
- 地图视图（退出 GUI 就是 3D 地图）
- 自动放置机器
- 多方块机器（先只支持单方块）

## 5. 风险

| 风险 | 影响 | 应对 |
|---|---|---|
| 机器进度读不到 | 卡片显示不了进度 | 读不到就不画，不假装 |
| 奇点绑定失效 | 终端连不上网络 | 降级提示，允许重新绑定 |
| 区块未加载 | 机器不工作 | 接入时强制加载 |
| 多人同时编辑 | 状态冲突 | 意图带版本号，服务端权威 |
| GUI 复杂失控 | 维护成本 | 严格分包，逻辑下沉 core |
