# AGENTS.md

## 一、项目概述

### 1.1 是什么

AE2 Void Factory（`ae2vf`）：AE2 附属式的大型工厂 IO 管理模组。把"把机器接进自动化网络"从摆接口、铺线缆、调 IO 面变成在终端里配置。

只接管机器与仓库之间的搬运。机器配方、能量、加工时间一律不变。

### 1.2 核心设定

- 服务端权威：IO 全在服务端；GUI 只是监视器，只渲染 + 发意图；menu 是登录壳。
- 工厂不是数据库：仓库只支持 AE2 网络（`MEStorage`）；仓库离线 = 没料，不是断网。
- 被动 IO：玩家给机器配输入输出规则，每 tick 搬运。主动合成：AE2 pattern / CPU 下单，已实现——节点带虚拟样板供应器，把样板广告给 CPU、接住派单推输入并回收产物；被动模式只做被动搬运，主动模式在被动搬运之外参与合成，写入第一张样板时自动转主动。
- 基础概念：节点（1..N 台机器，带自己的 IO 规则）；**没有产线对象**，分组只是 UI 视图；失联是运行时状态，不写档。
- **机器 = 一个方块**。多方块已删除：一台机器不再占多个方块，节点靠成员机器列表承担「1 台 / 多台」的区别。
- 资源身份用 AE2 的 `AEKey`：物品带完整数据组件（`AEItemKey`），流体按注册名（`AEFluidKey`），不自己造平行的资源类型。
- **资源类型的覆盖范围 = AE2 原生 + 已安装的 AE 生态附属，模组自己不新增任何资源类型**。FE 以 Applied Flux 为前提，化学品以 Applied Mekanistics 为前提；没装对应附属就没有那类资源，不写降级表示、不造平行 key。装了时它们的 key 都是 `AEKey`，走和物品 / 流体完全相同的规则。
- IO 规则分精确 / 模糊：模糊忽略物品组件，旧规则默认按模糊读入。
- 观测层是第二事实层：方块在不在、是什么方块 / 方块实体、是否在干活；不存能力面。界面只读观测。
- **写操作只有一条路**：节点 / 规则 / 绑定杖的写入全部走 `command` 包（世界命令用 `containerId = -1`），门禁、执行、落盘、回执各管一段；没有并行的 Service 写入口。
- 无方块。入口只有两个终端物品右键：被动 IO 管理终端、节点管理终端；**没有聊天指令入口**（`/idf` 已整体删除）。

### 1.3 技术栈

| 项 | 值 |
|---|---|
| MC / NeoForge | 1.21.1 / 21.1.264 |
| Java / Gradle / ModDevGradle | 21 / 9.2.1 / 2.0.148 |
| Parchment | 2024.11.17 |
| modid / 版本 / 分支 | `ae2vf` / 0.1.0-beta / `view-client-refactor-20261120`（视图表与客户端重构；从快进后的 `1.21.1neoforge` 切出） |
| AE2 | 19.2.10，`implementation`，硬依赖 |
| JEI | 19.21.1.304，`compileOnly + runtimeOnly`，可选 |
| Mekanism | 10.7.19.85，仅 `localRuntime`，本地测试用 |
| Applied Flux | 1.21-2.1.5-neoforge，`compileOnly + localRuntime`，可选；**FE 功能的唯一来源**，未安装时模组不支持 FE |
| Applied Mekanistics | `compileOnly + localRuntime`，可选；**化学品功能的唯一来源**，未安装时模组不支持化学品 |

## 二、项目结构

### 2.1 分包秩序（硬要求）

包即层。先定包，再写类；答不出属于哪一层，就不写。

