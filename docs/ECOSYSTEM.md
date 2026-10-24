# AE2 生态调研

> 调研时间：2026-10-24
> 目的：确认 AE2 本体、化学品支持、FE 支持的实际实现，为接入层与存储后端做参考。

## 结论先行

| 需求 | 项目 | 地址 |
|---|---|---|
| AE2 本体 | Applied Energistics 2 | https://github.com/AppliedEnergistics/Applied-Energistics-2 |
| 化学品支持 | Applied-Mekanistics | https://github.com/AppliedEnergistics/Applied-Mekanistics |
| FE 存储 | Applied Flux | https://modrinth.com/mod/appflux |

## 版本支持（1.21.1 / NeoForge）

| 项目 | 1.21.1 NeoForge | 备注 |
|---|---|---|
| Applied-Mekanistics | 1.6.3 | 官方 Mekanism 支持，提供化学品的 ME 存储 |
| Applied Flux | 1.21-2.1.5-neoforge | FE 存储与传输 |

## 细节

### AE2 本体

- 仓库：https://github.com/AppliedEnergistics/Applied-Energistics-2
- 1.21.1 对应分支需要进仓库确认（仓库里同时有更晚的版本线）

### Applied-Mekanistics（化学品）

- 仓库：https://github.com/AppliedEnergistics/Applied-Mekanistics
- 描述：The official Mekanism support addon for AE2
- 实现了 `ChemicalP2PTunnelPart` 等，把 Mekanism 的化学品接入 AE2 的存储与 P2P
- 对本项目的意义：`IWarehouse` 的化学品账户可以参考它的做法

### Applied Flux（FE）

- Modrinth：https://modrinth.com/mod/appflux
- 描述：Store and transfer energy with ME network
- 支持 1.21.1 NeoForge
- 源码位置：Modrinth 标注为 https://github.com/GlodBlock/ExtendedAE，但该仓库主描述是「36 槽 pattern provider」，Applied Flux 的独立源码位置未确认（可能在其 `xmod/module` 下）
- 对本项目的意义：FE 作为 AE2 的一种存储资源是可行的，能量账户可以借鉴

## 待确认

1. AE2 1.21.1 的存储接口形态（`IStorageService` / `MEStorage` / `IItemHandler` 桥）
2. Applied Flux 的独立源码位置
3. Applied-Mekanistics 的化学品接口细节
4. AE2 的 pattern provider 与 `IItemHandler` 的对接方式（1.21.1）
