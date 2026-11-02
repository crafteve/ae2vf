# AGENTS.md

## 一、项目概述

### 1.1 是什么

Idle Factory（放置工厂）：AE2 附属式的大型工厂 IO 管理模组。把"把机器接进自动化网络"从摆接口、铺线缆、调 IO 面变成在终端里配置。

只接管机器与仓库之间的搬运。机器配方、能量、加工时间一律不变。

### 1.2 核心设定

- 服务端权威：IO 全在服务端；GUI 只是监视器，只渲染 + 发意图；menu 是登录壳。
- 工厂不是数据库：仓库在 AE2 网络或方块容器组；仓库离线 = 没料，不是断网。
- 被动 IO：玩家给机器配输入输出规则，每 tick 搬运，当前形态。主动合成：AE2 pattern / CPU 下单，未实现。
- 基础概念：机器库（每台机器带自己的 IO 规则）；**没有产线对象**，分组只是 UI 视图；失联是运行时状态，不写档。
- 资源身份用 AE2 的 `AEKey`：物品带完整数据组件（`AEItemKey`），流体按注册名（`AEFluidKey`），不自己造平行的资源类型。
- **资源类型的覆盖范围 = AE2 原生 + 已安装的 AE 生态附属，模组自己不新增任何资源类型**。FE 以 Applied Flux 为前提，化学品以 Applied Mekanistics 为前提；没装对应附属就没有那类资源，不写降级表示、不造平行 key。装了时它们的 key 都是 `AEKey`，走和物品 / 流体完全相同的规则。
- IO 规则分精确 / 模糊：模糊忽略物品组件，旧规则默认按模糊读入。
- 观测层是第二事实层：方块在不在、哪个面有什么能力、是否在干活。界面与指令只读观测。
- 无物品、无方块，只有 MenuType。入口是 `/idf` 指令与 `/idf gui`。

### 1.3 技术栈

| 项 | 值 |
|---|---|
| MC / NeoForge | 1.21.1 / 21.1.264 |
| Java / Gradle / ModDevGradle | 21 / 9.2.1 / 2.0.148 |
| Parchment | 2024.11.17 |
| modid / 版本 / 分支 | `idlefactory` / 0.1.0 / `1.21.1neoforge`（主线） |
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
| `observation` | 方块级观测、能力缓存、工作状态 | 配置、搬运 |
| `machine` | 机器、机器库、IO 规则（`ResourceSpec` / `MatchMode`） | 世界探测、搬运 |
| `storage` | 仓库后端（AE2 / 容器，统一实现 `MEStorage`）、`AeKeySupport` 工具 | IO 事务 |
| `io` | 遍历、取面、搬运事务 | 配置、持久化 |
| `factory` | 工厂实例与编排（Runtime / Service / Store / Actions） | 存储实现、搬运实现 |
| `diag` | 事件、统计、体检、渲染文本 | 改写配置 |
| `event` / `registry` | 指令注册；MenuType 注册 | 业务逻辑 |
| `menu` | 菜单与玩家槽位 | 工厂数据 |
| `network` | 协议与负载 | 业务判断 |
| `client` | 客户端入口、世界链接、绑定杖、按键 | 工厂界面细节 |
| `client.screen` | 工厂工作台、主题、贴图、控件、仓库缓存、UI 配置 | 服务端状态 |
| `integration` | AE2 / Applied Flux / JEI / jecharacters 适配 | 核心逻辑 |

命名后缀固定：

`Service` 业务入口 · `Runner` 每 tick 执行 · `Store` 存档容器 · `Manager` 内存索引 · `Library` 集合 · `Ref` 不可变引用 · `Anchor` 仓库地址 · `Resolver` 后端解析 · `Adapter` 能力适配 · `Builder` 视图组装 · `Handler` 消息处理 · `Checks` 体检 · `Stats` 统计 · `Render` 渲染 · `Messages` 文案 · `Refs` 引用解析

硬条款：