| 包 | 负责 | 不负责 |
|---|---|---|
| `observation` | 方块级观测表、观测调度、批量变化、工作状态 | 配置、搬运 |
| `machine` | 机器（一个方块）、`IoRuleSpec` / `MatchMode` 规则类型、`MachineHealth` 纯函数 | 世界探测、搬运 |
| `storage` | 仓库解析（只解析 AE2 `MEStorage`） | IO 事务 |
| `io` | 遍历、取面、搬运事务 | 配置、持久化 |
| `factory` | 工厂实例与索引（`Factory` / `FactoryLibrary`）、装配（`FactoryLoader`）、生命周期（`FactoryLifecycle`）、每 tick 编排（`FactoryRunner`）、存档（`FactoryStore`）、短 id 解析（`FactoryResolver`）、表单（`IoFormBuilder`） | 存储实现、搬运实现、活节点、连锁查找、网络发包 |
| `runtime` | 活节点：`LiveNode` / `LiveNodeLibrary` / `LiveNodeApply` / `LiveNodeRunner` / `LiveNodeCraft` / 样板视图 | 配置、搬运 |
| `node` | 节点配置数据：`Node` / `NodeIo` / `NodeIoRules` / `NodeCraft` / `NodeDispatchQueue` / `NodeDispatchOrder` / `NodeLibrary` / `NodeCodec` | 世界探测、搬运、存档根 |
| `claim` | 方块占用表（机器 / 仓库锚点唯一性） | 世界探测 |
| `command` | 唯一写入口：`FactoryCommand` 信封、`CommandController`、门禁、执行、拓扑、绑定杖、回执与原因 | 存储实现、界面 |
| `view` | 视图表：`ViewTable` / `ViewHeader` / `ViewNode`（内嵌 `Activity`）/ `ViewNodeCore` / `ViewIoRule` / `ViewMachine` / `ViewTableDiff` / `ViewNodeDiff` / `ViewTableBuilder` | 持久化、世界探测、配置写入 |
| `key` | 身份键：`AeKeyAdapter`（AEKey ↔ 物品栈 / 标签 / 流）与 `BlockKey`（维度 + 坐标） | 业务逻辑 |
| `config` | NeoForge 配置注册与读取（仅模组配置可用 config 命名） | 业务逻辑 |
| `registry` | 纯注册：物品、菜单类型、创造栏、网络负载（`VoidFactoryItems` / `VoidFactoryMenus` / `VoidFactoryCreativeTabs` / `VoidFactoryPayloads`） | 业务逻辑 |
| `menu` | 会话层：`node/` / `passiveio/` / `wand/` 三组 Host+Menu，终端偏好契约（`TerminalSetting` / `TerminalSortMode` / `PassiveIoViewMode`），门禁 `MenuGate` | 工厂数据、界面绘制 |
| `network` | 协议、负载、`ViewPort`（视图出口）、`CommandPort`、`ObservationNoticeListener`（失联提示） | 业务判断 |
| `client` | 客户端入口与四个子包：`world`（世界高亮）、`wand`（绑定杖输入与草稿）、`network`（报文投递）、`screen`（界面） | 工厂数据、服务端状态 |
| `client.screen` | 两个终端界面（`terminal.node` / `terminal.passiveio`）、共享组件（`paint` 卡片与键帽、`theme` 层序与贴图、`text` 文案、`search`、`sort`、`wand`） | 服务端状态 |
| `integration` | AE2 / Applied Flux / JEI / jecharacters 适配 | 核心逻辑 |

命名后缀固定：

`Service` 业务入口 · `Runner` 每 tick 执行 · `Store` 存档容器 · `Manager` 内存索引 · `Library` 集合 · `Ref` 不可变引用 · `Anchor` 仓库地址 · `Resolver` 后端解析 · `Adapter` 能力适配 · `Builder` 视图组装 · `Handler` 消息处理 · `Stats` 统计 · `Render` 渲染 · `Messages` 文案 · `Refs` 引用解析 · `Planner` 规划器（择时、排序与分配） · `Executor` 执行器（无跨轮记忆） · `Ledger` 账本（变化序列） · `Analyzer` 分析器（账本、结论、速率与经验面的唯一维护者） · `Pick` 执行侧查询器（探面 / 容量 / 变体，只读不写）

值类型与结构后缀（第二张表，和角色后缀同样硬）：

`Form` 表单（每 tick 的工作清单，内嵌 `Entry` / `Entry.Key`） · `Job` 一次性搬运任务（机器 / 方向 / 面 / 资源 / 量，不带结果） · `Order` 一单待投递（跨 tick、可拆给多台机器） · `Queue` 多单排队（内存与存档的唯一写入口） · `State` 一 tick 的输入或快照 · `Side` 方向 · `Mode` 互斥取值（**两值开关与多档位都叫 `Mode`，不设 `Switch`**） · `Reason` 一次操作的原因（可含成功值） · `Result` 单个目标一次操作的返回值 · `Spec` 不可变配置值组合 · `Key` 不可变身份 · `Status` 推导结论（能不能干活） · `Health` 探活结论（在不在） · `Group` 同类元素的有序集合 · `Hint` 经验值 · `Conclusion` 历史结论

硬条款：

1. 包名只用单数领域名词。禁止 `util`、`common`、`misc`、`helper`。
2. 一个概念只建模一处：资源身份 = AE2 的 `AEKey`，观测键 = `BlockKey`，IO 规则 = `IoRuleSpec`，占用 = `BlockOccupancyTable`。
3. 依赖不得成环。跨包 import 不逐条登记，只守这一条；历史上的 `storage → network`、`factory → integration.ae2`、`network.payload → client.network`、`io → node` 只是既成事实，不再是需要维护的例外清单。
4. 发包只从 `network`，服务端不得直接发负载（`player.openMenu` 之外的网络动作都走负载）。
5. `client.*` 只在客户端加载；服务端类不得引用。负载的客户端处理放 `client.network` 的 Handler。
6. 数据 record 只存 id、坐标、枚举；不持有 `Level`、`BlockEntity`、capability 等活对象。资源 key 一律用 AE2 的 `AEKey`，它本身是不可变值对象，不需要额外例外。
7. 单文件超过约 400 行先给拆分方案，不边想边改。
8. 新建包、新建文件、改数据层交互结构（record 字段 / NBT / 网络字段 / 跨包签名），计划里必须逐项列出：文件、职责、依赖方向、迁移顺序。等你确认后再动手。
9. **类名 = 限定词 + 后缀。** 结构后缀只取上面两张表；功能名（`Command`、`Gate`、`Decoder`、`Codec`、`Source`、`Kind` 这类说明职责的词）不受后缀表约束，但必须说明它干什么。限定词说明「谁的 / 哪个子领域的」，只有三种来源：层名（`Io`）、子领域（`Dispatch`）、主题或功能词（`Blocking`、`Multiplier`、`Provider`、`Active`、`Passive`、`Face`、`Capacity`、`Variant`、`Port`）。包的领域根类型（`Node`、`Machine`）可以裸名，其余一律要有可读限定词：`Analyzer` 不合格，`IoAnalyzer` 合格。状态三分不许串：`Health` 只答在不在，`Status` 只答能不能干活，`State` 只答此刻是什么。

