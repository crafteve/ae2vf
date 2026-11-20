# 决策记录（ADR）

> 记录讨论阶段确定的方向，以及明确否决的方案。
>
> 2026-10-24 起，此文档只记录**实际讨论确认**的决策，不再保留 AI 生成的假设。
> 编号规则：`ADR-NNN`，状态取 `已决定` / `已否决` / `待定`。

---

## ADR-001 定位：虚拟 pattern provider + 全局仓库

**状态**：已决定

**决定**：本模组 = 把 AE2 的「pattern + CPU」执行端，从接口方块换成机器；把机器间物流换成全局仓库。

**理由**：AE2 缺的不是合成树，是「把机器接进去」这一步。虚拟化这一步，工作量最小，痛点最真。

**后果**：不做产线图、不做机器间直连、不做自动读配方。

---

## ADR-002 仓库是全局抽象，没有方块

**状态**：已决定

**决定**：仓库不是方块，是存档里的一份账户数据。玩家通过终端访问。

**理由**：符合「云」的语义；跨维度免费；没有仓库被破坏 / 被偷的问题。

**后果**：容量和吞吐是账户属性；升级靠消耗资源，不靠造新方块。

---

## ADR-003 每玩家一个仓库，可组队共享

**状态**：已决定

**决定**：每个玩家一个账户，组队时队伍共享一份。

**理由**：服务器全局唯一会变成公共垃圾桶；每玩家一个符合「这是我的工厂」。

**后果**：权限模型围绕「玩家 / 队伍」做。

---

## ADR-004 不自动读配方

**状态**：已决定（否决「自动读配方」）

**决定**：模组不推断配方、不反查配方。配方由玩家提供：主动模式写 pattern，被动模式填输入输出清单。

**理由**：自动读配方在科技整合包里 100% 卡合成——条件配方、多输出、副产物、模组自定义配方类型，没有一个能通用处理。

**后果**：玩家仍要付出「写 pattern」或「填清单」的操作成本；这部分成本靠批量工具（配置器 / 蓝图）降低。

---

## ADR-005 1.21.1 支持四种资源，能量只认 FE

**状态**：已决定

**决定**：1.21.1 版仓库支持物品、流体、能量（FE）、化学品（gas / infusion / pigment / slurry 等）。1.7.10 只支持物品、流体。

**理由**：

- 化学品是 Mekanism 那类独立 registry 的资源，不是流体的子集，必须单独建模
- 能量只认 FE：FE 是 1.21.1 的事实标准；EU / Joule 没有统一换算率，硬换必然出错
- 化学品具体有哪些子类型，取决于整合包装了什么模组——没装 Mekanism 就没有 gas

**后果**：

- `ResourceType` 是 `(kind, subtype)`，`CHEMICAL` 的 subtype 由装载的模组决定
- EU 等其他能量单位的机器，仍然用真实线缆送电
- UI 要能展示多种资源类型，且类型集合随整合包变化

---

## ADR-006 能量无损

**状态**：已决定

**决定**：能量进出仓库 1:1，无损耗。

**理由**：仓库是全局抽象的，没有「距离」；固定损耗率是半吊子——既不真实，也没保住原本想保的平衡维度。

**后果**：**这是一个已知的平衡改变**——GT 类整合包的长距离输电损耗玩法被移除。README 与本文档都要明说。

---

## ADR-007 被动模式手动配置 + 批量复制

**状态**：已决定

**决定**：被动模式的输入输出由玩家手动填写；提供配置器（单台复制）和蓝图（整组复制）降低重复劳动。

**理由**：手动填写可控；批量工具解决量的问题。

**后果**：不做「学习式」自动识别。

---

## ADR-008 不做机器间直连

**状态**：已决定（否决「机器—机器」拓扑）

**决定**：所有交换经过仓库。机器之间没有边、没有管道、没有直接 IO。

**理由**：星形拓扑自动解决副产物回收、环、多机同产、跨维度。

**后果**：v0.1 的 Node / Edge / Line、带宽配额、产线图全部作废。

---

## ADR-009 能力可选，两版资源集不同

**状态**：已决定

**决定**：`IWarehouse.supports(type)` 由后端声明。1.21.1 支持物品 / 流体 / 能量；1.7.10 只支持物品 / 流体。

**理由**：1.7.10 能量接口碎片化，统一成本过高。

**后果**：Core 不能假设某资源一定存在；1.7.10 版能量必须用真实线缆。

---

## ADR-010 Core 零 MC 依赖

**状态**：已决定

**决定**：Core 不 import 任何 Minecraft 类，锁 Java 8 语言级别。

**理由**：无头可测；两版共用；AE2 从地基降为可选后端。

**后果**：Core 不能用 record / var / switch 表达式，写法啰嗦。

---

## ADR-011 否决：带宽配额

**状态**：已否决

**背景**：v0.1 提出「边 = 带宽配额」，每条边分配吞吐量。

**决定**：不做。

**理由**：这是 AI 生成的假设，从未设计过。星形模型下没有「边」，带宽概念失去载体。

**后果**：吞吐上限收敛到仓库账户上（见 ADR-002）。

---

## ADR-012 否决：产线图 / 自动推断连线

**状态**：已否决

**决定**：不做产线图、不做自动推断连线。

**理由**：同 ADR-011，v0.1 的假设；且推断依赖配方，与 ADR-004 冲突。

**后果**：玩家在终端里看到的是「机器列表 + 配置」，不是拓扑图。

---

## ADR-013 否决：L2 反射适配

**状态**：已否决

**决定**：不通过反射读 NBT 猜配方槽。

**理由**：静默失效风险高——搬错槽位、物品塞进不该塞的地方。崩溃好查，静默错误难查。

**后果**：只做标准 capability 与玩家显式配置。

---

## ADR-014 终端是无线终端，用量子纠缠奇点绑定

**状态**：已决定（暂定）

**决定**：终端是物品，不占方块位。绑定载体是 AE2 的量子纠缠奇点：ME 网络侧建量子环并放入奇点，终端 GUI 放入同频奇点，终端按频率全局查询并连上该网络。

**理由**：

- 符合「仓库没有方块」的定位
- 奇点自带 `long` 频率，不需要自定义 DataComponent
- `Locatables.quantumNetworkBridges()` 是 AE2 提供的全局注册表，天然跨维度
- 复用 AE2 的 QNB 配对与跨维度机制，不重造

**后果**：

- 玩家必须在 ME 网络侧建一个量子环
- 两个环都要放奇点且同频
- 环的区块未加载时无法连接，需要降级提示

---

## ADR-015 第一版依赖 AE2，不做自建后端

**状态**：已决定

**决定**：第一版 demo 直接依赖 AE2。存储用 `IStorageService.getInventory()`，主动模式实现 `ICraftingProvider`，不做自建仓库后端。

**第一版资源范围**：物品 + 流体。FE 能量和化学品不在第一版范围内。

**理由**：把精力集中在工厂维度和面板上；AE2 的存储、CPU、pattern 系统已经成熟。

**后果**：

- `IWarehouse` 抽象暂时退化为 AE2 适配层
- 资源类型受 AE2 原生限制：物品、流体
- 1.7.10 版本再考虑自建后端
- AE2 变成编译期和运行期依赖

---

## ADR-016 终端物品为入口

**状态**：已决定（2026-10-24 修正：原「零物品、纯快捷键」方案作废）

**决定**：终端是一个物品，右键打开工作台。第一版从创造标签页获取，不做配方。快捷键作为后续可选的辅助入口。

**理由**：

- 物品有持有感，符合「工具」定位
- 创造标签页可见，玩家容易发现
- 不占额外物品栏（终端本身就是工具）

**后果**：

- 需要注册 `TerminalItem`、贴图、模型、语言文件
- 后续可加速度键作为第二入口
- 接入机器的触发方式：手持终端右键机器（M2）
- 绑定奇点的位置：玩家附件槽 + 终端内的浮层（见 ADR-014）

---

## ADR-017 诊断走聊天栏，不做独立界面

**状态**：已决定

**决定**：工厂的运行诊断（状态摘要、体检、事件流、实时速率）全部走聊天栏命令，不做独立界面。

**理由**：独立界面要把 UI 再写一遍，而 M1 的终端界面迟早重写；聊天栏的点击、悬停、颜色是白送的。诊断的数据层（事件 + 计数）同时是后续终端面板的地基，先做不浪费。

**后果**：单条输出控制在 60 字以内、不用空格对齐（MC 默认字体不等宽）；实时速率用 actionbar 而不是聊天栏刷屏；事件只存内存 200 条，不写存档。

---

## ADR-018 命令树以产线为主语

**状态**：已被 ADR-033 取代（2026-10-31）

**决定**：`/idf line <线> <动作> <对象>`，不是 `/idf line <动作> <线> <对象>`。

**理由**：玩家脑子里是「1 号产线，加台机器」，语序应该跟脑子一致；产线成为节点之后，Tab 补全才拿得到前面填的产线，能按「这条线里的机器」过滤。

**后果**：

- 产线名不能占用子命令字面量（create/delete/info/start/end/add/remove/manage/list），建线时校验
- `add` 只补未分配的机器；挪机器要先 `remove` 再 `add`
- `remove` 的物品补全只列已配置项，`face` 补全标出真正有物品能力的面