1. 包名只用单数领域名词。禁止 `util`、`common`、`misc`、`helper`。
2. 一个概念只建模一处：资源身份 = AE2 的 `AEKey`，观测键 = `BlockKey`，IO 规则 = `ResourceSpec`，能力分类 = `CapabilityKind`。
3. 依赖单向。现存例外只有 3 个：`storage → network`、`factory → integration.ae2`、`network.payload → client.network`。禁止新增跨层环。
4. 发包只从 `network`。`FactoryStorageActions` 发包是历史例外，新代码不许学。
5. `client.*` 只在客户端加载；服务端类不得引用。负载的客户端处理放 `client.network` 的 Handler。
6. 数据 record 只存 id、坐标、枚举；不持有 `Level`、`BlockEntity`、capability 等活对象。资源 key 一律用 AE2 的 `AEKey`，它本身是不可变值对象，不需要额外例外。
7. 单文件超过约 400 行先给拆分方案，不边想边改。
8. 新建包、新建文件、改数据层交互结构（record 字段 / NBT / 网络字段 / 跨包签名），计划里必须逐项列出：文件、职责、依赖方向、迁移顺序。等你确认后再动手。

### 2.2 三层信息：配置 / 观测 / io

- 配置（最高信息，写档）：`machine` 的机器与 IO 规则、`factory` 的机器库与仓库地址。只描述搬什么，不执行。
- 观测（第二信息，不写档）：`observation` 判断机器是否存活（PRESENT / UNLOADED / MISSING / NO_DIMENSION）、哪个面有什么能力、当前是否在干活。界面、指令、执行门禁都只读它，读路径不探世界。
- 执行（第三信息，瞬态）：`io` 判断机器此刻能不能收、能不能抽，跑 `MachineRunner → GroupRunner → IoTransfer`；结果只留在本轮与诊断里，不回写配置。

各包职责：

- `factory`：工厂实例与编排。`Factory` 持仓库插头与机器库；`FactoryRuntime` 每 tick 调观测 → 仓库 → IO；`FactoryService` / `MachineService` 是指令入口，`MachineActions` 管机器规则写操作；`FactorySnapshotBuilder` 把配置 + 观测整理成视图。
- `io`：执行层。`MachineRunner` 遍历机器、做睡眠、读观测门禁；`GroupRunner` 按规则调搬运；`RuleResolver` 负责取面与 handler：玩家配的面 > 路由表 > 扫面；物品先试无向 handler，无向不可用（缺失或只读）时逐面探测，命令里显式配了 `face` 的物品规则仍优先用配置面；`ItemCandidates` / `ItemVariantIndex` 负责模糊物品规则的变体展开；`IoTransfer` 是唯一写路径；`RunnerState` 存睡眠瞬态；`RouteTable` 只记流体 / 能量搬通的面。

调用链：`ServerTick → FactoryRuntime → MachineRunner → GroupRunner → IoTransfer → MEStorage / ResourceAdapter`。

失败原因沿链上抛：`OK`、`NO_ITEM`、`NO_SPACE`、`NO_HANDLER`、`REJECTED`。`REJECTED`（有容量却不收）说明面不对，要踢路由（物品规则没有路由条目，下一轮重新探面）；`NO_SPACE`（满了）是正常状态，保住路由。

### 2.3 数据层

- 不可变优先：`record` + `withXxx` 返回新实例。
- 存档读写对称：`toTag` / `fromTag` 成对，键名常量化，缺字段走默认值兼容旧档。
- 对外返回不可变集合或防御性拷贝，不暴露内部可变表。
- 上限、间隔、容量一律具名常量，并在解码侧校验。
- 枚举分支用穷尽 `switch`，不留 `default` 掩盖新增值。
- 禁止布尔盲参数：不写 `foo(true, false)`，用枚举或具名类型。
- 物品身份 = `AEItemKey`（注册名 + 数据组件），相等性直接用它自己的实现，不再自己对齐。
- `ResourceSpec` 的 `MatchMode` 决定规则是否比较组件；旧档缺 `Mode` 的物品规则按 `IGNORE_COMPONENTS` 读入。
- 规则落盘时整个 key 交给 `AEKey.toTagGeneric` / `fromTagGeneric`，网络统一走 `AEKey.STREAM_CODEC`（经 `ResourceKeyCodec` 薄包装）。