### 2.2 三层信息：配置 / 观测 / io

- 配置（最高信息，写档）：节点的成员机器与 IO 规则、`factory` 的节点库与仓库地址。只描述搬什么，不执行。
- 观测（第二信息，不写档）：`observation` 判断方块在不在（PRESENT / UNLOADED / MISSING / NO_DIMENSION）、是什么方块 / 方块实体、当前是否在干活。界面与执行门禁都只读它，读路径不探世界。
- 执行（第三信息，瞬态）：`io` 判断机器此刻能不能收、能不能抽，跑 `IoRunner → ActivePlanner / PassivePlanner → IoExecutor → PortExecutor`；结果写进 `IoAnalyzer` 的账本与结论（不写档），不回写配置。

各包职责：

- `factory`：工厂实例与编排。`Factory` 持仓库缓存与节点库；`FactoryLoader` 管读档 / 装配，`FactoryLifecycle` 管启停与 tick 顺序；`FactoryRunner` 每 tick 按固定顺序推进；`FactoryStore` 负责两条落盘路径（`put` / `flushRuntime`）；写操作全部在 `command` 包，工厂层不再有行政入口。
- `command`：唯一写入口。`CommandController` 收 `FactoryCommand` 信封；`CommandGate` / `CommandWandGate` 校验身份与作用域；`CommandApply` / `CommandTopology` / `CommandWand` 只改内存；`CommandPort` 落盘、推快照、发回执。
- `io`：执行层。`IoForm`（内嵌 `Entry`）是唯一工作清单，不在表上的条目执行层不认识；`IoRunner` 是唯一入口，按 回收 → 主动 → 被动 依次跑；`PassivePlanner` 按规则级排期并产出逐条 `IoJob`；`ActivePlanner` 把 `DispatchOrder` 分到多台机器（顺序 / 轮询、半份优先、卡单退避）并定回收节奏；`IoExecutor` 是机器侧唯一写入口（`move` / `fill` / `refund` / `sweep`）；`FacePick` 只选面（配置面 → 经验面 → 逐面探），`CapacityPick` 只算份数，`VariantPick` 查模糊变体，`PortExecutor` 提供按面的 `MEStorage` 缓存；`IoAnalyzer` 是账本、结论、速率与经验面的唯一维护者。**`io` 可以依赖 `node`，反向则成环**：配置数据归 `node`，活节点在运行层包，执行层只取数据、不持有运行对象。

调用链（被动）：`ServerTick → VoidFactoryServer → FactoryLifecycle → FactoryRunner（观测 → 节点健康 → 锚点 → 活节点 → 表单）→ IoRunner → PassivePlanner（FacePick / VariantPick）→ IoExecutor.move → PortExecutor → MEStorage`。

调用链（主动，旁路）：`AE2 CPU → LiveNodeCraft.pushPattern（校验 + 拆份 + 入队）→ 同一 tick 内 IoRunner → ActivePlanner（CapacityPick / FacePick）→ IoExecutor.fill → 机器`；产物由 `IoRunner` 调 `IoExecutor.sweep` 扫回网络。

执行顺序固定：回收 → 主动 → 被动。

失败原因沿链上抛（`io/IoReason`，9 值）：`RUN` 正常、`SLEEPING` 择时未跑、`NO_FACE` 探不到面、`BUDGET_OUT` 预算耗尽、`NO_MATERIAL` 没料、`FULL` 满了、`REJECTED` 有容量却不收、`ERROR` 异常、`FACE_CONFIG_INVALID` 玩家配的面没有这个能力（不兜底、不换面）。`REJECTED` 说明面不对，清掉经验面下一轮重探；`FULL` 是正常状态，保住面。原因只回答「一次搬运尝试为什么是这个结果」，世界态（在不在、加载没、类型对不对）由 `MachineHealth` 回答。

### 2.3 数据层

