# 可选扩展与前置安装

三个扩展按钮不再要求前置已经加载。空实例也可以先勾选 Create、Modular Golems、Cookery，再点“保存并检查”。更新器将选中 Folkways 模块、缺失主前置和必要的间接依赖作为一批验证、预下载，在整个游戏 JVM 退出后一起安装；下次启动加载。

默认仍只选择 Core、Vanilla；不会为了未勾选的扩展下载大型前置。Minecraft 1.21.1、Java 21、NeoForge 21.1.233 或更高版本需要事先安装。

## 固定官方目录

当前自动安装限定于下列已核验的官方 Modrinth 版本，不在运行时搜索同名项目，也不自动选择“最新”前置。每个文件的项目、版本、下载 URL、大小、SHA-512、MC/加载器标签与真实 JAR 身份均需匹配；未来上游要求超出这些固定版本时，会保留现有文件并提示不兼容，目录应通过新的 updater 发行更新。

| 前置 | JAR 声明版本 | 何时需要 | 来源 |
| --- | --- | --- | --- |
| LDLib2 | `2.2.37` | 基础 Core 所需 | [官方版本](https://api.modrinth.com/v2/version/bpcbGDy4) / [源码](https://github.com/Low-Drag-MC/LDLib-Architectury) |
| Create | `6.0.10` | 勾选 Create 扩展 | [官方版本](https://api.modrinth.com/v2/version/UjX6dr61) / [源码](https://github.com/Creators-of-Create/Create) |
| Modular Golems | `3.1.44` | 勾选 Modular Golems 扩展 | [官方版本](https://api.modrinth.com/v2/version/lcKGfUcI) / [源码](https://github.com/Minecraft-LightLand/ModularGolems) |
| L2Library | `3.0.9` | Modular Golems 的必需依赖 | [官方版本](https://api.modrinth.com/v2/version/7GS7JBkX) / [源码](https://github.com/Minecraft-LightLand/L2Library) |
| Kaleidoscope Cookery | `1.6.0-neoforge+mc1.21.1` | 勾选 Cookery 扩展 | [官方版本](https://api.modrinth.com/v2/version/g8w0b2ly) / [源码](https://github.com/KaleidoscopeMods/KaleidoscopeCookery) |

L2Library 的官方 1.21.1 NeoForge 发行在平台标记为 **beta**；当前采用固定的 3.0.9，JAR 声明版本为数字版本。其余表中候选的平台标签为 release。这个标签独立于 Folkways 自己的 release/rc/beta/alpha/nightly 渠道。

Create 和 Folkways Core 自带 Ponder、Flywheel，Modular Golems/L2Library 的若干库也由 JarJar 提供。更新器解析这些内嵌身份和必需依赖，不额外放入重复的外置 JAR；完整所选集合仍需满足所有版本范围。可选、建议或资源包不会因此自动安装。

## 现有文件与切换扩展

- 已有兼容前置只读复用，不变成 updater 的受管文件；预下载与安装时都会重查其摘要。
- 已有不兼容版本、同 Mod 多份文件、用户改动或未知依赖会阻止这批安装，并保留原文件。界面允许选择不代表跳过检查。
- 取消扩展只调整对应 Folkways 模块，不自动卸载已经安装的前置，以免影响其他 Mod。
- 这批前置与 Folkways 使用同一待安装计划、文件归属、有限备份、异常恢复与游戏进程门禁。下载不完整或配置切换不会应用半套文件。
- 本地开发开启时，Folkways 及这些前置都暂停；updater 自身仍由独立的自更新开关控制。

## 来源与分发

依赖文件直接从官方 Modrinth CDN 获取，官方版本 API 用于核对固定证据。[Modrinth 版本接口](https://docs.modrinth.com/api/operations/getversion) 提供项目、MC/加载器、文件哈希与依赖字段；运行时不会将 API 中任意新 URL 当作安装授权。GitHub Folkways 附件继续按网络设置在直连与 github.davisye.cn 之间故障切换。 Windows 已启用静态系统 HTTP 代理时，建连失败可回退到这条既有路径；不要求额外 JVM 参数。仅读取 ProxyEnable/ProxyServer，不修改系统或全局 Java 代理设置，不支持带凭据、PAC 或 SOCKS 的自动转换。TLS、HTTP 状态、正文大小和摘要等校验失败不会借此绕过。

第三方 JAR 不打入 updater，不作为本仓库发行附件，也不上传到 OpenList。Create、Cookery、Modular Golems、L2Library、LDLib2 的许可证和素材条款各自独立；本项目只引用并下载官方文件，重新托管或重新打包需要单独核对许可。

## 验证范围

真实文件的官方摘要和声明依赖已经逐项审计。安装算法的闭包测试、真实网络安装、NeoForge 加载与公开 CI 分别记录在 [验证说明](verification.md)；静态元数据闭合本身不等于完整游戏运行或存档兼容已通过。
