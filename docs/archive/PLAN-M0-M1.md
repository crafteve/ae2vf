# M0 / M1 详细计划（已归档）

> **🗄 已归档（2026-10-26）** —— M1 的「装备量子纠缠奇点 → 终端读 ME 网络库存」整条链
> 在 P0 被删掉：界面改由 `/idf gui` 打开，仓库读 `factory.storage()`（AE2 与容器组走同一条路），
> 终端物品与玩家附件一起退场。
>
> **现行**：界面相关见 [../ui/P0.md](../ui/P0.md)；网络与仓库见
> [../DECISIONS.md](../DECISIONS.md) 的 ADR-014/015/019。
>
> 状态：已完结（M0/M1 已完成，随后被重构取代）
> 关联：[ROADMAP.md](ROADMAP.md)、[GUI.md](GUI.md)、[ARCHITECTURE.md](ARCHITECTURE.md)

## M0 骨架

### 目标

跑通「物品 → Menu → Screen → 网络包」整条链路，打开一个空工作台。

### 文件清单

```
src/main/java/io/github/crafteve/idlefactory/
├── IdleFactory.java              改：挂 DeferredRegister
├── IdleFactoryClient.java        改：客户端注册
├── registry/
│   ├── ModItems.java             新：终端物品
│   ├── ModMenus.java             新：MenuType
│   └── ModCreativeTabs.java      新：创造模式标签
├── item/
│   └── TerminalItem.java         新：终端物品
├── menu/
│   └── FactoryMenu.java          新：空 Menu
├── network/
│   └── ModNetwork.java           新：包注册
└── client/
    └── screen/
        └── FactoryScreen.java    新：空工作台
```

```
src/main/resources/assets/idlefactory/
├── lang/en_us.json               改：物品名
├── models/item/terminal.json     新：物品模型
└── textures/item/terminal.png    新：16×16 贴图
```

### 技术要点

1. **物品**
   - `TerminalItem extends Item`
   - `use()` 服务端打开 `FactoryMenu`
   - 用 `MenuProvider`（`player.openMenu(...)`）

2. **Menu**
   - `FactoryMenu extends AbstractContainerMenu`
   - M0 无槽位，`quickMoveStack` 返回空
   - `stillValid` 简化
   - 注册 `MenuType`（`IMenuTypeExtension.create` 或 `new MenuType<>(...)`）

3. **Screen**
   - `FactoryScreen extends AbstractContainerScreen<FactoryMenu>`
   - 画 480×300 面板：深色底 + 细边框 + 标题
   - ESC 关闭
   - 在 `RegisterMenuScreensEvent` 里注册

4. **网络包**
   - `ModNetwork` 持有 `PayloadRegistrar`
   - M0 注册一个空的 `PingPayload` 占位，验证通道可用

5. **贴图**
   - 16×16 PNG，像素风
   - 主题：终端面板，深底 + 青色线条
   - 生成方式不限（Python PIL / Java BufferedImage / 手写）

### 验收

- `gradlew build` 通过
- `gradlew runClient` 能启动
- 创造模式拿到终端
- 右键打开空工作台
- ESC 关闭

---

## M1 绑定与存储

### 目标

奇点绑定 ME 网络，存储栏显示库存，搜索可过滤。

### 关键设计：奇点放哪

不放在 GUI 常驻槽位里，放在**玩家数据**中，用浮层管理。

技术方案：NeoForge 1.21.1 的 `AttachmentType<ItemStack>`

```java
public static final AttachmentType<ItemStack> SINGULARITY_SLOT =
        AttachmentType.builder(() -> ItemStack.EMPTY)
                .serialize(ItemStack.CODEC)
                .build();
```

- 注册到 `NeoForgeRegistries.ATTACHMENT_TYPES`
- 装备：手持奇点右键（或终端浮层里的槽位拖入）
- 终端顶栏一个按钮 → 浮层 → 显示奇点槽
- 不依赖 Curios

为什么可行：附件是玩家数据的一部分，随玩家存档、跨维度、死亡策略可配。比 Curios 少一个依赖，比"藏在物品里"更符合「装备」直觉。

### 文件清单

```
├── player/
│   └── SingularitySlot.java       新：附件类型 + 读写
├── integration/ae2/
│   ├── Ae2Binder.java             新：频率 → IGrid
│   └── Ae2StorageView.java        新：ME 库存读取
├── menu/
│   └── FactoryMenu.java           改：持有 IGrid
├── network/payload/
│   └── StorageSnapshotS2C.java    新：库存快照
└── client/
    ├── screen/FactoryScreen.java  改：存储栏
    └── widget/ItemGrid.java       新：物品网格
```

### 技术要点

1. **奇点槽**
   - `AttachmentType` 注册
   - 右键装备 / 浮层拖入
   - 浮层显示当前频率

2. **绑定**
   - 读奇点 `AEComponents.ENTANGLED_SINGULARITY_ID`
   - `Locatables.quantumNetworkBridges().get(serverLevel, freq)`，再试 `-freq`
   - `QuantumCluster.getActionableNode().getGrid()`
   - 失败降级：未绑定 / 网络丢失

3. **存储快照**
   - `grid.getStorageService().getInventory().getAvailableStacks()`
   - 序列化成 `List<{key, amount}>`
   - S2C 发给客户端

4. **存储栏**（190px 宽，照 AE2 终端的骨架）
   - 搜索框：复用 AE2 的 `RepoSearch` + `GridInventoryEntry`，继承它的 `@mod` / `#tag` / `*id` 语法，以及拼音搜索模组对 AE2 的适配
   - 物品网格：9 列 × 18px，1px 内凹边框（深色描边 + 内侧高光）
   - 悬停：格子背景提亮、边框变亮
   - 选中：青色边框 + 轻微外发光
   - 右侧竖滚动条
   - 底部：玩家背包（3×9 主背包 + 快捷栏，照 AE2 终端）
   - 不做左侧按钮列

### 验收

- 放奇点 → 打开终端 → 看到 ME 库存
- 搜索能过滤（含 `@mod` 语法）
- 悬停 / 选中动画可见
- 底部背包能显示玩家物品
- 拔掉奇点 → 提示「未绑定」
- 奇点槽在重登后还在

---

## 执行顺序

1. M0：子代理实现，`build` 通过后我验收
2. M1：等 M0 跑通再开

## 不做

- M0 不写任何产线逻辑
- M1 不做「全部物品」Tab（需要 JEI 注册表，放 M6）
- M1 不做奇点浮层的动画