- 不可变优先：`record` + `withXxx` 返回新实例。
- 存档读写对称：工厂根在 `FactoryStore`，节点在 `NodeCodec`；键名常量化，缺字段走默认值兼容旧档；样板聚合键写 `Craft`，读侧兼容旧的 `Provider`。
- 工厂 id 由拥有者派生（`FactoryStore.idOf`），存档不写 id、不写序号。
- 对外返回不可变集合或防御性拷贝，不暴露内部可变表。
- 上限、间隔、容量一律具名常量，并在解码侧校验。
- 枚举分支用穷尽 `switch`，不留 `default` 掩盖新增值。
- 禁止布尔盲参数：不写 `foo(true, false)`，用枚举或具名类型。
- 物品身份 = `AEItemKey`（注册名 + 数据组件），相等性直接用它自己的实现，不再自己对齐。
- `IoRuleSpec` 的 `MatchMode` 决定规则是否比较组件；旧档缺 `Mode` 的物品规则按 `IGNORE_COMPONENTS` 读入。
- 规则落盘时整个 key 交给 `AEKey.toTagGeneric` / `fromTagGeneric`，网络统一走 `AEKey.STREAM_CODEC`（经 `AeKeyCodec` 薄包装）。

### 2.4 其余包

`runtime` 活节点 · `claim` 占用表 · `view` 视图表（`ViewTable` / `ViewNode` / `ViewIoRule` / `ViewMachine` / `ViewTableDiff` / `ViewTableBuilder`） · `command` 唯一写入口 · `registry` MenuType · `menu` 会话层（`node` / `passiveio` / `wand` 三个子包 + 偏好契约 + `MenuGate`） · `network` 版本与线路上限（`NetworkVersion` / `NetworkLimit`）、视图出口（`ViewPort`）、命令端口（`CommandPort`）、失联提示（`ObservationNoticeListener`），负载注册在 `registry.VoidFactoryPayloads`，编解码器在 `network.codec` · `client` 客户端入口，四个子包 `world` / `wand` / `network` / `screen` · `client.screen.terminal` 两个终端（`node` / `passiveio`），共用 `TerminalModel` / `TerminalSource` / `TerminalChrome`，客户端类名不带 `Factory` 前缀 · `integration` 外部模组适配，反射失败必须降级。

## 三、规模、性能、界面

### 3.1 规模与性能

目标体量：上百台机器、16 个容器。

- 退避：规则级（不是机器级），按上一轮原因分档——`NO_MATERIAL` 5 tick、`FULL` 10 tick、`NO_FACE` 20 tick、`REJECTED` 20×连续拒收（封顶 100）、`ERROR` 20→100、`SLEEPING` 200 tick；一次都没试过的条目立刻跑。观测按状态退避。主动单另有 5 / 10 / 20 / 40 / 80（封顶 100）的卡单退避。
- 预算：观测三条通道各默认 128 次/tick（`VoidFactoryConfig.observationHealthBudgetPerTick` / `observationWorkBudgetPerTick` / `observationEventBudgetPerTick`，下限 1，**不设硬上限**）。
- 复探：健康通道 120 tick（MISSING 逐渐退到 1200），工作状态 5 / 40 / 120 → 1200 tick；读档首 tick 对账一次，之后每 1200 tick。
- IO：固定每 tick 一轮（原本可由 `/idf diag io` 调整，指令删除后固定）。
- 上限：节点 4096、单节点机器 256、全厂机器 8192、规则每侧 16、仓库快照 1024。
- 读路径不探世界：展示与界面只读观测缓存。
- 索引 O(1)：`byId`、`byPosition`；不每 tick 新建集合、不重复分配。
- 所有遍历有上限护栏；失联通知按工厂聚合、每 tick 最多一条；心跳走 trace。
- 模糊物品变体：索引 TTL 20 tick、单 id 64 个、单规则 8 个 / 单机器 16 个变体每 tick；仓库快照按 512 KiB 分片。
- 卡顿先降频，再考虑加预算。

### 3.2 界面

风格：AE2 终端浅色、立体、科幻。不发挥。

