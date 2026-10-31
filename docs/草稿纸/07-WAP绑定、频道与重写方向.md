# 07-WAP 绑定、频道与重写方向

> 状态：讨论中
> 最后更新：2026-10-31
> 标记：【用户已确认】/【用户想法·待确认】/【Agent 建议·未确认】/【用户已推翻】；技术判断归入 Agent 建议。
> 目标：确认 Factory 的绑定入口、频道扩展方式，以及是否新分支重写。

## 【用户已确认】

- 用 AE2 原版“无线访问点（WAP）”作为 Factory 的绑定 / 创建入口。
- 终端复用 / 继承 AE2 无线终端（`WirelessTerminalItem`），并在 AE2 终端界面上做修改，不自己另写一套 UI。
- 把终端绑定到 WAP；如果该网络没有 Factory，就创建一个。

## 【用户想法·待确认】

- AE2 没有安全终端了，WAP 取代它的位置。
- 一个 ME 网络最多一个 Factory；多个 Factory 容易出 bug。
- 从头开一条新分支重写模组；可以借鉴现有机制，但必须读懂后自己写，不照搬。

## 【Agent 建议·未确认】

- WAP 是频道 / 功率 / 范围锚点，不是权限系统：任何能拿到 WAP GUI 的玩家都能链接终端；需要自有 ACL 才谈得上安全。
- Factory 绑定 WAP 所在 grid；同 grid 的多个 WAP 可以共享一个 Factory；子网不自动建 Factory。
- 频道：WAP 本身消耗 1 个频道；频道限制 WAP 数量 / 覆盖，不直接限制机器数。Factory 本身不耗频道；要按机器数收频道，需要虚拟节点或自定义带宽，不建议硬套 AE2 频道。
- **虚拟节点可行**：`ManagedGridNode` 可以 `setInWorldNode(false)`，owner 是 Factory 这种普通对象；用 `GridHelper.createConnection(factoryNode, wapNode)` 连到 WAP 的 grid，再 `setFlags(REQUIRE_CHANNEL)` / `setIdlePowerUsage` / `addService(ICraftingProvider.class, ...)`。节点 NBT 由 Factory 存档保存/恢复。
- 如果 Factory 作为虚拟节点 owner 实现 `PatternContainer`，AE2 的样板访问终端可能看到它的虚拟样板；但 `PatternProviderLogic` 仍是方块/Part 绑定的，不能直接复用。
- 终端复用 `IGridLinkableHandler` + `AEComponents.WIRELESS_LINK_TARGET`，注册到 `GridLinkables`；WAP GUI 会把终端 link 到 WAP。`WirelessTerminalItem` 的 `getMenuType` / `getMenuHost` 可以覆盖，挂自定义菜单/界面。
- Factory 仍是自建领域类；样板管理也自建（机器上的虚拟样板列表），但复用 AE2 的样板数据格式 / 解码。
- `IGrid` 是运行时对象，不持久化；Factory 不长期持有，按 WAP / 锚点每次解析或失效重建。
- 一网络一 Factory 要靠创建入口 + 注册表强制：`FactoryRegistry` 以 `IGrid` 为运行时键、以 canonical anchor 为持久键；同一 grid 已有 Factory 就复用，不再建第二个虚拟节点。
- 同 grid 多个 WAP 只是多个接入点，不产生第二个 Factory；grid 分裂时 Factory 跟 canonical anchor，合并时两 Factory 冲突，保留一个、另一个休眠并提示。
- 重写：新分支可以，但先写产品 / ADR，再按垂直切片写；旧代码只做行为参考，保留行为清单，避免把踩过的坑又踩一遍。

## 待确认

- 一个 WAP 一个 Factory，还是同 grid 多 WAP 共享一个 Factory？
- 频道限制是只卡 WAP 数量，还是要卡机器数 / 吞吐？
- WAP 是唯一创建入口吗？还要不要 `/idf factory create`？
- 重写范围：核心 + UI 全重写，还是只重写 domain/UI，IO/观测按行为重写？
