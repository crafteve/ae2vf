# 发布手册（CurseForge / Modrinth）

填表、发版、换图的唯一入口。文案正文在 [zh_cn.md](zh_cn.md) 与 [en_us.md](en_us.md)，
截图尚未拍摄，两份文案里的图片行都是注释状态。

## 一、CurseForge 建项目

| 字段 | 填什么 |
|---|---|
| Name | `AE2 Void Factory` |
| Summary | `An AE2 addon that binds machines into your ME network with a wand, then moves items and fluids between them — no interfaces, cables or recipe changes.` |
| Class | **Mods** |
| Main category | **Technology** |
| Additional categories | **Storage**（可再加 Utility & QoL） |
| Description | 粘贴 [en_us.md](en_us.md) 正文（Markdown） |
| Avatar | 建议 1:1、边长 ≥256，512×512 最稳；平台会自己裁剪缩放，不是硬门槛 |
| Game Version | 1.21.1 |
| Mod Loader | NeoForge |
| License | MIT |
| Source | <https://github.com/crafteve/ae2vf> |
| Issues | <https://github.com/crafteve/ae2vf/issues> |
| 上传文件时的 Release Type | **Beta**（当前版本 0.1.0-beta） |

依赖关系在上传文件时填：AE2 = Required，Applied Flux / JEI = Optional。

**CurseForge 上搜不到 "AE2"**：它的项目名是全称 `Applied Energistics 2`，slug 是
`applied-energistics-2`，短名 AE2 不在标题里。搜索框里输全称或直接输数字 id：

| 依赖 | CurseForge 名称 | CF 项目 id | CF slug | Modrinth slug | 关系 |
|---|---|---|---|---|---|
| AE2 | Applied Energistics 2 | 223794 | `applied-energistics-2` | `ae2` | Required |
| JEI | Just Enough Items (JEI) | 238222 | `jei` | `jei` | Optional |
| Applied Flux | Applied Flux | 965012 | `applied-flux` | `appflux` | Optional |

两个平台的 slug 不一样，写发布脚本时别混用（`build.gradle` 里已按平台分开）。

## 二、Modrinth 建项目

| 字段 | 填什么 |
|---|---|
| Project type | Mod |
| Slug | `ae2-void-factory` |
| Summary | 与 CurseForge 同一句英文（≤256 字符） |
| Description | 粘贴 [en_us.md](en_us.md) 正文 |
| License | MIT（必填，不选项目不公开） |
| Environment | Client + Server |
| Categories | Technology、Storage |
| Loaders | NeoForge |
| Game versions | 1.21.1 |

上传版本时：版本号 `0.1.0-beta`、渠道 **Beta**、依赖 AE2 = Required，Applied Flux / JEI = Optional。

## 三、本地发布

环境变量放 token，项目 id 用 `-P` 传（不是密钥，也可写进 `gradle.properties`）：

```bash
export CURSEFORGE_TOKEN=...      # console.curseforge.com 申请
export MODRINTH_TOKEN=...        # 可选：Modrinth → Settings → PAT，scope 含 CREATE_VERSION

./gradlew publishCurseForge -Pcurseforge_project_id=<数字项目 id>
./gradlew modrinth -Pmodrinth_project_id=ae2-void-factory   # 暂未使用
```

- 版本号取 `gradle.properties` 的 `mod_version`，渠道由 `-Prelease_channel=beta|release|alpha` 决定（默认 beta）；
- 发布说明自动截取 `CHANGELOG.md` 里当前版本标题下的条目，不用手抄；
- `modrinth` 任务与配置缓存不兼容，Gradle 会为这一条命令自动退回不缓存；
- `-Pmodrinth_debug=true` 只打印将上传的内容，不发请求。

## 四、CI 发布

推 `v` 开头的 tag 触发 [.github/workflows/release.yml](../../.github/workflows/release.yml)：

1. 校验 tag 与 `mod_version` 一致；
2. `build`；
3. **配了哪个平台就发哪个**，没配 token 的平台自动跳过；
4. 把 jar 挂到 GitHub Release。

仓库需要配置（至少一个平台）：

- Secrets：`CURSEFORGE_TOKEN`（必配，当前只在 CurseForge 发布）、`MODRINTH_TOKEN`（可选）；
- Variables：`CURSEFORGE_PROJECT_ID`、`MODRINTH_PROJECT_ID`（对应平台没开就不用配）。

只发 CurseForge 时，`MODRINTH_TOKEN` 留空即可，工作流不会失败。

## 五、换图标（画师交付后两步）

1. 项目头像 → 上传到平台即可（建议 1:1、≥256px，512 最稳），原件放 `docs/publish/icon.png`；
2. jar 内小图标 → 缩到 ≤128px 放 `src/main/resources/ae2vf_logo.png`，再把 `src/main/templates/META-INF/neoforge.mods.toml` 里 `logoFile` 那一行的注释去掉。

两处尺寸要求不同：平台头像会自己裁剪缩放，随便多大都能传；jar 内那张只影响模组列表里的 32px 小图标，大了纯属浪费体积。

## 六、发布前检查

- [ ] `mod_version` = tag = 两个平台填的版本号，渠道都是 Beta；
- [ ] `CHANGELOG.md` 有该版本的条目；
- [ ] jar 内 `META-INF/neoforge.mods.toml` 的 `license` 是 MIT；
- [ ] 依赖只写真实存在的：AE2 必需，Applied Flux / JEI 可选，**没有 Applied Mekanistics**；
- [ ] 文案里没有不存在的功能，截图已替换或删掉注释行。
