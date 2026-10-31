# 文档索引

## 现行（照着这些做）

| 文档 | 内容 | 状态 |
|---|---|---|
| [DECISIONS.md](DECISIONS.md) | ADR 决策记录。**唯一活的设计约束来源**——架构为什么长这样，看这里 | 持续维护 |
| [ECOSYSTEM.md](ECOSYSTEM.md) | AE2 生态调研：存储后端、FE 支持、化学品边界 | 现状仍成立 |
| [ui/P0.md](ui/P0.md) | 界面 P0：目标、尺寸、贴图管线、怎么试、P1 清单 | 现行 |
| [ui/P1-EDIT.md](ui/P1-EDIT.md) | 界面 P1：产线菜单、机器拖拽、IO 卡片编辑、网络包与验收清单 | 现行 |
| [ui/mockup-v2.png](ui/mockup-v2.png) | 界面定稿设计稿（v2），[HTML 源](ui/mockup-v2.html) 可直接改 | 现行 |
| [ui/mockup-v1.png](ui/mockup-v1.png) | 第一版设计稿，仅作 v1→v2 的对照 | 历史 |
| [../README.md](../README.md) | 当前状态、指令、包结构、核心约束 | 现行 |

## 草稿纸

[草稿纸](草稿纸/) 是 Agent 的讨论笔记，**不要求阅读，也不构成实现约束**；只记核心结论、技术约束与待定项。命名 `NN-需求概述.md`；定稿结论同步到 [DECISIONS.md](DECISIONS.md)。

## 已归档

[**archive/**](archive/README.md) —— 画布时代的全部文档与设计稿（架构草案、GUI 讨论、
M0–M6 路线图、第一版界面原型、终端物品预览）。**只作决策留痕，不要照着实现。**

一句话记住哪些没了：**画布、物品节点、按面分槽的 IO 面板、终端物品、装备奇点、
`core/graph` 图模型**——这些全部作废，理由见归档区索引。

## 怎么判断一份文档是不是过时

三条快速判据：

1. 它提到 `core/graph`、`FactoryNode`、`PortRef`、`ItemNode`、`MachineIo` → 过时
2. 它提到「终端物品」「奇点已装备」「画布坐标」 → 过时
3. 它把界面描述成「库存 + 机器列表 + 画布」 → 过时

现行界面的描述只有一处：[ui/P0.md](ui/P0.md)。