- 颜色归属组件：卡片、键帽、状态、tooltip、输入框、各终端 Render 各自持有色值常量；状态四色集中在 `paint/StatusColor`（`RUN / WARN / ERROR / IDLE`）。不再有集中主题类。
- 立体感只来自贴图：九宫格边框、内凹斜边、上/左深阴影、下/右高光、深色机器卡片。表格外层保留「深色 + 白色」双像素框。
- 嵌套立体：下拉框本身是立体框，选项各自也是小立体框。
- 1px 像素精度：线条不糊、不对齐就重画；槽位保留格子感与悬停/选中动画。
- 图标主导，少文字：物品、机器、状态优先用图标 + 颜色 + 动画表达。
- 排序入口固定在两个终端的左侧工具栏：帮助 → 终端高度 → 档位 → 方向 →（被动 IO 再加视图切换）。档位 / 方向按钮沿用 AE2 工具栏按钮规格；自绘图标（状态 / IO 规则数 / 距离）由 `tools/gui/gen_textures.py` 生成，16×16、白 `#F2F2F2` + 右下 1px `#4D4D67` 阴影，Java 只 blit。
- 状态表达：灯色 + 文字色 + 1px 状态条；列宽自适应，压缩机器名与状态，信息列优先。
- 贴图由 `tools/gui/gen_textures.py` 离线生成，Java 只 blit。禁止手改 PNG；改色必须同步生成脚本与使用该贴图的组件常量。
- 流体 / FE 图标按 AE2 风格：流体 still 贴图染色，FE 用 Applied Flux 图标（未装时绿色水贴图兜底）；物品用原版装饰层补耐久条。
- 仓库格数量：物品 / FE 最多 4 字符、流体最多 5 字符，缩放 0.666；完整精度只放 tooltip 与速率。
- 悬停高亮 = 浅蓝填充 + 1px 描边；描边必须在图标之后画，否则会被不透明的流体贴图盖住。
- 几何只在各自的 `*Layout`（`NodeLayout` / `PassiveIoLayout`）。溢出先让位，不许压到相邻区域。
- 绘制层级由各终端屏幕自己的 `draw` 顺序决定：底 → 内容 → 物品 → 数量 → 浮层 → tooltip。浮层必须高于数量层，否则会被物品数量盖住；tooltip 由原版固定在最后。不要在调用点写 z 魔法数。
- 尺寸问题以游戏内截图为准；留白可用 `-Pgutter=64` 或 `-Dae2vf.gutter` 现场调。

### 3.3 便利性

- 没有指令入口：节点与规则全部在两个终端里操作，绑定杖负责世界里「点方块」这一半。
- 表格可展开规则、可搜索；搜索沿用 AE2 语法并接 jecharacters 拼音兼容层。
- 两个终端左侧工具栏提供六档排序（名称 / ID / 机器台数 / IO 规则数 / 状态 / 距离）与升降序切换；偏好写在终端物品的 `ae2:exported_settings` 组件上，两个终端各自记忆。
- JEI R/U 必须覆盖机器列表、IO 列、仓库、背包；FE 使用独立配料（`ae2vf:fe`）与信息页，R/U / 收藏不得落到红石粉。

## 四、编码规范

### 4.1 命名

Java 惯例：类 PascalCase、方法/字段 camelCase、常量 UPPER_SNAKE、包小写。领域词用 Factory、Machine、IO 规则、Observation、Storage、Anchor、Ref、Runner、Planner、Executor、Ledger、Analyzer，不用 `Target`；`Line` 只作为「订单里的一行」，不再引入产线。

### 4.2 注释

- 全部中文，句末用「。」。
- 每个类与公开方法必须 Javadoc，写清：这个函数/代码块为哪个功能加的、为什么这么做、参数与返回含义。
- 复杂分支、魔法数、绕过坑的写法必须有块注释说明意图。
- 禁止复述代码的注释，禁止 emoji，禁止分割线艺术字。允许 `// ----` 分段。
- 注释缺失视为代码未完成。

### 4.3 编码与工具

- 全仓库 UTF-8 无 BOM；行尾交给 `.gitattributes`。
- 看到 `?`、U+FFFD 或「涓枃」式乱码即视为失败：修编码重做，不采信那一次结果。
- 改代码优先用 `edit` 工具做精确替换。
- **允许 bash 批量替换，禁止 pwsh 批量替换**：bash（MSYS）的 UTF-8 处理可靠，pwsh 历史上写坏过中文注释。
- 批量替换必须遵守三条：替换前 `git stash` 或确认已提交、替换后立刻 `git diff` 全量检查中文注释有没有变乱码、有损坏就 `git checkout` 回退重来。
- 替换标识符时要防误伤：`ResourceKey` 同时是 `net.minecraft.resources.ResourceKey`（维度键），批量替换前必须先排除这类同名异库的符号。
- PowerShell 读写中文显式 `-Encoding UTF8`；控制台乱码先设 `[Console]::OutputEncoding`。

### 4.4 结构

- 纯静态工具类：私有构造 + 说明。
- 空值用 `org.jetbrains.annotations.Nullable`。
- 游戏循环内异常必须降级，不外抛；快照失败返回空快照。
- 不新增 AE2 之外的硬依赖；可选模组一律 `compileOnly + localRuntime`。
- Applied Flux 只能通过 `AppFluxKeys.Bridge` 懒加载；加 `-PwithoutAppFlux` 可验证未安装路径。

### 4.5 网络

`record` + `TYPE` + `STREAM_CODEC`。服务端重校验客户端输入：数量、资源、容器 id 一律不信。改包结构必须递增 `PROTOCOL_VERSION`。

## 五、流程与 Git

### 5.1 先计划后执行

1. 调查代码与 ADR。
2. 写计划：改哪些文件、为什么、依赖方向、验证方式、风险。
3. 等确认。没确认不动手。
4. 执行，对照计划逐项自查。
5. 计划有缺口先说明，再改。