---

## ADR-019 仓库支持外部方块容器，当前与 AE2 互斥

**状态**：已决定

**决定**：仓库除 AE2 量子环外，还支持由若干方块容器（箱子、木桶等）组成的容器组。一个工厂当前只能挂一种，AE2 与容器互斥。

**理由**：

- AE2 原生只存物品和流体，化学品、能量受限于 AE2 的附加模组生态；物理容器不依赖 AE2，未来按能力扩展更自由
- 容器是玩家看得见、摸得着的货架，符合放置游戏的物理直觉
- 容器组内部用优先级表达「主货架 / 溢出缓冲」

**后果**：

- 容器上限 16 个（性能护栏）；双箱子两半要分别绑
- 容器按「优先级高的先填、低的先抽」；**同优先级组内**插入分两阶段（先补满本组已有堆叠，再占本组空槽），组与组之间不合并——否则低优先级的半组会在高优先级还有空间时被补满，等于把货存进了低优先级
- 区块未加载的容器按不可用处理，全部不可用则仓库离线、产线停
- **互斥是刻意的**：如果 AE2 网络通过 storage bus 访问同一个容器，两个后端会把同一份库存算两次，破坏 `simulate` 的事务边界。未来做共存前，必须先有「容器是否被该 ME 网络引用」的检测

---

## ADR-020 仓库按资源类型建模，IO 默认每 tick 一轮

**状态**：已被 ADR-033 取代（2026-10-31）

**决定**：仓库和 IO 从物品专用改为 `ResourceKey(kind, id)`；本次支持物品、流体（mB）、FE。IO 默认每 tick 一轮，`/idf diag io` 可在 1–20 tick 间调；默认搬运量是「塞满」。

**理由**：

- AE2 原生支持物品和流体；FE 需要 Applied Flux 这类扩展；化学品需要独立实现
- 5 tick 一轮对 FE 太慢，机器会间歇性缺电
- 塞满符合「工厂只管注入」的定位，少一个配置项

**后果**：

- 旧存档的 IO 规则和仓库地址不再兼容（新格式带 `Kind` / `Type`），测试期重建存档
- 1 tick 一轮增加遍历开销；容器上限 16，必要时用 `/idf diag io` 降频
- 化学品规则可存档但不执行，`why` 报「暂不支持化学品」
- 睡眠按 tick 计（100 tick 不动才睡），不再按轮数，否则 1 tick 时过于敏感

---

## ADR-021 机器失联进回收站，不删配置

