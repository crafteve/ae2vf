# AE2 Void Factory（1.7.10 / GTNH）

AE2 附属：把 AE2 网络与机器之间的资源 IO 收进「工厂方块」——不摆接口、不铺线缆。

- 被动 IO：玩家给机器配输入输出规则，工厂按规则在 AE2 网络与机器之间搬运。
- 主动样板：工厂充当虚拟样板供应器，向合成 CPU 广告样板，并把产物收回网络。

构建：`.\gradlew.bat build`（JDK 25，GTNH MDK / GTNHGradle）。

开发运行：`.\gradlew.bat runClient` / `runServer`；首次 `runServer` 需在 `run/server/eula.txt` 写入 `eula=true`，游戏进程用的 JDK 8 与开发依赖由 GTNHGradle 自动下载。
