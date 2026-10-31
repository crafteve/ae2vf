# 02-AE2 原生 UI 与架构方向

> 状态：讨论中
> 最后更新：2026-10-31
> 标记：【用户已确认】/【用户想法·待确认】/【Agent 建议·未确认】/【用户已推翻】；技术判断归入 Agent 建议。
> 目标：判断 UI 是否迁到 AE2 原生控件；核心是否保留。

## 【用户已确认】

- “UI 独立、AE2 可选”的假设不再成立：AE2 是硬依赖，约 90% 功能需要 AE2。
- 独立 UI 方向选错了；需要 AE2 级手感。
- 需要 UI 管理机器、机器的被动 IO 和主动样板。
- Factory 绑定 ME 网络，不按玩家。

## 【用户想法·待确认】

- 重写 / 新分支 / 迁移范围尚未决定。
- 是否接受“必须带终端物品才能打开 UI”。

## 【Agent 建议·未确认】

- 不重写工厂核心；只换 UI 外壳。
- 新分支做 AE2 原生 UI 最小 spike；主线保持稳定。
- 保留视觉 / 布局 / 贴图；替换交互层。
- 若 `AEBaseMenu` host 太硬，退到 AE2 `WidgetContainer` / 控件思路。

## 【用户已推翻】

- “UI 独立、可脱离 AE2”。

## 【Agent 建议·未确认】（技术判断）

- 当前 `FactoryScreen extends AbstractContainerScreen`，`FactoryMenu extends AbstractContainerMenu`。
- AE2 附属通常用 `AEBaseScreen` + `AEBaseMenu` + `WidgetContainer`。
- `AEBaseMenu` 需要合法 host（`BlockEntity` / `IPart` / `ItemMenuHost`）；当前 `/idf gui` 是无 host 菜单。
- 代码量：`client/screen` 约 10.9k 行，`factory` 约 13.5k 行，`integration` 约 0.8k 行，总约 29k 行。

## 待确认

- `AEBaseMenu` host 方案；样板访问终端是否要桥；UI 迁移范围。