重构必须先给包结构、职责边界、迁移顺序，禁止边想边改。可以分段执行。卡住或边界不清先问，不硬搞。

### 5.2 验证与提交

- `.\gradlew.bat build` 必须通过（CI 同款 `./gradlew build`）。
- 每次编译成功后立即本地提交，不攒到最后；一次提交一件事。
- build 失败不提交。提交前 `git status` 确认无意外文件。

### 5.3 游戏测试两条路

**A. CUA 自测**（用户让你自己测时）：

- 先把游戏窗口最大化，否则界面会错位。
- 启动 `runClient` → 进世界 → 右键被动 IO 终端（自动建厂）→ 用绑定杖蹲下右键接机器 → 在节点终端看节点与规则 → 核验搬运 → 查日志 → 截图 → 结论。
- 执行到底，失败先读日志再重试，不许点两下就交差。结束关闭游戏进程。

**B. 用户自测**（用户自己要测时）：

- 在报告末尾列测试点清单：编号、前置条件、操作步骤、预期结果、回归影响面。
- 顺序可逐条打勾，不写"随便点点看"。

### 5.4 Git

- 远程在 GitHub：`origin` = `https://github.com/crafteve/ae2vf.git`。
- **推送时机：一个大任务（一个计划全部完成、`build` 通过）收口后推一次**；不要每完成一个小 commit 就 push。中途的小提交留在本地，攒到大任务结束时统一推送。
- 禁止假装推送成功：push 失败如实报告，不写「已推送」。
- Conventional Commits + scope：`feat`、`fix`、`refactor`、`docs`、`chore`、`perf`、`test`、`style`；scope 用模块名，如 `io`、`ui`、`command`、`storage`、`tools`。
- 描述用中文，可夹英文术语。
- 不提交 `run/`、`build/`、`.tmp/`、`.gradle/`、`.idea/`。
- `.tmp/` 是 Agent 的临时工作目录（审查报告、校验脚本、diff 落盘）。它已被 git 忽略，**禁止 Agent 私自删除**；确需清理必须先征得用户同意，且只删自己本轮新建的文件，不得整目录清空。
- 当前分支 `1.21.1neoforge`；`view-client-refactor-20261120` 为视图表与客户端重构分支，旧 UI 线归档在 `archive/ui-v3-20261110` 与 `archive/m3-canvas`，只读。

### 5.5 草稿纸（讨论文档）

`docs/草稿纸/` 是 Agent 自己的讨论笔记，不是给用户阅读的文档，也不是实现约束或计划。日常讨论仍在对话里进行；只有核心结论、已确认项、关键技术约束才落笔，且保持简洁。

规则：

1. 文件名固定 `NN-<需求概述>.md`，两位数字前缀，如 `01-主动合成与虚拟样板供应器.md`；序号递增，不复用已删除的编号。
2. 每个文件开头写状态（`讨论中` / `已定稿` / `已归档`）、最后更新日期、一句话目标。
3. 只记核心：目标、已确认、关键约束、待定/分歧；不记对话过程，不写逐文件实现步骤，不写代码。
4. 每条必须标来源与状态，且只归一类：
   - 【用户已确认】用户明确同意或决定。
   - 【用户想法·待确认】用户提出、尚未最终确认。
   - 【Agent 建议·未确认】Agent 提出、用户未确认；技术判断也归入这一类，不单独设“技术约束”。
   - 【用户已推翻】用户之前提出、后来自己否定或被替代。
   状态变化时移动条目并注明日期，不要同时挂多个状态。
5. 不要求用户阅读或维护；不要用草稿纸代替对话里的提问和确认。用户明确确认的才写“已确认”，推测写“待定”。
6. 讨论中可随时改；每次只补关键变化，能一页写完就不写两页。
7. 需要进入实现时，仍按 5.1 单独写计划并等确认；草稿纸不能替代计划。
8. 结论确认后，把可执行的设计约束同步到 `docs/DECISIONS.md`（ADR）；草稿保留作背景，不删除，后续以 ADR 为准。
9. 草稿纸不参与构建、不参与协议版本，也不要求所有条目最终都实现；被否决的方案也留在“待定”里，避免重复讨论。

### 5.6 本文件的修改

`AGENTS.md` 只有在计划中明确列出修改项、并得到用户审批后才能修改。计划外不得改动。

## 六、速查与坑

### 6.1 关键文件