**状态**：已被 [ADR-032](#adr-032-规则跟机器走失联只标记显式删除) 取代（2026-10-29）

**决定**：机器从机器库里消失（方块被拆、维度没了）或玩家主动 `disconnect` 时，只要还有 IO 组引用它，
就只标记、不删除；规则原样保留，进「回收站」。只有**没有配置过规则**的机器才直接删。

**三条输入，职责不混**：

- `referenced` —— 配置层现算（有没有 IO 组引用）
- `retired` —— 配置层存的玩家意图（主动退役进回收站），**写档**
- `health` —— 观测层的事实（在 / 未加载 / 失联），**不写档**

**删除条件**：`!referenced && (retired || health.gone())`。

**后续变更**：2026-10-29 起字段命名为 `retired`（原 `detached`），语义不变。

**理由**：

- 误拆机器是高频操作，旧行为会在 5 秒内把机器和规则一起删掉，玩家必须重配
- 「没配过规则」的机器没有可损失的东西，删掉零代价
- 墓碑数量天然不超过 IO 组数量，有界
- 玩家把最后一条引用 `line remove` 掉之后，墓碑会被下一次对账自动清掉——回收站自清理

**后果**：

- `IoGroup.machineId` 永远能在机器库里找到（不再有孤儿组）
- `info` / `diag` 要区分「活跃 / 未加载 / 失联 / 回收站」
- 失联会弹 actionbar（按工厂聚合、10 秒节流），但玩家自己断开的机器不提醒

---

## ADR-022 观测是独立一层，读路径不探世界

**状态**：已决定（2026-10-26）

**决定**：把「世界现在是什么样」抽成一层全局观测（`factory.observation`），由 tick 循环维护；
命令、Tab 补全、以后的 GUI 只读观测，不亲自查世界。

**三层事实**：

1. **配置（L0，写档）**：机器、规则、仓库地址。唯一权威是存档。
2. **观测（L1，可重建）**：方块在不在、是哪个方块、哪些面有什么能力。唯一权威是世界本身，
   内存里只是「最后一次观测 + 时间」。
3. **路由 / 执行（L2/L3，瞬态）**：先试哪个面、这一轮搬了多少。

**关键设计**：

- 观测的原子单位是**方块位置**，不是「机器」。机器和容器共用同一张表（`BlockKey` 位置键），
  所以一台被同时接入和绑定成容器的方块只有一份观测。
- `observation` 包<b>零工厂依赖</b>：它只认 `BlockKey`，由机器/容器反向提供位置。
- 探测分两档：`presence`（4 次能力查询）与 `faces`（21 次，惰性缓存）。
- 预算 8 次/tick；PRESENT 5 秒复探、UNLOADED 2 秒、MISSING 指数退避到 60 秒。
- **UNLOADED 不进事件流**（走路就会刷屏），只在 `diag observe` 里看得到。

**后果**：

- `info` 不再逐台现场探测；显示的是「最后一次观测」，可能旧几秒，但两次调用之间一致
- `why` 仍做现场体检：显式刷新那一条线的机器，再复用同一套 `BlockProbe`
- `MachineChecks` 被删除，存在性判断只剩一个入口

---

## ADR-023 能力句柄缓存靠失效事件，不靠轮询

**状态**：已决定（2026-10-26）

**决定**：每个维度一份 `LevelCapabilityCache`，键是 `(pos, 种类, 面)`，值是 handler（含负结果）。
一个位置只注册一个 NeoForge 失效监听器，收到通知就清掉该位置全部条目，并让观测层下一 tick 重探。

**理由**：

- NeoForge 会在区块装卸、方块实体装卸、方块实体放置/销毁时主动失效能力监听器
- 模组改面配置时应当自己调 `invalidateCapabilities(pos)`；这是模组的契约
- 参考 SFM：`SFMBlockCapabilityCacheForLevel` 就是同形状的表

**必须注意的坑**：

- NeoForge 的 `CapabilityListenerHolder` 存的是 **WeakReference**，必须自己强引用监听器，
  否则会被 GC 掉、永远收不到失效通知
- 监听器里**不许查世界**（NeoForge 明确禁止），只能标脏
- 负结果要缓存，否则「这个面没有能力」会每 tick 重查；失效事件会把它一起清掉

**契约被违反时的兜底**：模组改了能力却不发失效通知，缓存就会陈旧。执行层靠「搬不动」自然暴露；
下一阶段的路由表会用「容量有但模拟插入为 0」这个精确信号把该面踢掉。

---

## ADR-024 面路由表：程序自建、按事件失效

**状态**：已决定（2026-10-26）

**决定**：每条规则记住「上次从哪个面搬通」，存在运行时（`RouteTable`，不写档）。取面三层优先级：

```
玩家配的面  >  路由表  >  扫面（6 面模拟 + 无向兜底）
```

配置优先是硬规矩：玩家写死的面永远不被路由覆盖。

**失效（三条路，各管一段）**：

| 触发 | 机制 |
|---|---|
| 方块换了、能力失效、健康度变化 | 观测层面表代数 `facesGeneration` +1；路由条目带着旧代数，对不上就作废 |
| 有容量却不收（`IoResult.REJECTED`） | 踢掉这条路由，下一 tick 重扫 |
| 玩家改规则（`manage add/remove`） | 清空该组的整张路由表 |

**为什么「满」不能当作「面不对」**：`NO_SPACE`（容量为 0）是正常状态，必须保住路由；只有
`REJECTED`（容量 > 0、模拟插入却是 0）才说明这个面不对。这两个信号以前混在一个 `NO_SPACE` 里。

**顺带修的错误**：能量**输出**规则以前用 `receiveEnergy` 探面（那是输入语义），现在输入探
`receiveEnergy`、输出探 `extractEnergy`。

**后果**：

- `IoResult.Reason` 多一个 `REJECTED`，`diag` 的阻塞原因更有信息量
- `diag observe` 多一行「面路由 N 条」
- 物品规则**不**参与路由：物品保持无向语义（无向才是「整个库存」，符合仓库直觉）

**2026-10-29 补充**：物品规则仍不参与路由，但执行层不再假设无向句柄一定可写。Mekanism 的
`side == null` 物品句柄是只读代理，因此物品解析顺序为「无向可用则用 > 逐面探测 > 无向兜底」；
命令里显式配置了 `face` 的物品规则仍优先用配置面。

---

## ADR-025 界面不做画布，用表格 + 右侧终端

**状态**：已决定

**决定**：不恢复旧稿的画布（机器卡片 + 物品节点 + 正交连线 + 画布坐标存档）。工作台是
「左侧机器表 + 右侧仓库/机器」的两栏结构。

**理由**：旧画布画的是「物品在机器之间流动」，但 ADR-008 已经定了机器间不直连——所有流动
都经过仓库，拓扑是一个以仓库为中心的星形，没有信息量。而它要求维护一份画布坐标存档、
一套相机与拖动逻辑，成本高、价值低。表格能在同屏看到更多机器，还能排序筛选。

**后果**：

- `MachineRef` 不需要画布坐标，不用为一台机器存两个位置（世界坐标 + 画布坐标）
- 「看工厂」这件事交回世界本身；终端只负责配置与诊断
- 空间感如果以后确实需要，用**机器真实坐标**画散点图（零持久化），而不是另造一套画布坐标

---

## ADR-026 界面入口只有 /idf gui，没有终端物品

**状态**：已决定

**决定**：删掉终端物品、玩家附件、装/卸奇点两个事件处理器，界面只由 `/idf gui` 打开。
仓库读 `Factory#storage()`，不再读玩家身上奇点绑定的 ME 网络。

**理由**：界面挂物品上时，「打不开界面」有三种可能原因（没物品 / 没装备奇点 / 奇点网络不对）。
现在只剩一条。而且仓库后端可能是**方块容器组**，本来就没有 ME 网络可读。

**后果**：

- 净删约 900 行（`TerminalItem` / `ModItems` / `ModCreativeTabs` / `ModAttachments` /
  `SingularityInteractionHandler` / `SingularityUnequipHandler` / `UnequipSingularityC2S` /
  `StorageSnapshotS2C` / `Ae2StorageView` / `ClientPacketHandler`）
- 奇点退化成纯粹的「绑定凭证」：`storage connect` 时从主手消耗
- 玩家没有持物入口；以后要加回来是一个独立小项

**后续变更**：2026-11-11 复核：入口已由 ADR-039 恢复为两个终端物品右键，本 ADR 的
「界面只由 `/idf gui` 打开」已被取代；保留作决策留痕。

---

## ADR-027 界面静态外观由脚本离线生成贴图，Java 只做 blit

**状态**：已决定

**决定**：面板、凹槽、按钮、输入框、分隔线等静态外观，由 `tools/gui/gen_textures.py`
（Pillow）生成成 PNG 放进 `assets/idlefactory/textures/gui/`；Java 侧用 `NineSlice` 切九宫格
贴上去，**不在代码里画边框、斜边、圆角**。

**理由**：窗口化之后要画的装饰多了（双层描边、内凹斜边、投影），每样都手写 `fill()`
既难对齐也难改；全放贴图里则细节好做、渲染代码只剩 blit，换主题只需重新生成一遍。

**后果**：

- 色值必须两边一致：`GuiTheme` 的常量 ↔ 生成脚本的调色板；改一边会造成贴图与文字脱节，
  而脱节在截图里看不出来
- 1×1 贴图（分隔线、底色）用「拉伸填充」，同样是一次 blit，不引入几何图元
- `GuiTheme` 只管文字色与状态色（它们要随主题变、又不能进贴图）

---

## ADR-028 物品身份包含数据组件，IO 规则分精确 / 模糊

**状态**：已决定

**决定**：物品资源身份 = 注册名 + 完整数据组件。`ResourceKey` 内部持一份 count=1 的 `ItemStack` 快照，相等 / 哈希直接复用 `ItemStack.isSameItemSameComponents` / `hashItemAndComponents`，与 AE2 的 `AEItemKey` 对齐。`ResourceSpec` 增加 `MatchMode`：`EXACT` 比较组件，`IGNORE_COMPONENTS` 只比较注册名；旧存档缺 `Mode` 的物品规则按模糊读入，新规则只在命令里写了组件时才精确。

**理由**：

- 1.21 的附魔、药水、命名、损伤都在数据组件里；只按注册名会把不同变体合并，取出时丢组件
- AE2 的仓库本身就是按精确变体存储，模糊只发生在输出总线 / 接口 / 样板这一侧
- 旧规则原本就是「按 id 搬」，迁移成模糊才能保持行为

**后果**：

- 仓库快照、GUI、网络、存档、AE2 适配全部保留组件；同名变体在界面各占一格
- 模糊输入需要枚举仓库变体，新增 `ItemVariantIndex`（TTL 20 tick、单 id 64、单规则 8 / 单组 16 每 tick）；模糊输出直接读机器实际栈
- 带组件的规则也可以显式切换为模糊：仍以样本注册名为匹配入口，忽略组件枚举所有变体；带组件快照但没有 `Mode` 的旧档仍按精确读入
- 仓库快照按 512 KiB 分片，避免大组件撑爆单包；协议版本随包结构递增
- 暂不做 AE2 的百分比模糊（99/75/50/25）；`IGNORE_COMPONENTS` 等价于 `IGNORE_ALL`
- `ResourceKey` 是「数据 record 不持活对象」的唯一例外：只持不可变副本

---

## ADR-029 AE2 的 FE 通过 Applied Flux 的 FE key 桥接

**状态**：已决定

**决定**：AE2 仓库的 FE 走 Applied Flux 的 `FluxKey.of(EnergyType.FE)`，只桥接 `EnergyType.FE`（GTEU 忽略）。Applied Flux 是可选依赖：`compileOnly + localRuntime`，所有类型收进 `AppFluxKeys.Bridge` 懒加载，未安装时 `available()` 短路并降级。

**理由**：

- AE2 原生只有物品 / 流体，FE 必须依赖附属的 key 类型
- 直接把附属类散在业务代码里会让「没装附属」变成类加载崩溃
- `-PwithoutAppFlux` 可以在开发环境验证未安装路径

**后果**：

- `Ae2Storage.supports(ENERGY)` 以 `feKey() != null` 为准；没装附属时 FE 只走方块容器
- FE 图标优先用 `EnergyType.FE.getIcon()`，未装时退回绿色水贴图
- Applied Flux 版本升级时先看 `FluxKey` / `EnergyType` 的 API 是否变化

---

## ADR-030 仓库与机器库排序是纯客户端视图

**状态**：已决定

**决定**：仓库与机器库的排序键、方向只存在于客户端界面，服务端快照顺序、协议和存档不变。默认顺序保持服务端/登记顺序与稳定追加，避免 Shift 连续取物时格子跳动。排序偏好写入客户端 CLIENT 配置（`config/idlefactory-client.toml`），关闭界面与重启客户端都保留；不写存档、不参与服务端。

**理由**：

- 排序是「怎么看」，不是工厂配置；服务端不该为每个玩家的界面偏好改快照顺序
- 默认顺序如果随数量重排，Shift 连续取物会点错格；稳定追加是刻意行为
- 排序键与显示名解析都在客户端，服务端不需要知道资源显示名

**后果**：

- `FactoryStorageRepo` 只接收 `Comparator`，在本地过滤/排序；`client.storage` 不依赖界面排序键
- 仓库范围过滤（全部 / 仅物品 / 仅流体，FE 归流体）也在客户端；数量排序里流体按 B 比较（1000 mB = 1 B），与格子显示单位一致
- 排序入口是搜索行右侧的三个 14×14 图标按钮：排序键 / 方向 / 范围；左键正向、右键反向循环
- `PopupMenu` 已并入 `LineSelectorMenu`（只有产线二级菜单一个使用者），不再保留通用菜单类
- 不改 `PROTOCOL_VERSION`，不新增网络包
- 数量/名称等排序下，快照刷新会重排；这是用户主动选择排序的预期代价

**后续变更**：2026-11-11 复核：第一版工作台（含这里的仓库 / 机器库排序入口与客户端配置）
已随 ADR-039 删除，客户端排序配置不再存在；终端排序改由 ADR-041 定义（六档 + 方向，
偏好写物品组件）。

---

## ADR-031 绑定杖：机器与仓库数据留在服务端

**状态**：已决定

**决定**：机器链接写入工厂机器库，仓库链接写入工厂的 `StorageAnchor`；物品只做入口，不存链接。机器断开复用回收站语义：没配过规则直接删并级联 IO 组，配过规则进回收站并保留规则。连锁用 6 邻接 BFS，单次最多 128 台、访问最多 4096 个方块、半径 32 格；同 id 但不是有效目标的方块不扩展。存储分 ME 与容器两种模式：A 模式用 `MeAnchor` 记录 ME 方块坐标，只允许一个；B 模式用 `ContainerAnchor`，最多 16 个；A/B 互斥，切换前必须清空。客户端只发动作，服务端校验后回 `FactoryLinksS2C`；客户端按主手物品渲染绿色机器框 / 蓝色仓库框，穿墙、64 格内、视锥裁剪。

**后续变更**：2026-10-29 删除量子环地址（`Ae2Anchor`）与 `/idf storage connect`，只保留 ME 与容器两种；`FactoryState` 同时删除。网络协议版本当前为 18；机器删除语义改由 ADR-032 接管。

**理由**：

- 链接是工厂配置，不是物品 NBT；物品丢失不应丢链接
- 服务端权威，客户端不能自报连接成功
- 6 邻接 BFS 不会把斜角/装饰方块误连，且可限制数量与半径
- A/B 共用一个仓库地址，天然互斥；模式独立存工厂，清空后仍记得

**后果**：

- 机器库、仓库地址与 `StorageMode` 继续写 `FactoryStore`
- ME 接入点按方块坐标解析 `IGridNode`；区块未加载或网络离线时仓库离线
- 容器上限保持 16（IO 每 5 tick 遍历所有容器槽的性能护栏）
- 连锁机器单次 128；已绑定机器渲染当前按快照全量（≤512）
- 客户端每帧最多提交两批线框：外层淡光 + 内层亮线

---

## ADR-032 规则跟机器走，失联只标记，显式删除

**状态**：已废弃（superseded by ADR-034 / ADR-035，2026-11-05）

**决定**：

- IO 规则从产线的 `IoGroup` 搬到机器本身（`Machine.io`，`MachineIoGroup`）；产线只存 `List<UUID> machineIds`。
- 方块没了只把机器加入 `FactoryLive.machineMissing`，机器和规则留在机器库，UI 画屏障；方块回来自动清除。
- 从产线移除机器只删引用，机器和规则回到机器库。
- 删除机器是显式动作：没有规则直接删；有规则时 UI 弹确认，指令需 `confirm`；连锁只删没有规则的机器，跳过并提示去界面删有规则的。
- 移除回收站：`FactoryBin`、`retired`、`/idf factory bin` 全部删除。

**理由**：

- 规则属于机器，不属于产线；移动/移出产线不应改变规则。
- 失联是观测事实，不是玩家意图；用 `machineMissing` 表达，不写档。
- 删除是唯一会丢规则的入口，把确认放在这里，比维护一个回收站简单。

**后果**：

- 存档：`Machines[].Io` + `Lines[].MachineIds`；没有 `retired`，没有版本迁移。
- 机器失联仍占门禁位置，直到显式删除。
- `FactoryHealth` 只统计活跃 / 未加载 / 失联。
- 界面协议 18：机器快照带 `machineMissing`，删除机器走 `FactoryLineActionC2S.DELETE_MACHINE`。

---

## 待定

| 编号 | 问题 |
|---|---|
| TBD-01 | 界面右侧要不要接到 AE2 合成终端（合成槽 / 中键下单 / 样板管理） |
| TBD-02 | 升级的具体数值 |
| TBD-03 | 被动模式停机策略（容量上限 vs 目标存量） |
| TBD-04 | 配置器 vs 蓝图的边界与形态 |
| TBD-05 | AE2 pattern 是否复用、两套 CPU 是否互通 |
| TBD-06 | 多人权限细节（两人同时配置同一台机器） |
| TBD-07 | 「每台机器二选一」是否成立 |
| TBD-08 | 工厂维度是否还需要 |
| TBD-09 | 多方块机器怎么接入 |
| TBD-10 | AE2 与容器共存时的自指检测（storage bus 引用同一容器） |

---

## ADR-033 资源身份统一为 AE2 AEKey，删除产线与自研存储抽象

**状态**：已决定（2026-10-31）

**决定**：

1. 资源身份直接用 AE2 的 `AEKey`（`AEItemKey` / `AEFluidKey` / Applied Flux 的 `FluxKey`），删除自研的 `ResourceKey` 与 `ResourceKind`。
2. 删除 `Line` 数据对象，IO 按机器库遍历，产线降级为纯 UI 分组。
3. 删除自研 `Storage` 接口，三个后端统一实现 AE2 的 `MEStorage`；模拟 / 提交的布尔语义收进 `AeKeySupport`。
4. 资源类型覆盖范围 = AE2 原生 + 已安装的 AE 生态附属；FE 依赖 Applied Flux，化学品依赖 Applied Mekanistics，没装就不支持，不写降级表示。
5. 观测层的能力分类独立成 `CapabilityKind{ITEM, FLUID, ENERGY}`，不再复用资源类型枚举。

**理由**：

- 自研资源模型是「仓库以容器为主、AE 只是可选后端」时期的产物（ADR-019 / ADR-020）。现在 AE2 是硬依赖、ME 是主线，平行模型只剩下转换成本：`ResourceKey` 的相等与哈希当年就要手动对齐 `AEItemKey`，等于在复刻一个已经存在的东西。
- 下一步要做虚拟样板供应器，样板、`ICraftingProvider`、CPU 派单给的都是 `AEKey`；留一层自研 key 等于每次派单都翻译一遍。
- 产线只是分组，不该是数据对象；删掉后 IO 遍历少一层，机器库成为唯一遍历单位。

**后果**：

- 存档不兼容，开发期直接弃档，不写迁移。
- 规则描述改为 `名字[id][组件][模糊][面]`，便于指令核对组件身份。
- ADR-018（命令树以产线为主语）作废，指令改为 `/idf machine <id> input|output ...`。
- ADR-020（仓库按资源类型建模）作废，`ResourceKey(kind, id)` 不再存在。
- 后续若要让 FE 进 AE，直接复用 Applied Flux 的 key，不再自建 `AEKeyType`。

---

## ADR-034 节点 → 机器 → 方块三层数据模型

**状态**：已决定（2026-11-05）

**决定**：

1. 数据模型固定为三层：`Node` → `Machine` → `BlockKey`。节点 id、机器 id 都是随机 UUID；`BlockKey` 只表示「维度 + 坐标」，不派生任何 id。
2. 节点与机器都不存维度；维度挂在方块上。节点内机器可以跨维度，同一台机器内部方块也可以跨维度。任何方块不可用 → 整台机器 OFFLINE。
3. 节点类型只认**主方块**：每台机器的 `blocks.get(0)` 是类型 / 展示方块，`Machine.typeBlock()` 固定返回它；单方块机器是退化情况，不单开分支。部件不参与类型判定。
4. 节点内所有机器的 `blocks.get(0)` 类型 id 必须等于 `Node.typeBlockId`；唯一例外是「全离线自动迁移」（见 ADR-035）。
5. `Node` 是浅不可变聚合：字段全部 final，结构变更返回新实例并让 `topologyRevision` 加一；`NodeIoGroup` / `NodeProvider` 是可变的，由新旧节点共享，加机器不复制规则与样板。
6. `NodeLibrary` 维护 `byId`、`machineToNode`、`blockToOwner` 三张 O(1) 索引；写 API 只有 `add` / `replace` / `remove`，全部「先全量校验、再一次性写索引」，失败不改索引并返回结果枚举。
7. 存档只写 `Nodes[]`；`NextSeq` 仍留在存档根级，不属于任何工厂记录。不写旧档迁移。
8. 健康只有两套枚举：`MachineHealth{OFFLINE, ONLINE}` 与 `NodeHealth{OFFLINE, PARTIAL, ONLINE}`；方块级 `Health` 只用于诊断与 tooltip。「未探测」与「健康过期」是独立布尔，不塞进健康枚举。

**理由**：

- 「1 机器 = 1 节点 = 1 方块」无法表达堆叠与多方块；把节点单独建模后，规则与样板天然按节点共享。
- 随机 id 让加 / 删方块不再改变机器身份，避免坐标派生 id 在多方块场景下反复漂移。
- 三张索引把「按机器找节点」「按方块找归属」做成 O(1)，绑定提交与 IO 门禁都靠它。
- 浅不可变让结构变更可原子替换，同时保留规则 / 样板的可变宿主，不复制大数据。

**后果**：

- `AGENTS.md` 1.2 / 2.2 / 3.1 中「机器库、每台机器带自己的 IO 规则」等表述已被本 ADR 取代；在 AGENTS 修订获批前以 ADR 为准。
- 旧存档不兼容：旧世界加载为空是预期，测试世界重建。
- 协议在 P1b 升到 27（视图带 nodeId / 健康 / probed / stale / 成员每块维度），P6 升到 28，P7 升到 29。

---

## ADR-035 规则 / provider 归节点，健康与类型迁移

**状态**：已决定（2026-11-05）

**决定**：

1. IO 规则归节点（`NodeIoGroup`）：规则作用于节点内每台在线机器的每个方块，堆叠机器共用一套；节点 PARTIAL 时只跑 ONLINE 的机器。
2. provider 归节点（`NodeProvider`）：一个节点一个 `PatternContainer`；阻挡、倍率、派单、样板、余量都是节点级配置。一次 `pushPattern` 只选一台 ONLINE 机器，整份货给它，收不下的进余量队列；余量条目绑定原机器 id，原机器没了退回 ME 网络，`MAX_PENDING = 64` 是硬顶。
3. 机器健康：所有方块 PRESENT 才算 ONLINE；任一方块不可用即 OFFLINE。节点健康按机器聚合：全部在线 ONLINE、部分在线 PARTIAL、全部离线 OFFLINE。未探测按 OFFLINE 处理，用 `probed=false` 单独表达。
4. 健康过期：方块 `probed && now - lastProbeTick > staleHealthTicks` 才算 stale；从未探测过时 `stale=false`，不能把 `lastProbeTick` 的 `-1` 哨兵算成过期。机器任一方块 stale 即 stale，节点任一机器 stale 即 stale。
5. 全离线自动迁移：只有节点所有机器都 OFFLINE、所有 `blocks.get(0)` 都 PRESENT、blockId 非空且是同一个新 id、且与当前 `typeBlockId` 不同时才迁移；陈旧快照（stale）不参与迁移。部分换、类型不一致、方块是空气 → 不迁移。
6. 派生值不存第二份：健康每次由「节点拓扑 + 方块观测快照」计算；`FactoryObservationRunner` 是 `withTypeBlockId` 的唯一写入点，`NodeHealthResolver` / `NodeTypeResolver` 是纯函数。

**理由**：

- 规则跟着机器走会让堆叠机器各配一套，玩家改一次要改 N 次；归节点后一次编辑全体生效。
- provider 归节点才能保证「一份货只给一台机器」，避免 AE2 侧看到 N 个供应器重复广告。
- 健康过期独立于「未探测」，可以区分「还没看过」与「看过但太旧」，避免拿过期健康做搬运判断。

**后果**：

- `FactoryLive.machineMissing` / 离线集合在 P2 由 `NodeHealthResolver` 派生取代；事件流只保留节点级与机器级 id。
- IO 睡眠按机器 id，路由按 `BlockKey`；读路径只读观测缓存。
- 文件改名在 P1c 完成：`MachineNode → NodeRuntime`、`MachineCraftService → NodeCraftService`、`MachinePatternInventory → NodePatternInventory`、`MachineTerminalGroup → NodeTerminalGroup`。

---

## ADR-036 样板倍率：固定 / 智能，乘法和容量探测

**状态**：已决定（2026-11-05）

**决定**：

1. 倍率是节点级配置，模式 `OFF / FIXED / SMART`；`FIXED` 带固定倍数，`SMART` 在派单前探测。
2. 倍率乘法一律用 `Math.multiplyExact`，倍率硬上限 `Multiplier.HARD_MAX_FACTOR = 64`；溢出或超过硬上限的样板跳过并记日志，绝不产生负数数量。
3. 智能倍率只用 `Actionable.SIMULATE` 探测「一台机器能装几份」：二分找最大 k，多个输入要求同一 k 都能装下（等价取最小），夹在 1..硬上限；探测失败回退 ×1。
4. 缓存键 = 样板 + 节点拓扑版本；缓存的是探测当时的静态上限 kMax，不是实时空位。机器逐渐变满后 kMax 可能偏大，收不下的部分进余量，不重探测、不重复扣料。
5. 一次 `pushPattern` 只选一台 ONLINE 机器，整份货给它；固定倍率 N 大于容量时多出的进余量，输入输出同倍。
6. 探测与实际 push 之间机器状态变了：按「已提交」处理，收不下的进余量，不回退成 push 失败（避免 CPU 复制）。

**理由**：

- 倍率让 AE2 一次看到 N 份样板，减少 CPU 调度次数；但数量必须防溢出，否则样板会变成负数并复制资源。
- 智能倍率按「一台机器能装几份」而不是「整个节点能装几份」，与「一次货只给一台机器」的不变量一致。
- 缓存静态上限避免每次 push 重探测，代价只是余量队列偶尔多几条。

**后果**：

- `factory/node/ScaledPatternDetails` 包装 `IPatternDetails`：只放大每个输入的 `getMultiplier()` 与产物数量，输入模板数量不动（AE2 的实际消耗 = 模板数量 × multiplier，两层都乘会变成倍率平方）；派单时按接口默认实现把 CPU 算好的 inputHolder 原样推出，不委托原样板。
- `NodeCraftService` 负责选机器、选面、部分插入与余量入队。
- 端到端倍率验收留到 P12 统一游戏内测试。

---

## ADR-037 绑定杖草稿：客户端权威 + 服务端原子提交

**状态**：已决定（2026-11-05）

**决定**：

1. 不做服务端会话、不做 TTL；草稿的权威副本是客户端静态缓存，并镜像到绑定杖的物品组件。组件带 persistent Codec，会随物品栈落盘——这是预期行为，不是需要规避的东西；1.21.1 的 `DataComponentType.Builder.build()` 强制要求 Codec，不存在无 Codec 的组件。
2. 草稿结构：模式 `SINGLE / STACK / MULTIBLOCK / STACKED_MULTIBLOCK`、可选 `nodeId`（{@code null} 表示新建节点）、机器列表与每台机器的方块列表。
3. 手势：SINGLE 普通左右键保持 1:1 toggle；STACK / MULTIBLOCK / STACKED_MULTIBLOCK 用蹲下右键开草稿、蹲下右键空气提交、蹲下左键空气取消；左键方块从草稿移除。
4. 草稿不跨登录保留：登录 / 登出 / 换维度 / 换手 / 把杖放进容器时清空。
5. 客户端只做简单核验（主手、重复块、堆叠同 id）；服务端按玩家工厂与 `LinkGate` 逐块重查，不读组件、不信坐标。
6. 服务端提交顺序固定：`LinkGate` 快照 + 本地 `Set` 查重 → 全量校验（归属 / 类型 / 上限 / 节点归属）→ 内存构造候选 `Node`（已有节点原对象不动）→ 只调一次 `NodeLibrary.add/replace` → `markDirty` 触发 `LinkRegistry.rebuild`。失败只丢候选，旧节点与索引不变。
7. 编辑已有节点只追加机器，绝不删除原有机器 / 方块。
8. 不校验距离；不做防作弊。

**理由**：

- 服务端会话需要超时、注销与并发清理，收益只有「防止重放」；草稿一次提交本身已经幂等性受限，不如把校验做在提交包里。
- 组件不持久化、不同步，正好满足「不跨登录保留」且不污染存档。
- 候选 Node + 原子 add/replace 让失败路径不需要回滚，旧节点完全不受影响。

**后果**：

- 协议 29 新增 `FactoryBindingCommitC2S/S2C`；草稿模式与方块逐块编码，上限受 `HARD_WIRE_ENTRIES`。
- `ModDataComponents.BINDING_DRAFT` 带 persistent Codec，草稿会随物品栈落盘；但它不参与绑定校验，服务端只信提交包。
- “不跨登录保留”由客户端保证：登录 / 登出 / 换维度 / 换手 / 把杖放进容器时清空草稿并移除组件。
- 世界蹲下右键编辑已有节点依赖最近一次机器表里的「方块 → 节点」映射；没有映射时退回节点终端的编辑入口。

---

## ADR-038 config 上限与不可关的硬上限

**状态**：已决定（2026-11-05）

**决定**：

1. 玩法上限全部进 `FactoryLimitsConfig`（COMMON），默认 `-1` 表示无上限；正数表示上限；`0` 非法。注释中英双语。
2. 有效值 = `min(config, 硬上限)`；config 负数视为「用硬上限」。config 改小不删已有数据，只阻止继续新增。
3. 硬上限不可关：节点数 4096、单节点机器 256、单机器方块 256、单节点方块 4096、全工厂机器 8192、全工厂方块 32768、每侧规则 64、样板槽 1024、倍率 64、名字长度 32767。
4. 观测预算与健康过期是调优项：`observationBlockBudgetPerTick` 默认 64、硬顶 256；`staleHealthTicks` 默认 1200、硬顶 2400；热改下一 tick 生效。
5. 网络解码固定 `HARD_WIRE_ENTRIES = 32768`（条目/包），解码用 `min(有效值, HARD_WIRE_ENTRIES)`；它不是玩法上限。
6. `MAX_PENDING = 64` 是硬常量，不进 config；余量满了退回 ME 网络，网络也收不下才按现有掉落兜底并记日志。

**理由**：

- 玩法上限可调，但服务端内存与线协议不能被恶意包或误配置击穿，所以硬上限单独存在。
- `-1 = 无上限` 让默认行为保持「不限制」，同时保留调优空间。

**后果**：

- `FactoryLimitsConfig` 的硬上限常量引用各数据类自己的常量（`NodeLibrary` / `Machine` / `NodeIoGroup` / `NodeProvider` / `Multiplier` / `Node`），避免两处数字漂移。
- 观测预算由 `FactoryRuntime` 以 `IntSupplier` 注入 `ObservationService`，观测层不直接读配置。

---

## AGENTS.md 修订提案（待用户批准，不直接修改）

以下条目已被 ADR-034 / ADR-035 取代，建议在下一轮文档更新中修改 `AGENTS.md`：

1. `1.2 核心设定`「机器库（每台机器带自己的 IO 规则）」→「节点库：节点 → 机器 → 方块；IO 规则与 provider 归节点，堆叠机器共用」。
2. `2.2 三层信息`「机器库」→「`NodeLibrary` + `Machine`」；IO 规则宿主由机器改为节点。
3. `3.1 规模与性能`「机器表 512、规则每侧 16、观测 8 次/tick」→「节点 / 机器 / 方块上限见 `FactoryLimitsConfig` 与硬上限表；观测预算默认 64/tick，可配」。
4. `3.3 便利性`指令以机器为主语 → 增加「`/idf node ...` 以节点为主语；`/idf machine ...` 仍按机器定位但规则作用于节点」。
5. `6.2 坑` 第 16 条「机器失联只进 `FactoryLive.machineMissing`」→「健康由观测快照现算，`probed` / `stale` 与健康独立；`FactoryLive` 不再存失联集合」。

在修订获批前，以 ADR 为准。

以下条目来自 ADR-042（IO 执行层重构），同样待批准后修改 `AGENTS.md`：

6. `2.1 命名后缀固定` 增加 `Form`（表单，内嵌 `Entry` / `Entry.Key`）与 `Job`（一次性任务；只带机器 / 方向 / 面 / 资源 / 量，不带结果）。
7. `2.1 命名后缀固定` 增补定位：`Order` 一单待投递、`Queue` 多单排队（唯一写入口）、`State` 一 tick 的输入值对象、`Pick` 执行侧只读查询器（探面 / 容量 / 变体）、`Runner` 每 tick 编排。
8. `2.2 三层信息` 执行层类名更新：`IoForm` / `IoUnit` → `Form` / `Form.Entry`，`IoUnitDigest` 合并进 `Analyzer`，`IoTransfer` 合并进 `IoExecutor.move`，`IoPortResolver` 拆成 `FacePick` + `PortExecutor`。
9. `6.2 坑` 第 6 条删除「必须先试无向、再逐面找」的表述：机器侧统一走 AE2 `MEStorage`，面由 `FacePick` 决定，物品规则不再单独走无向兜底。
10. `2.2 三层信息` 调用链更新为 `ServerTick → FactoryRuntime → FactoryTickRunner → IoRunner → ActivePlanner / PassivePlanner → IoExecutor → PortExecutor`。

---

## ADR-039 轻量化：删除命令行、第一版工作台与多方块，机器与方块统一

**状态**：已决定

**决定**：只保留两个 AE2 终端界面（被动 IO 管理、节点管理）与两把绑定杖：

1. **删除全部聊天指令**：`/idf` 与 `/idlefactory` 整棵树（含 diag 文本诊断、仓库控制台、仓库清空确认）移除；建厂改为打开终端时自动完成。
2. **删除第一版工厂工作台**：`FactoryScreen` 及其渲染子树、`FactoryMenu`、工厂终端物品、R 键快捷键、仓库摘要协议全部删除；入口只剩两个终端物品右键。
3. **删除多方块**：`BindingMode` 只留 `SINGLE` / `STACK`；机器与方块统一为「一台机器 = 一个方块」，节点靠成员机器列表表达 1..N 台。`Node.blockCount`、方块数上限、每台机器的方块列表、协议里的方块字段全部消失。
4. **删除死代码**：零调用方法、未使用 import、失效配置键（`maxPatternCapacity` / `maxMultiplier`）、未使用贴图与失效的 access transformer 一并清理。

**理由**：界面已经是唯一入口，指令是并行维护的第二套交互与第二套文案；两代界面并存让共享渲染类被迫服务两种几何；多方块让「机器 / 方块 / 节点」三个概念互相缠绕，是数据层复杂度的主要来源。当前目标是加速开发，不是覆盖全部历史功能。

**后果**：

- `PROTOCOL_VERSION` 从 29 递增到 30（绑定提交包、节点表包、机器表包的字段变化）。
- 开发版不兼容旧存档：`Machine` 的 NBT 从 `Blocks` 列表改为单 `Block`，旧档的多方块机器读不出来即丢弃，不写迁移代码。
- 失去的能力：全部指令、文本诊断、IO 间隔可调、单台成员删除、仓库容器的手动 bind/unbind/prune/priority、仓库存取 get/put、`/idf gui` 兜底。
- 保留的能力：两个终端界面、绑定杖 toggle 与草稿提交、节点/机器/规则、仓库后端（AE2 与容器）、JEI / EMI / Applied Flux 适配。
- `FactoryPassiveIoHost` / `FactoryNodeHost` 打开终端时调用 `FactoryService.ensureFactory`，这是指令删除后唯一的建厂入口。

**回归条件**：重新引入命令行入口、第二套界面、或「一台机器多个方块」时，本决策作废，需要重新评估分层与协议。


---

## ADR-040 节点状态只算一份：NodeStatus + 协议 32 下发

**状态**：已决定（2026-11-11）

**决定**：

1. 新增 `NodeStatus(probed, stale, health, totalState)` 与 `NodeStatusResolver`，作为节点状态的唯一表示与唯一聚合实现。
2. 聚合口径固定，且全部用 any / all 表达，结果与成员登记顺序无关：
   - `probed`：全部成员都探过才算探过；
   - `stale`：任一成员过期就算过期；
   - `health`：全在线＝在线，部分在线＝缺机，全离线＝失联；
   - `totalState`：没规则＝无规则；任一成员堵塞＝输入堵塞；任一成员在干活＝运行；任一成员缺料＝仓库缺料；任一成员可输入＝可输入；其余都空闲＝空闲；否则未知。离线 / 过期成员不参与这一步；
   - 规则数是节点级事实，由调用方传入（服务端传节点的 `ruleCount`，机器表按组内共享值传），resolver 不从成员字段里挑一个；全员离线时 `totalState` 直接给 `OFFLINE`，空成员列表的 `probed` 为 `false`。
3. 服务端在快照构建时算一次：节点表通过 `NodeView.status` 下发，机器表通过 `FactoryTableS2C.nodeStatuses` 下发；客户端只做展示映射，不再自己聚合。
4. `FactoryStatus` 只保留一套节点判断顺序：未探测 → 健康过期 → 失联 / 缺机 → 在干什么。
5. 两个终端的搜索字段统一为 `FactoryNodeSearchFields`，状态词只读 `NodeStatus`；删除按机器状态拼接的旧搜索字段。
6. `PROTOCOL_VERSION` 从 31 递增到 32。

**理由**：

- 修 bug：服务端节点视图在成员循环里反复赋值，实际由最后一台机器决定"在干什么"；被动 IO 终端又拿第一台机器当节点状态。两个终端各有一套判断，导致同一节点状态文字、灯色、搜索命中都可能不同，且换一下成员登记顺序结论就变。
- 一个概念只建模一处：节点状态是"体检汇总结论"，必须一处算、所有入口读同一份。

**后果**：

- 协议 32，旧客户端不兼容，重进即可。
- 行为变化（已确认接受）：部分节点显示会变（例如最后一台空闲、前面有台堵塞，节点从"空闲"变"输入堵塞"）；被动 IO 里第 2 台过期、第 1 台正常时从"正常"变"健康过期"；搜"失联 / 运行"在两个终端命中同一批节点。
- 删除 `FactoryMachineSearchFields` 与被动 IO 的节点状态自行聚合；节点状态不再由客户端拼装。
- 被动 IO 机器行名字的「已失联」改为读 `NodeStatus.health()`，不再看第一台机器的健康，避免「名字已失联 + 灯是缺机」的自相矛盾。
- 只影响界面（文字、灯色、搜索、tooltip、排序位置）；搬运、规则执行、存档不读这个状态。


---

## ADR-041 终端排序：六档、方向与物品组件持久化

**状态**：已决定（2026-11-11）

**决定**：

1. 两个终端共用一套排序偏好：档位 `FactorySortMode`（NAME / ID / MACHINE_COUNT / RULE_COUNT / STATUS / DISTANCE）+ 方向（AE2 `SortDir`），存在物品的 `ae2:exported_settings` 组件里（键 `terminal_sort_mode` / `terminal_sort_direction`，默认 NAME / ASCENDING）；每个终端物品各自记住自己的选择，同类终端不同副本互不影响。
2. 排序单位是节点：节点管理终端一行＝一个节点，被动 IO 终端一块＝一个节点；规则行与成员行不参与排序（规则下标是动作身份），列表 / 磁贴两种视图共用一套顺序。
3. 比较链：主键（当前档位）→ 名称 → 机器台数 → 节点 UUID；方向按钮把整条链反转，所以降序时四把尺子一起反。
4. 状态档的等级来自展示档 `FactoryStatus.Kind`：运行 0、可输入 1、空闲 2、无规则 3、未知 4、仓库缺料 5、输入堵塞 6、未探测 7、缺机 8、健康过期 9、失联 10；升序＝从好到坏，排序位置与灯色永远一致。
5. 距离档取节点内离玩家最近的成员，按平方距离比较；玩家不在该维度视为无穷远；只在快照重建时用当时的玩家位置重排，不做每帧重排。
6. 排序在客户端模型 `rebuild()` 内做（过滤后、排版前）；服务端不参与排序，只负责写回偏好。新增 C2S / S2C 一对包，`PROTOCOL_VERSION` 从 32 递增到 33。
7. 改名框 / 删除确认框打开时冻结行序：快照照常更新数据，列表不在用户操作期间跳动。
8. `FactorySortMode` 的声明顺序同时是档位按钮的循环顺序、物品组件与线协议的 ordinal：只允许末尾追加，不允许重排。

**理由**：

- 排序是纯视图偏好，放客户端才能不改快照、不动服务端排序；偏好放物品组件与「列表 / 卡片视图」同源，跟着终端物品走。
- 一个概念一处定义：比较链只在 `FactorySortKey.comparator`，状态等级只在 `FactoryStatus.sortRank`，两个终端只把视图翻译成排序键。

**后果**：

- 旧终端没有这两个键，`IConfigManager` 回落到默认（名称升序），不需要迁移代码。
- 协议 33，旧客户端不兼容，重进即可。
- 状态档与距离档会随快照（约每秒）重排；打开改名框 / 删除确认框时冻结。
- 名称排序按 Unicode 码位，不接拼音；中文名的顺序是码位顺序，不是拼音顺序。
- 三张自绘图标（状态 / IO 规则数 / 距离）由 `tools/gui/gen_textures.py` 生成，Java 只 blit；旧的三档继续用 AE2 的 `SORT_BY_NAME / SORT_BY_MOD / SORT_BY_AMOUNT`，方向用 `ARROW_UP / ARROW_DOWN`。


---

## ADR-042 IO 执行层：统一 MEStorage，规划器出 Job，Analyzer 记账

**状态**：已决定（2026-11-12，按 `docs/IO执行层重构计划.md` 第 7 轮执行）

**决定**：

1. **表单只有一个类**：`Form` 内嵌 `Entry` / `Entry.Key`，一条规则一行；条目离开表单即释放账本与结论（机器短暂 UNLOADED 再回来从零开始，这是预期行为）。
2. **机器侧统一走 AE2 `MEStorage`**：删除 `ResourceAdapter` 与物品 / 流体 / 能量三个适配器；`PortExecutor` 按「机器 + 面」缓存 `MEStorage`，失效只有两个入口——动作失败（`REJECTED` / `ERROR` 失效该面、`NO_FACE` 失效整台机器）与生命周期（机器 / 节点删除时 `drop`）。缓存不做世界检查，取用直接命中。
3. **选面与容量分开**：`FacePick` 只回答走哪个面（配置面 → 经验面 → 逐面探；配置面无效不兜底，记 `FACE_CONFIG_INVALID`）；`CapacityPick` 只回答这一轮能装几份（逐行模拟取最小）。执行器不选面、不决定搬多少。
4. **规划器出 Job，执行器只跑事务**：被动由 `PassivePlanner` 产出 `IoJob`，主动由 `ActivePlanner` 产出 `Fill`；`IoExecutor` 只有 `move` / `fill` / `refund` / `sweep` 四个写入口，`moved` 一律是机器侧实际收下 / 交出的量，是记账的唯一依据。
5. **主动队列属于节点**：`DispatchOrder` 带订单 id、`perPortion`（不带面）、总份数、`donePortions`、`openBatches`、轮询起点与退避时刻；`DispatchQueue` 是内存与存档的唯一写入口，`refundAll` 供删节点与以后的手动按钮用。节点只把 `DispatchState` 交给执行层，`io` 不 import `node`。
6. **派单拆到执行层**：`pushPattern` 只做校验 + 拆份 + 入队，不选机、不选面、不做阻挡判定；分配、阻挡、退避与孤儿超时都在 `ActivePlanner`。多机分配：顺序逐台塞满，轮询多机平均分；半份优先补齐且不挪到别的机器。
7. **执行顺序固定为 回收 → 主动 → 被动**，由 `IoRunner` 统一编排，异常按机器 / 节点兜住。
8. **Analyzer 只记不判**：被动账本按条目、主动账本按节点；结论含节点队列状态与 `deliveredTotal`、机器阻挡缓存；经验面被动存条目结论、主动存「机器 + 资源」（TTL 1200 tick，每机器 512 条栅栏），淘汰与清理都在 Analyzer 内部。
9. **网络协议**：`Reason` 末尾新增 `FACE_CONFIG_INVALID`（severity 4），`PROTOCOL_VERSION` 35 → 36。
10. **常量位置**：`io` 内部使用的上限（端口缓存栅栏、经验面 TTL / 栅栏、退避阶梯、扫描上限）留在使用它们的 `io` 类里，不放进 `factory.FactoryLimitsConfig`——否则 `io` 会反向依赖 `factory`，破坏单向依赖。

**理由**：

- 一个概念一处定义：资源身份 `AEKey`、观测键 `BlockKey`、规则 `ResourceSpec`，执行层不再有自己的资源适配体系。
- 可测与可推演：选面（`FacePick`）、容量（`CapacityPick`）、事务（`IoExecutor`）三者拆开后，每个都能脱离游戏单独验证。
- 队列归节点：删一台机器不再作废整单，材料也不会因为机器掉线被反复退款。

**后果**：

- 存档：`Provider` 里的队列格式换成 §6 新字段，旧 `Pending` 格式直接丢弃（D26 允许重建世界）。
- 行为变化：主动一单可分给多台机器、跨 tick 交付；智能倍率不再有 64 倍业务截断，改为「在线机器容量之和的 25%」；倍率溢出从「丢整张样板」改为「夹回安全倍数」。
- 文件数：`io` 23 → 21、`node` 22 → 16（计划 §4.4 的 18 / 17 是估算时漏算了搬家与拆分的净增，以实际为准）。
- `AGENTS.md` 的命名后缀与坑条修改见上面的修订提案，需单独批准。


---

## ADR-043 类命名：限定词 + 后缀

**状态**：已决定（2026-11-13）

**决定**：

1. **类名 = 限定词 + 后缀**。后缀表达「这是哪种东西」，限定词表达「谁的 / 哪个子领域的」；只有三种限定词来源：
   - 层名：`Io`；
   - 子领域：`Dispatch`；
   - 主题或功能词：`Blocking`、`Multiplier`、`Provider`、`Active`、`Passive`、`Face`、`Capacity`、`Variant`、`Port`。
2. **后缀只取两张表**（`AGENTS.md` §2.1）：角色后缀（`Service` / `Runner` / `Planner` / `Executor` / `Analyzer` / `Pick` / `Ledger` / `Manager` / `Library` / `Resolver` / `Adapter` / `Builder` / `Handler` / `Store` / `Stats` / `Render` / `Messages` / `Refs`）与值后缀（`Form` / `Job` / `Order` / `Queue` / `State` / `Side` / `Mode` / `Reason` / `Result` / `Spec` / `Key` / `Status` / `Health` / `Group` / `Hint` / `Conclusion`）。
3. **`Mode` 统一表示互斥取值**：两值开关（`BlockingMode`、`DispatchMode`）与多档位（`MultiplierMode`、`ProviderMode`、`MatchMode`）都叫 `Mode`，不设 `Switch` 后缀。
4. **状态三分不许串**：`Health` 只答「在不在」，`Status` 只答「能不能干活」，`State` 只答「此刻是什么」。
5. **包的领域根类型可以裸名**（`Node`、`Machine`），其余一律要有可读限定词。
6. **`io` 包按此规范落地**（21 个顶层类，零裸名）：
   - `Io*`：`IoForm`、`IoKey`、`IoSide`、`IoReason`、`IoResult`、`IoJob`、`IoExecutor`、`IoRunner`、`IoAnalyzer`；
   - 功能策略：`ActivePlanner`、`PassivePlanner`、`FacePick`、`CapacityPick`、`VariantPick`、`PortExecutor`；
   - 主动队列：`DispatchOrder`、`DispatchQueue`、`DispatchState`；
   - 开关：`BlockingMode`、`DispatchMode`。
   嵌套类型：`FacePick.Result`、`ActivePlanner.FillRequest` / `RefundRequest`。
7. **旧名对照**（ADR-042 正文里的名字按此读）：`Form` → `IoForm`，`RuleKey` → `IoKey`，`Reason` → `IoReason`，`MoveResult` → `IoResult`，`Analyzer` → `IoAnalyzer`；`BlockingMode` / `DispatchMode` 名字不变（后缀在 `Switch` 与 `Mode` 之间来回过一次，最终定为 `Mode`）。

**理由**：

- 只有后缀的裸名（`Side`、`Reason`、`Form`、`RuleKey`）在跨包 import 时答不出「是什么的」，限定词提供这个答案；`IoKey` 的 Javadoc 另外写死「不是资源键」。
- 包名不能当限定词用：`io.Reason` 在 `client` 里读作 `Reason.RUN`，没有上下文；`io.IoReason` 自解释。
- 一个后缀只表达一件事，才能机械检查：`Mode` 一旦允许「开关用 Switch」，`DispatchMode` 与 `BlockingMode` 就会分家；统一成 `Mode` 后两者同构。

**后果**：

- 协议不变：`IoReason` 的枚举 ordinal 与声明顺序未动，`PROTOCOL_VERSION` 仍为 36。
- `AGENTS.md` §2.1 已同步两张后缀表与第 9 条硬条款；§6.3 记录本次偏差修正。
- `node` 包按用户要求暂不动：`Multiplier` → `MultiplierSpec`、`ScaledPatternDetails` → `ScaledPattern` 仍待办。

---

## ADR-044 视图表：一条读通道与客户端重组

**状态**：已决定（2026-11-20）

**决定**：

1. **读侧只有一张表**。服务端在 `FactoryRunner` 里把工厂状态聚合成 `ViewTable`（`ViewHeader` + 节点列表：`ViewNode` / `ViewNodeCore` / `ViewIoRule` / `ViewMachine`），客户端只做展示映射。`MachineSnapshot` / `NodeSnapshot` / `ProviderSnapshot` / `NodeStatus` / `NodeStatusBuilder` / `TotalState` 与协议 32 的「节点表 + 机器表」两张快照全部删除。
2. **聚合口径不变**：原 `NodeStatusResolver` 的 any / all 规则原样搬进 `ViewTableBuilder`（未探测优先、过期其次、失联 / 缺机再次、最后看活动），`INPUT_BLOCKED` 的 200 tick 宽限常量保留。
3. **两段式下发**：整表分页（`ViewTableS2C`，按 16 KiB 预算切页）与差量（`ViewTableDiffS2C`，`ViewIoRule.identity()` 做行身份、`machineId` 做机器身份）。`ViewPort` 是唯一出口；开发期内部编号到 42，首个公开测试版发布前**重置为 1**，旧编号只作历史记录。
4. **客户端结构**：`client` 拆 `world`（世界高亮）/ `wand`（绑定杖输入与草稿）/ `network`（报文投递）/ `screen`；两个终端分到 `terminal.node` 与 `terminal.passiveio`，共用 `TerminalSource`（分页拼装与差量应用）、`TerminalModel`（搜索 / 排序 / 玩家上下文）、`TerminalChrome`（外壳与标题）。
5. **命名**：客户端类去掉 `Factory` 前缀（`NodeScreen` / `PassiveIoModel` / `WandHud` …）；排序键与上下文进 `screen.sort`（`SortKey` / `SortContext`），搜索字段进 `screen.search.SearchFields`；`ResourceKeyCodec` → `AeKeyCodec`，`NoticeHandler` → `ObservationNoticeListener`，`ModNetwork` → `IdleFactoryNetwork`。
6. **颜色与文案**：删除 `FactoryGuiTheme`，颜色归属组件（`paint/StatusColor` 集中四色）；界面文案走 `idlefactory.*` 语言键（中英各一份），代码里不再有中文字面量。
7. **绑定杖链接快照**带仓库锚点：`WandLinksS2C(storageAnchor, nodes)`，锚点先编码、再编码节点数。

**理由**：

- 两条并行的读通道（节点表 + 机器表）需要客户端自己按节点合并，聚合规则必然漂移；一张表把口径钉在服务端。
- 分页 + 差量让首屏与后续 tick 都不必整表重发，同时保留「客户端零聚合」。
- 客户端按「数据源 / 模型 / 排版 / 绘制 / 交互 / 外壳」切分，才可能让两个终端真正共用一套基座。

**后果**：

- 协议号发布重置为 1（开发期到 42）；旧的两张表负载与客户端 Handler 已删，没有兼容层。
- `AGENTS.md` §2.1 / §2.4 / §3.2 / §5.4 / §6.1–6.3 已同步；`docs/README.md` 与 `README.md` 的结构说明同步更新。
- 偏差记录：多出 `ViewTableCodec` 一个编解码文件；`FactoryLifecycle.stop()` 额外调用 `ViewPort.clear()`；客户端 `TerminalFrame` 已并入 `TerminalChrome`，`PassiveIoScroll` 已删除（直连 AE2 `Scrollbar`）。

**2026-11-20 追加：HUD 条与协议 42**

- 新增 `HudNoticeS2C(text, Kind{SUCCESS,NEUTRAL,FAILURE})`，把失联 / 恢复这类没有界面的通知从原版动作栏改成模组 HUD 条（内部编号 42，发布重置为 1）。
- 客户端统一入口 `client/screen/hud/HUDRenderer`：深底、1px 边框、左侧状态点；`WandHud` 常驻草稿条复用 `drawBar`，绑杖 / ME 杖 / 定位 / 改名 / 失联共五类提示全部落到这里。
- 约定：**服务端只发无色文本，颜色由 `Kind` 决定**；条内文本自带颜色会与状态点打架，`FactoryMessages` 因此删除。
- `EnergyPick` 在接入时直接探 FE 能力，不再依赖观测 tick；`EnergyRuleRetry` 双保险已移除。


---

## ADR-045 menu 包命名与分包

**状态**：已决定（2026-11-20）

**决定**：

1. `menu` 定位为**会话层 + 终端偏好契约**，不是界面层：Host 拿终端物品做 AE2 `ItemMenuHost`，Menu 只建 36 个玩家槽位，数据仍走 `ViewPort` / 视图表。
2. 分包按终端切分，与客户端目录对称：
   - `menu/node`：`NodeTerminalMenu` / `NodeTerminalHost`
   - `menu/passiveio`：`PassiveIoTerminalMenu` / `PassiveIoTerminalHost`
   - `menu/wand`：`WandDraftMenu` / `WandDraftHost`
   - 根：`TerminalSetting` / `TerminalSortMode` / `PassiveIoViewMode` / `MenuGate`
3. 改名 `FactoryGuiOpening` → `MenuGate`（它做归属门禁，不是「打开」），方法 `isFactoryMenu` / `isWandMenu` → `allowsTerminal` / `allowsWandDraft`。
4. **兼容性**：`Setting` 键字符串（`passive_io_view` / `terminal_sort_mode` / `terminal_sort_direction`）、枚举常量名与 ordinal、MenuType 注册顺序全部不变；协议号不变。旧终端组件与旧存档无需迁移。
5. 迁移分四步提交：偏好类型 → 四个 Host/Menu 改名并移入子包 → MenuGate → 草稿面板；每步 `gradlew build` 通过。

**理由**：

- `Factory` 前缀在 client 侧已去掉，menu 仍满是前缀，命名与职责不一致；
- `Host` / `Menu` 成对出现，按终端分包后与 `client/terminal.node` / `terminal.passiveio` 一一对应；
- `Opening` 这个名字让人以为它负责打开界面，实际只做校验。

**后果**：

- 客户端仍有 53 处引用 `menu` 的三个偏好类型（组件值与线协议 ordinal 的契约共享）。彻底消除 `client → menu` 需要把这三个类型挪到中立 `terminal` 包，属于独立决定，本期不做。


---

## ADR-046 包结构整理：key、registry、network.codec

**状态**：已决定（2026-11-20）

**决定**：

1. `resource`（`AeKeyAdapter`）与 `location`（`BlockKey`）合并为 `key` 包；两者都是「身份键」，`BlockKey` 不需要 Adapter——它本身就是本模组的不可变值对象，没有外部序列化形态要适配。
2. 删除 `event` 包：唯一类 `FactoryWandEvents` 只拦 ME 杖左键挖掘，按产品决定取消该行为，不再需要这个包。
3. `registry` 保留，类名统一模组前缀：`IdleFactoryItems` / `IdleFactoryMenus` / `IdleFactoryCreativeTabs` / `IdleFactoryPayloads`；负载注册属于「注册」，从 `network` 移到这里，`network.NetworkProtocol` 只留协议版本与线路上限，随后再拆成 `NetworkVersion`（版本号）与 `NetworkLimit`（线路上限 + `wireLimit`）。
4. 编解码器集中到 `network.codec`：`AeKeyCodec`、`ViewTableCodec`。
5. 删除全部 `package-info.java`。
6. `config` 保留，只有模组配置可以叫 config；`FactoryConfig`（工厂持久化部分）的命名问题留到 factory 重构时一并处理。

**理由**：两个 Key 包都只有 1 个文件，分开没有信息量；事件包只服务一条已取消的行为；注册与协议行为分开后，`network` 只剩「怎么连、怎么发」，`registry` 负责「注册了什么」。

**后果**：纯改名与搬文件，协议号不变（仍为 1），存档、物品组件、菜单注册顺序均不受影响。

**ADR-046 追加（2026-11-20）**：命名再收敛一轮——`FactoryManager` → `FactoryLibrary`（`Manager` 读作「管理」，与「内存索引」不符；`Library` 是集合后缀）；`FactoryRefs` → `FactoryResolver`（`Resolver` = 解析）；连锁查找从 `factory` 移入 `command.CommandChain`（它只被绑定杖命令使用，BFS 找同 id 机器属于命令的职责）；`NetworkProtocol` 拆成 `NetworkVersion` 与 `NetworkLimit`。


---

## ADR-047 factory 包拆分

**状态**：已决定（2026-11-20）

**决定**：

1. 装配从生命周期里拆出：新增 `FactoryLoader`（读档、建观测通道、绑端口、建运行器、重建占用表），返回 `Loaded` 记录；`FactoryLifecycle` 只持有协作者并转发 tick。
2. 绑定杖链接推送移出工厂层：新增 `network.WandLinkPort`，工厂层不再直接发 `WandLinksS2C`（守住「发包只从 network」）。
3. 草稿提交序号窗口移出工厂层：新增 `command.CommitSeqStore`（防重放，下线清空），生命周期只保留同名委托方法。
4. `FactoryConfig` 并回 `Factory`：写档部分就是 `owner` / `storageAnchor` / `nodes` 三个字段，删掉只做包装的类；`factory.config().nodes()` 改为 `factory.nodes()`。
5. 命名：`FactoryLibrary`（原 Manager，内存索引）、`FactoryResolver`（原 Refs，短 id 解析）、连锁查找迁入 `command.CommandChain`。

**理由**：`FactoryLifecycle` 原来同时承担启停、读档装配、脏标记、存档、链接推送、防重放与节点日志，任何一处改动都要在一个 400 行文件里找位置；拆开后每类的修改理由只剩一个。

**后果**：`FactoryLifecycle` 从 412 行降到 346 行（其余是服务 getter），无协议、存档、行为变化；存档键与节点编码格式不变。
