# 放置工厂 GUI 设计（讨论记录）

> 状态：讨论中（2026-10-24 起）
> 关联：[ARCHITECTURE.md](ARCHITECTURE.md) 第 4 节、[DECISIONS.md](DECISIONS.md) ADR-014 / ADR-015

## 0. 先定性：这是编辑器，不是容器界面

原版 MC 的 GUI 模型是「容器 + 槽位」：`AbstractContainerMenu` 持有 `Slot`，客户端 `Screen` 画槽位，玩家在槽位间搬东西。

放置工厂的 GUI 不是这个模型。它需要的是：

- 一块**画布**：节点、连线、缩放、平移
- 一组**面板**：机器列表、库存、配置、分析
- 一个**编辑器**：选中、拖拽、框选、右键菜单
- 少量**真实槽位**：终端的绑定槽（奇点）

所以不能照搬 `Slot` 那套，必须自己造一层。

## 1. 分层

```
┌─────────────────────────────────────────┐
│ client/screen    FactoryScreen           │  渲染 + 输入
│ client/widget    控件（Button/List/...）  │
│ client/layout    布局（Stack/Row/...）    │
├─────────────────────────────────────────┤
│ menu             FactoryMenu             │  会话 + 权限 + 同步
│ network          自定义包                 │
├─────────────────────────────────────────┤
│ core             FactoryGraph / Analyzer │  纯 Java，零 MC
└─────────────────────────────────────────┘
```

原则：**screen 不知道业务规则，core 不知道渲染**。

## 2. Menu 的职责（待定）

三种方案：

**A. Menu 承载全部状态**
用 `ContainerData`（int 数组）同步。缺点：只能同步 int/short，图数据根本塞不下。

**B. Menu 只做会话锚点**
Menu 负责：打开时建立「玩家 ↔ 工厂」会话、校验权限、生命周期；数据用自定义包（`CustomPacketPayload`）同步。

**C. 完全不用 Menu**
纯 `Screen` + 包。简单，但失去服务端生命周期钩子（玩家关闭 GUI、断线时的清理）。

倾向：**B**。

## 3. 数据同步

- 打开时：服务端发一次**全量快照**（图 + 机器列表 + 库存摘要）
- 编辑时：客户端发**意图**（「移动节点 A 到 x,y」），服务端校验后广播**增量**
- 关闭时：不额外同步

关键：客户端永远不直接改本地模型，只发意图；服务端是唯一真相。

## 4. 布局系统（待定）

两种：

**绝对坐标**：每个控件写死 x/y。简单，但适配差、难维护。

**声明式**：`Stack`（层叠）、`Row/Column`（线性）、`Canvas`（自由）。每个控件实现：

```java
Size measure(Constraints c);
void layout(int x, int y, int w, int h);
void render(GuiGraphics g, int mouseX, int mouseY, float partialTick);
```

倾向：**声明式，但只做三种容器**，不要做完整的 Flexbox。

## 5. 控件体系（待定）

- 原版 `AbstractWidget`：事件驱动，和声明式布局不搭
- 自造 `Widget` 基类：统一 measure/layout/render/input

倾向：**自造**。原版控件只在必要处复用（`EditBox` 可以包一层）。

## 6. 渲染

- 视口裁剪：只渲染可见区域的节点和连线
- 连线：`GuiGraphics` 画线段（可能需要自己拼 `blit`，或用 `RenderSystem`）
- 文本：缓存 `Component` → `FormattedCharSequence`
- 缩放：画布支持 0.5x–2x，用 `PoseStack` 变换

## 7. 输入路由

```
Screen.mouseClicked
  → 布局树自顶向下 hitTest
  → 控件处理（返回 true 则拦截）
  → 否则交给画布（拖拽 / 框选 / 缩放）
```

右键菜单、快捷键同理。

## 8. 包结构（建议）

```
dev.idlefactory
├── core/                 纯 Java，未来独立成模块
│   ├── graph/            FactoryGraph, Node, Edge, Port
│   ├── machine/          MachineConfig, MachineRef
│   └── analysis/         Analyzer, Throughput
├── menu/                 FactoryMenu
├── network/              S2C / C2S 包
├── client/
│   ├── screen/           FactoryScreen
│   ├── widget/           Widget, Button, ListView, ScrollPane
│   ├── layout/           Stack, Row, Column, Canvas
│   └── render/           NodeRenderer, EdgeRenderer, GridRenderer
└── registry/             MenuType, Item, Block...
```

## 9. 待定问题

1. Menu 用 A / B / C 哪种？
2. 布局绝对坐标还是声明式？
3. 控件自造还是复用原版？
4. 图数据存哪：`SavedData` / `BlockEntity` / 玩家附件？
5. 编辑是「即时同步」还是「草稿 + 提交」？
6. 画布缩放范围、平移方式（拖拽 vs WASD）？
7. 节点多了之后，是否要做 LOD（低缩放只画方块）？