`build.gradle` · `gradle.properties` · `docs/DECISIONS.md` · `VoidFactoryServer.java` · `FactoryLifecycle.java` · `FactoryRunner.java` · `FactoryStore.java` · `Factory.java` · `FactoryLibrary.java` · `Node.java` · `NodeLibrary.java` · `NodeIo.java` · `NodeIoRules.java` · `NodeCraft.java` · `NodeCodec.java` · `NodeDispatchQueue.java` · `NodeDispatchOrder.java` · `Machine.java` · `MachineHealth.java` · `CommandController.java` · `CommandGate.java` · `CommandApply.java` · `CommandTopology.java` · `CommandWand.java` · `CommandPort.java` · `FactoryCommand.java` · `CommandReason.java` · `LiveNode.java` · `LiveNodeLibrary.java` · `LiveNodeApply.java` · `LiveNodeRunner.java` · `LiveNodeCraft.java` · `ObservationTable.java` · `ObservationRunner.java` · `ObservationReader.java` · `ObservationChange.java` · `BlockOccupancyTable.java` · `IoRunner.java` · `IoForm.java` · `IoKey.java` · `IoReason.java` · `IoResult.java` · `ActivePlanner.java` · `PassivePlanner.java` · `IoExecutor.java` · `FacePick.java` · `CapacityPick.java` · `VariantPick.java` · `PortExecutor.java` · `IoAnalyzer.java` · `IoJob.java` · `DispatchState.java` · `IoFormBuilder.java` · `IoRuleSpec.java` · `MatchMode.java` · `AeKeyAdapter.java` · `AppFluxKeys.java` · `MeStorageResolver.java` · `ViewTable.java` · `ViewTableBuilder.java` · `ViewPort.java`（服务端视图出口） · `AeKeyCodec.java` · `ObservationNoticeListener.java` · `client/screen/terminal/TerminalModel.java` · `TerminalSource.java` · `TerminalChrome.java` · `client/screen/terminal/node/NodeScreen.java` · `client/screen/terminal/passiveio/PassiveIoScreen.java` · `client/world/BlockHighlightRenderer.java` · `client/wand/WandSubmitter.java` · `TerminalSortMode.java`（档位与循环顺序，menu 包） · `tools/gui/gen_textures.py`

### 6.2 坑

1. 存档写盘在 `ServerStoppingEvent`，`ServerStoppedEvent` 太晚。
2. 落盘两条路：配置变化走 `FactoryStore.put(factory)`（记「本 tick 已写」），运行时数据走 `flushRuntime`（跳过本 tick 已写的），停服走 `flushAll`；不要绕开 `FactoryStore` 直接改存档。
3. 观测变化监听器（`ObservationChangeListener`）要强引用；监听器里只标脏 / 丢缓存，不查世界。`PortExecutor` 取端口前用观测事实校验（在、已探、方块实体类型没变）。
4. `NO_SPACE` 与 `REJECTED` 不能混：前者是满，后者是面不对。
5. `UNLOADED` 不进失联通知，只在节点健康里体现。
6. 物品规则默认无向（GUI 不提供面；旧档与常规规则 `face == null`），但执行时不再自己试无向 handler：机器侧统一走 AE2 `MEStorage`，面由 `FacePick` 决定（配置面 → 经验面 → 逐面探），`PortExecutor` 按面取存储。规则里显式配了 `face` 的优先用配置面，配置面无效记 `FACE_CONFIG_INVALID`，不自动换面。物品规则不写路由表（模糊变体可能落在不同面）；流体 / FE 一直按面。
7. FE 探面用 1000 FE，Mekanism 的 FE↔J 换算会截断小数值。
8. 容器优先级：高的先填、低的先抽；同优先级组内两阶段，组间不合并。
9. 仓库离线 = 空仓库，表现为没料，不是报错。
10. 无自动化测试；新逻辑优先写成可独立验证的纯函数，再进游戏核验。
11. 模糊规则没有候选 ≠ 输入堵塞：仓库缺料是 `WAITING_MATERIAL`，只有 `inputFull`（机器满 / 拒收）才是 `INPUT_BLOCKED`。
12. 流体图标不透明：仓库格的悬停描边必须画在图标之后，否则只剩 1px 暗边。
13. Applied Flux 的可选性靠 `ModList` 短路 + `LinkageError` 降级；不要在 `Bridge` 外直接引用它的类。
14. 浮层的绘制顺序写在终端屏幕的 `draw` 里，浮层必须排在物品与数量之后；被数量盖住基本都是顺序写错。
15. 终端视图与排序偏好（列表 / 卡片、排序档位与方向）写在物品的 `ae2:exported_settings` 组件上，不写存档、不参与服务端。
16. 失联是运行时状态，不写档；`ObservationNoticeListener` 按工厂聚合、每 tick 最多一条发 `HudNoticeS2C`（模组 HUD 条，不是原版 actionbar），`UNLOADED` 不通知。只有显式删除机器才删规则。
17. 节点终端里「删除」删的是整个节点；单台成员没有删除入口（旧工作台删除后不再提供）。
18. FE 在 JEI 里必须是独立配料（`ae2vf:fe`），不能拿红石粉当替身：否则 R/U、收藏都会串到红石粉；图标统一走 `KeyIconRenderer`，R/U 走 `addIngredientInfo` 的 FE 信息页。
19. **派活给子代理时中途改向，代价是整个已改一半的文件重翻一遍。** 2026-10-31 的 P2 就栽在这里：FE 的归属连改三次（独立规则 → Flux 为前提 → 化学品同理），每次都在子代理改到一半时下发，导致它两次回退、多花约 40 分钟。三条教训：
    - **派活前把方向写死**：资源类型范围、可选附属的归属、存档兼容策略、包名与命名。方向没定死不要开工；确实要改，先停下来把已改的部分收口到可提交状态再改。
    - **不要为了「每步可编译」造兼容层或单项转换**（例如 `aeKey()` 兼容视图、`toAEKey(ResourceKey)`）。「build 失败不提交」不等于「任何时刻都必须可编译」——中间态大面积报错是正常的，只要最终提交能编译。这类中间层只会凭空多出「先加、再迁、最后删」三步。
    - **机械的类型替换用 bash 批量替换 + 编译器收敛**，不要逐文件人工 read→edit（40 个文件约 80 次工具往返）。批量替换的安全做法见 4.3。
