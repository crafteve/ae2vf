# 0.1.0-beta
[2026-11-21 14:13] 升级 AE2 至 19.2.20、JEI 至 19.71.0.475，测试环境加回 ExtendedAE 系列与 AE2WTLib
[2026-11-21 14:03] 加入四个数据层 GameTest（节点编解码、节点库、倍率、IO 规则），16 例全过
[2026-11-21 13:40] 拼音模组改用 Modrinth 版本 id 解析，避免 maven 按版本号拿到 Fabric 包
[2026-11-21 13:35] 测试环境临时移除 ExtendedAE 与 ExtendedAE-Plus（ExtendedAE-Plus 要求 JEI >= 19.64，当前 19.21）
[2026-11-21 13:32] 删除 JEI 的 FE 假配料（FeIngredient 一族），FE 改走 AE2 通用包裹堆栈
[2026-11-21 12:42] 测试环境加入 JustEnoughCharacters、MouseTweaks、Extended AE、ExtendedAE-Plus（仅 localRuntime）
[2026-11-21 12:14] 移除 ADR：DECISIONS.md 归档到 docs/archive/，现行约束统一到 AGENTS.md
[2026-11-21 11:54] CHANGELOG 改为按版本分块，删除「更新日志」总标题
[2026-11-21 11:45] 新增 CHANGELOG.md，并在 AGENTS.md §5.7 记录更新日志的写法
[2026-11-21 11:45] AGENTS.md §1.1 的项目简介改为「用绑定杖绑定，在终端里配置，无线连接 AE2」
[2026-11-21 11:45] AGENTS.md 记录远程与推送约定：远程在 GitHub，大任务收口后统一推送
[2026-11-21 11:45] AGENTS.md 修正当前分支描述为 1.21.1neoforge
[2026-11-21 11:45] 清理 idlefactory 旧名残留：创造栏常量改为 CREATIVE_TAB，中英文案改为「虚空工厂」/「AE2 Void Factory」
[2026-11-21 11:45] 三个贴图生成脚本的输出目录从 assets/idlefactory 改回 assets/ae2vf
[2026-11-21 11:45] AGENTS.md 修正 registry 前缀为 VoidFactory，并删除已不存在的日志判据