### 2.4 其余包

`event` 指令树 + 审计包装 · `registry` MenuType · `menu` 36 个玩家槽位与 Shift 进仓库 · `network` 协议版本、负载、快照出口 · `client` 客户端入口/世界链接/绑定杖/按键 · `client.screen` 工厂工作台唯一复杂屏幕：壳、状态、几何、分区渲染、输入、JEI、主题、贴图、九宫格、搜索框、仓库缓存和 UI 配置都在这里，类名统一 `Factory` 前缀 · `integration` 外部模组适配，反射失败必须降级。

## 三、规模、性能、界面

### 3.1 规模与性能

目标体量：上百台机器、16 个容器。

- 睡眠：机器级，100 tick 未搬动才睡；观测按状态退避。
- 预算：观测 8 次/tick，标脏 32 次/tick，工作状态 16 次/tick。
- 复探：PRESENT 100 tick，UNLOADED 40 tick，MISSING 100 → 1200 tick，工作状态 10 tick。
- IO：默认每 tick 一轮，`/idf diag io` 可调 1–20。
- 上限：机器表 512、规则每侧 16、仓库快照 1024、事件 200 条、watch 4 路。
- 读路径不探世界：展示、指令、界面只读观测缓存。
- 索引 O(1)：`byId`、`byPosition`；不每 tick 新建集合、不重复分配。
- 所有遍历有上限护栏；失联通知按工厂聚合、10 秒节流；心跳走 trace。
- 模糊物品变体：索引 TTL 20 tick、单 id 64 个、单规则 8 个 / 单机器 16 个变体每 tick；仓库快照按 512 KiB 分片。
- 卡顿先降频，再考虑加预算。

### 3.2 界面

风格：AE2 终端浅色、立体、科幻。不发挥。

- 唯色源 `FactoryGuiTheme.AE2`；状态用 `statusRun / statusWarn / statusError / statusIdle` 四色。`DARK` 只保留，不用。
- 立体感只来自贴图：九宫格边框、内凹斜边、上/左深阴影、下/右高光、深色机器卡片。表格外层保留「深色 + 白色」双像素框。
- 嵌套立体：下拉框本身是立体框，选项各自也是小立体框。
- 1px 像素精度：线条不糊、不对齐就重画；槽位保留格子感与悬停/选中动画。
- 图标主导，少文字：物品、机器、状态优先用图标 + 颜色 + 动画表达。
- 状态表达：灯色 + 文字色 + 1px 状态条；列宽自适应，压缩机器名与状态，信息列优先。
- 贴图由 `tools/gui/gen_textures.py` 离线生成，Java 只 blit。禁止手改 PNG；改色必须同步 `FactoryGuiTheme`。
- 流体 / FE 图标按 AE2 风格：流体 still 贴图染色，FE 用 Applied Flux 图标（未装时绿色水贴图兜底）；物品用原版装饰层补耐久条。
- 仓库格数量：物品 / FE 最多 4 字符、流体最多 5 字符，缩放 0.666；完整精度只放 tooltip 与速率。
- 悬停高亮 = 浅蓝填充 + 1px 描边；描边必须在图标之后画，否则会被不透明的流体贴图盖住。
- 几何只在 `FactoryLayout`。溢出先让位，不许压到相邻区域。
- 绘制层级统一走 `client/screen/FactoryGuiLayer`，顺序固定：`BASE 0` → `ITEM 150` → `ITEM_DECORATION 200` → `GRID_COUNT 300` → `OVERLAY 360` → `DRAG 370` → `TOOLTIP 400`。浮层必须高于数量层（300），否则会被仓库/背包数量盖住；tooltip 由原版固定在 400，浮层打开时已抑制。新加图层先加枚举项再引用，禁止在调用点写 z 魔法数。
- 尺寸问题先看 `logs/latest.log` 的 `Idle Factory UI: ...` 行；留白可用 `-Pgutter=64` 或 `-Didlefactory.gutter` 现场调。

