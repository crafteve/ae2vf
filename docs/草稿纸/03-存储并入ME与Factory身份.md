# 03-存储并入 ME 与 Factory 身份

> 状态：讨论中
> 最后更新：2026-10-31
> 标记：【用户已确认】/【用户想法·待确认】/【Agent 建议·未确认】/【用户已推翻】；技术判断归入 Agent 建议。
> 目标：是否删除容器后端、Factory 是否按 ME 网络。

## 【用户已确认】

- Factory 对象保留，绑定 ME 网络，不按玩家。
- 机器绑定杖把机器绑到该网络的 Factory。

## 【用户想法·待确认】

- 删除容器后端；容器改为 ME 的外部存储，并入 ME 存储。
- 一个网络一个 Factory（方向；多个锚点、split/merge 规则未定）。
- “一个 ME 网络”里的“网络”是否指主网 / 管理域；子网算不算。

## 【Agent 建议·未确认】

- 存储 ME-only：`Storage` 收成 `MEStorage` 薄包装；去掉 `StorageMode` / `ContainerStorage` 后端。
- 容器走 AE2 storage bus（最系统）或全局 `IStorageProvider`（保留无方块）。
- Factory 按“管理域 / 主网锚点”而不是按 `IGrid`；子网不自动建 Factory；机器可以在子网上，仓库 / CPU 仍用主网。
- 首次绑定 ME 方块即创建 / 找到 Factory；不设默认绑定，也不需要单独 create。
- AE2 19.2.10 已移除安全系统，不能借用安全终端；需要自有 ACL / 成员表（可选团队模组适配）。
- `IGrid` 没有持久 id；存档要锚点。
- `IStorageService.addGlobalStorageProvider` / `IStorageProvider.mountInventories` 可把容器挂进 ME。
- storage bus 与全局挂载同时引用同一容器会双计数；FE 容器经 storage bus 可能不支持。
- 先 ME-only 存储，再决定身份细节。

## 【用户已推翻】

- 一人一个 Factory（改为绑定 ME 网络）。

## 待确认

- 容器路线；FE 容器；split/merge；权限；工具绑定流程；旧档迁移。