20. `menu/TerminalSortMode` 的声明顺序同时是档位按钮的循环顺序、物品组件与线协议的 ordinal：只许末尾追加、不许重排，否则旧终端里的排序档位会错位。
21. **账本与经验面按「配置引用的机器集合」保留**（2026-11-18 修订）：机器 UNLOADED 时仍保留 `faceHint` / 退避 / 端口缓存，只有机器离开配置才释放；保留集合统一在 `IoRunner` 收口（`IoAnalyzer` / `PortExecutor` / `PassivePlanner`）。观测恢复时 `wake`，不要当成新机器从零重探。

### 6.3 文档偏差

- 2026-11-21 远程与推送约定：`origin` 指向 GitHub（`https://github.com/crafteve/ae2vf.git`）。原「没有远程、禁止 push」作废；改为大任务收口、`build` 通过后统一推送，不逐个小 commit push。
- 2026-11-21 清理旧名残留：创造栏常量 `IDLE_FACTORY` 改 `CREATIVE_TAB`；创造栏名中英改为「虚空工厂」/「AE2 Void Factory」，停服文案同步；`tools/gui` 与 `tools/item` 三个生成脚本的输出目录从 `assets/idlefactory` 改回 `assets/ae2vf`，并删掉已不存在的 `FactoryLayout.java` 与 `Idle Factory UI:` 日志判据。
- 2026-11-20 改名 `ae2vf`：modid、包名（`io.github.crafteve.ae2vf`）、类前缀（`VoidFactory*`）、注册 id、语言键、资源目录与存档文件名全部迁移；版本改 `0.1.0-beta`。旧存档与 `idlefactory:*` 物品不再兼容。
- 2026-11-20 命名收敛：`NetworkProtocol` 拆成 `NetworkVersion` / `NetworkLimit`；`FactoryManager` → `FactoryLibrary`；`FactoryRefs` → `FactoryResolver`；连锁查找从 `factory` 移入 `command.CommandChain`（它只服务绑定杖，属于命令）。
- 2026-11-20 包结构整理：`resource` + `location` 合并为 `key`；删除 `event` 包（不再拦截挖掘）；`registry` 类统一 `VoidFactory` 前缀并把负载注册收进来；编解码器集中到 `network.codec`；删除全部 `package-info`。
- 2026-11-20 视图表与客户端重构落地：读侧只剩 `ViewTable` / `ViewTableDiff`（协议发布前重置为 1，内部编号 41 / 42 只留在 ADR），`view` 包改为视图表，客户端拆 `world` / `wand` / `network` / `screen` 并把两个终端分到 `terminal.node` / `terminal.passiveio`；颜色从主题类迁到组件、界面文案改语言键；新增 `HudNoticeS2C` 把失联 / 恢复提示并入模组 HUD 条。
- 2026-11-18 按草稿 22 收敛重构重写 §1.2 / §2.1 / §2.2 / §2.3 / §2.4 / §3.1 / §6.1 / §6.2（含 #2 / #3 / #16 / #21）：包分层加入 `runtime` / `claim` / `view` / `command`，删掉容器、能力面、`FactoryRuntime` / `FactoryService` / `NodeActions` 与旧观测预算说法。
- 发现时顺手修，不单独开重构；下面是已修正的偏差记录。
- 2026-11-11 修正 §1.2：原文写「主动合成未实现」，与代码不符（`NodeCraftService`、
  `ProviderMode.ACTIVE` 等早已实现）。
- 2026-11-12 按 ADR-042 修正 §2.1 / §2.2 / §4.1 / §6.1 / §6.2：执行层类名与调用链换成
  `Form` / `IoRunner` / `FacePick` / `PortExecutor` / `Analyzer` 一系，删掉「单元」与无向兜底的旧说法。
- 2026-11-13 按 ADR-043 修正 §2.1：加入「限定词 + 后缀」规则与值类型后缀表；`io` 包类名统一为
  `IoForm` / `IoKey` / `IoSide` / `IoReason` / `IoResult` / `IoJob` / `IoExecutor` / `IoRunner` / `IoAnalyzer`。