### 3.3 便利性

- 指令以机器为主语：`/idf machine <id> input|output add|remove <item|fluid|energy> [face]`；Tab 按上下文过滤。
- `diag why` 固定六问，`diag watch` 汇总 / 详情，单条回执 60 字内。
- 表格可展开规则、可搜索；搜索沿用 AE2 语法并接 jecharacters 拼音兼容层。
- JEI R/U 必须覆盖机器列表、IO 列、仓库、背包；FE 使用独立配料（`idlefactory:fe`）与信息页，R/U / 收藏不得落到红石粉。

## 四、编码规范

### 4.1 命名

Java 惯例：类 PascalCase、方法/字段 camelCase、常量 UPPER_SNAKE、包小写。领域词用 Factory、Machine、MachineIoGroup、IO 规则、Observation、Storage、Anchor、Ref、Runner，不用 `Target`，也不再引入 Line。

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
- 启动 `runClient` → 进世界 → 跑指令链路（`/idf create` → `storage bind` → `factory connect` → `machine <id> input/output add` → `diag`）→ 操作界面核验 → 查日志 → 截图 → 结论。
- **中文输入法会让指令输入失败**：`/` 变成 `、`、字符进候选框，或者看着打了但没提交。用 `Win+Space` 切英文键盘，或把整条指令写进剪贴板用 `Ctrl+V` 粘贴再回车。输入后用 `run/logs/latest.log` 或聊天记录核对指令真的执行了，不要在「以为打进去了」的假设上继续跑。
- 执行到底，失败先读日志再重试，不许点两下就交差。结束关闭游戏进程。

**B. 用户自测**（用户自己要测时）：

- 在报告末尾列测试点清单：编号、前置条件、操作步骤、预期结果、回归影响面。
- 顺序可逐条打勾，不写"随便点点看"。

### 5.4 Git

- 目前**没有 GitHub 远程**：禁止 `git remote add`、禁止 push、禁止假装推送成功。
- Conventional Commits + scope：`feat`、`fix`、`refactor`、`docs`、`chore`、`perf`、`test`、`style`；scope 用模块名，如 `io`、`ui`、`command`、`storage`、`tools`。
- 描述用中文，可夹英文术语。
- 不提交 `run/`、`build/`、`.tmp/`、`.gradle/`、`.idea/`。
- `.tmp/` 是 Agent 的临时工作目录（审查报告、校验脚本、diff 落盘）。它已被 git 忽略，**禁止 Agent 私自删除**；确需清理必须先征得用户同意，且只删自己本轮新建的文件，不得整目录清空。
- 当前分支 `1.21.1neoforge`（主线）；旧 M3 画布线归档在 `archive/m3-canvas`，只读，不再从它拉分支。

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

`build.gradle` · `gradle.properties` · `docs/DECISIONS.md` · `FactoryRuntime.java` · `Machine.java` · `MachineLibrary.java` · `MachineIoGroup.java` · `MachineActions.java` · `IoTransfer.java` · `GroupRunner.java` · `RuleResolver.java` · `ResourceSpec.java` · `MatchMode.java` · `AeKeySupport.java` · `ItemVariantIndex.java` · `AppFluxKeys.java` · `ObservationService.java` · `FactoryLayout.java` · `FactoryLabels.java` · `FactorySort.java` · `FactorySortButtons.java` · `FactoryGuiTheme.java` · `FactoryGuiLayer.java` · `FactoryStorageRepo.java` · `FactoryScreen.java` · `FactoryScreenState.java` · `tools/gui/gen_textures.py`

