# 归档区

这里放**已经废弃**的文档与设计稿。保留原因只有一个：它们是决策留痕——将来有人问
「为什么不做画布」「为什么没有终端物品」，答案在这批文件里，而不是在某个人的记忆里。

**不要让这里的任何内容指导实现。** 现行文档见 [../README.md](../README.md)。

| 文件 | 是什么 | 为什么归档 | 取代它的是 |
|---|---|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | v0.2 架构草案，含 `core/graph`、`core/machine`、`warehouse/` 等分包 | 这些包已经不存在；图模型（Node/Edge/Port）在 factory 重构中被删 | 仓库根 README 的「架构」一节 |
| [GUI.md](GUI.md) | 画布 + 物品节点 + 按面分槽的 IO 面板设计 | 机器之间不连线（所有流动经过仓库），IO 规则是扁平的「资源 + 可选面」 | [../ui/P0.md](../ui/P0.md)、[../ui/mockup-v2.png](../ui/mockup-v2.png) |
| [ROADMAP.md](ROADMAP.md) | M0–M6 分期与分包计划 | 分期是围绕 `core/graph` 排的，那套模型没了 | 仓库根 README 的「当前状态」与「未实现」 |
| [PLAN-M0-M1.md](PLAN-M0-M1.md) | M0/M1 详细计划（终端物品、装备奇点、读 ME 网络） | 这条链在 P0 整条删除，界面改由 `/idf gui` 打开 | [../ui/P0.md](../ui/P0.md) |
| [gui-mockup.html](gui-mockup.html) · [graph](gui-mockup-graph.png) · [graph-v2](gui-mockup-graph-v2.png) · [table](gui-mockup-table.png) · [table-v2](gui-mockup-table-v2.png) | 第一版界面原型（画布 + 表格两种视图） | 同上：UI 方向已变，画布不再做 | [../ui/mockup-v1.png](../ui/mockup-v1.png)、[../ui/mockup-v2.png](../ui/mockup-v2.png) |
| [terminal-preview.png](terminal-preview.png) | 终端物品的贴图预览 | 终端物品已删除，没有物品入口了 | 无（`/idf gui`） |

## 归档内容里仍然成立的部分

归档 ≠ 全错。以下几条当时想清楚了，现在仍然有效：

**来自 [GUI.md](GUI.md)**

- **这是编辑器，不是容器界面**：工作台的核心是配置与观测，不是「把东西放进格子」。
- **物品有三种角色不能混**：真实槽位 / 幽灵过滤槽 / 浏览列表。判断「要不要真 Slot」的标准至今照用。
- **颜色全部走 `GuiTheme`**：界面代码里不出现颜色字面量。现在更进一步——静态外观走贴图，
  `GuiTheme` 只管文字色与状态色。

**来自 [ARCHITECTURE.md](ARCHITECTURE.md)**

- **定位**：虚拟 pattern provider + 全局仓库，机器本身不动。
- **设计原则 P1–P4**：配方由玩家提供、只虚拟化搬运、能力可选、默认安全。
- **资源类型边界**：只认 FE，不碰 EU/J；化学品单列。

**已彻底作废、不要再引用的**

- 所有分包树（`core/graph`、`core/machine`、`machine/`、`warehouse/`）
- Node / Edge / Line / PortRef / 带宽配额 / 自动推断连线
- 画布、物品节点、按面分槽的 IO 面板、终端物品、装备奇点
- M0–M6 分期
