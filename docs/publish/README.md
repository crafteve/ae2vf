# 发布手册（CurseForge）

填表、发版、换图的唯一入口。项目页正文在 [zh_cn.md](zh_cn.md) 与 [en_us.md](en_us.md)，
写作顺序的依据见 [description-guide.md](description-guide.md)。

## 一、项目设置（已建好，改动时对照）

| 字段 | 值 |
|---|---|
| Name | `AE2 Void Factory` |
| Summary | `An AE2 addon that binds machines into your ME network with a wand, then moves items and fluids between them — no interfaces, cables or recipe changes.` |
| Class | Mods |
| Main category | Technology |
| Additional categories | Storage（可选再加 Utility & QoL） |
| License | MIT |
| Game Version | 1.21.1 |
| Mod Loader | NeoForge |
| Source | <https://github.com/crafteve/ae2vf> |
| Issues | <https://github.com/crafteve/ae2vf/issues> |

上传文件时：Release Type 选 **Beta**，Environment 选 **Client + Server**。

依赖关系（CurseForge 上搜全称或直接输数字 id，搜 "AE2" 搜不到）：

| 依赖 | CF 名称 | CF 项目 id | CF slug | 关系 |
|---|---|---|---|---|
| AE2 | Applied Energistics 2 | 223794 | `applied-energistics-2` | Required |
| JEI | Just Enough Items (JEI) | 238222 | `jei` | Optional |
| Applied Flux | Applied Flux | 965012 | `applied-flux` | Optional |

## 二、本地发布

```bash
export CURSEFORGE_TOKEN=...      # console.curseforge.com 申请
./gradlew publishCurseForge -Pcurseforge_project_id=<数字项目 id>
```

- 版本号取 `gradle.properties` 的 `mod_version`，渠道由 `-Prelease_channel=beta|release|alpha` 决定（默认 beta）；
- 发布说明自动截取 `CHANGELOG.md` 里当前版本标题下的条目，不用手抄；
- 缺 token 或项目 id 时任务会直接报错，不会误发。

## 三、CI 发布

推 `v` 开头的 tag 触发 [.github/workflows/release.yml](../../.github/workflows/release.yml)：

1. 校验 tag 与 `mod_version` 一致；
2. `build`；
3. **创建 GitHub Release 并挂上 jar**（不需要任何凭据）；
4. 配了 `CURSEFORGE_TOKEN` + `CURSEFORGE_PROJECT_ID` 时，再把 jar 传到 CurseForge。

所以没配 token 也能拿到自动打包的 Release，只是不会同步到 CurseForge。

## 四、换图标（两步）

1. 512×512 项目头像 → 直接传到平台（建议 1:1、≥256px）；
2. jar 内小图标 → 覆盖 `src/main/resources/ae2vf_logo.png`（≤256px），`mods.toml` 里的 `logoFile` 已指向它。

## 五、发布前检查

- [ ] `mod_version` = tag = 平台上的版本号，渠道都是 Beta；
- [ ] `CHANGELOG.md` 有该版本的条目；
- [ ] jar 内 `META-INF/neoforge.mods.toml` 的 `license` 是 MIT、`logoFile` 指向存在的图标；
- [ ] 依赖只写真实存在的：AE2 必需，Applied Flux / JEI 可选，**没有 Applied Mekanistics**；
- [ ] 文案里的截图链接可访问（raw 返回 200）。