### 6.2 坑

1. 存档写盘在 `ServerStoppingEvent`，`ServerStoppedEvent` 太晚。
2. `markDirty()` 必须走 `save()`；`FactoryStore` 只写自己的 `records`。
3. 能力监听器必须强引用（NeoForge 存 WeakReference）；监听器里只标脏，不查世界；负结果要缓存。
4. `NO_SPACE` 与 `REJECTED` 不能混：前者是满，后者是面不对。
5. `UNLOADED` 不进事件流，只在 `diag observe` 看。
6. 物品规则默认无向（GUI 不提供面；旧档与常规规则 `face == null`），但执行时不能只查无向 handler：Mekanism 的 `side == null` 物品句柄是只读代理，必须先试无向、不可用再逐面找可接收 / 可抽出的面；命令里显式配了 `face` 的物品规则仍优先用配置面。物品规则不写路由表（模糊变体可能落在不同面）；流体 / FE 一直按面。
7. FE 探面用 1000 FE，Mekanism 的 FE↔J 换算会截断小数值。
8. 容器优先级：高的先填、低的先抽；同优先级组内两阶段，组间不合并。
9. 仓库离线 = 空仓库，表现为没料，不是报错。
10. 无自动化测试；新逻辑优先写成可独立验证的纯函数，再进游戏核验。
11. 模糊规则没有候选 ≠ 输入堵塞：仓库缺料是 `WAITING_MATERIAL`，只有 `inputFull`（机器满 / 拒收）才是 `INPUT_BLOCKED`。
12. 流体图标不透明：仓库格的悬停描边必须画在图标之后，否则只剩 1px 暗边。
13. Applied Flux 的可选性靠 `ModList` 短路 + `LinkageError` 降级；不要在 `Bridge` 外直接引用它的类。
14. 新增浮层必须在 `FactoryGuiLayer` 里加枚举项再引用，调用点禁止写 z 魔法数字；浮层被物品/数量盖住、或反过来盖住 tooltip，基本都是层级散落导致的。
15. 排序键 / 方向 / 范围偏好写入客户端 CLIENT 配置（`config/idlefactory-client.toml`），关闭界面与重启客户端都保留；不写存档、不参与服务端。
16. 机器失联只进 `FactoryLive.machineMissing`，不写档；只有显式删除机器才删规则。
17. 左表机器行的操作是「展开 IO」，右侧机器库卡片的叉号是「删除机器」，两者语义不能混。
18. FE 在 JEI 里必须是独立配料（`idlefactory:fe`），不能拿红石粉当替身：否则 R/U、收藏都会串到红石粉；图标统一走 `FactoryResourceIconRender`，R/U 走 `addIngredientInfo` 的 FE 信息页。
19. **派活给子代理时中途改向，代价是整个已改一半的文件重翻一遍。** 2026-10-31 的 P2 就栽在这里：FE 的归属连改三次（独立规则 → Flux 为前提 → 化学品同理），每次都在子代理改到一半时下发，导致它两次回退、多花约 40 分钟。三条教训：
    - **派活前把方向写死**：资源类型范围、可选附属的归属、存档兼容策略、包名与命名。方向没定死不要开工；确实要改，先停下来把已改的部分收口到可提交状态再改。
    - **不要为了「每步可编译」造兼容层或单项转换**（例如 `aeKey()` 兼容视图、`toAEKey(ResourceKey)`）。「build 失败不提交」不等于「任何时刻都必须可编译」——中间态大面积报错是正常的，只要最终提交能编译。这类中间层只会凭空多出「先加、再迁、最后删」三步。
    - **机械的类型替换用 bash 批量替换 + 编译器收敛**，不要逐文件人工 read→edit（40 个文件约 80 次工具往返）。批量替换的安全做法见 4.3。

### 6.3 文档偏差

- 无已知偏差；发现时顺手修，不单独开重构。
