# Changelog

本文件记录用户可见的变化。未发布内容不代表已在公开下载或生产签名通道中可用。

## [Unreleased]

### Changed

- 主安装流程改为一个 NeoForge Mod JAR，内嵌 helper 自动释放；不再要求用户手放 standalone、seed、元数据或密钥。
- 自身更新改为运行时检查/预下载、整个 JVM 退出后安装、下次启动加载，不承诺首次扫描前最新。
- 官方发行且没有现有配置的新实例默认 managed；已有配置、源码构建、NeoForge dev 环境和显式开发保护保留。
- 旧 Bootstrap/seed 方式及 6.1→7.1 验收作为历史兼容方式记录，不代替 drop-in 实测。

### Verification pending

- 两个公开单 JAR 版本的网络升级、快速重开竞态与真实安装中断恢复仍需补充。
- Folkways 公共自动签名继续延期，未配置可信清单时不自动替换其 JAR。

### Verified locally

- 单 updater 的真实 NeoForge 专服完成正常存档、整 JVM 重启、原世界重开、限额拒绝保活与正常关闭；使用合成测试构建，未测试完整 Folkways 包、真实玩家或客户端。

## [0.1.0-rc.1] - 2026-10-23

### Added

- 独立 Java 21 Bootstrap 与 NeoForge 1.21.1 伴随 Mod。
- Folkways 两源、五渠道、同构建模块选择、游戏内周期提示与预下载。
- 默认本地开发保护、显式 JAR 接管、签名与摘要校验、受管安装恢复及有界缓存。
- GitHub 与允许镜像的故障切换，支持本地地域提示。
- OP 4 开发命令、正常停止、受监督专服重启及子进程门禁。
- 逐 JAR 离线签名发布、不可变版本归档、latest 指针和本地目录发布恢复。
- Windows/Linux CI 与官方更新器发布身份，固定 seed 的自身更新交接。

### Fixed

- 快速退出的子 JVM 不再因启动时间已不可读而误留启动门禁。
- Windows 8.3 与根目录别名可正常接管；受管目录内 symlink/junction 仍拒绝。
- GitHub 发布中断后通过授权分页发现同提交草稿，避免重复创建。

### Known limitations

- Folkways 公共自动签名通道与生产写入适配器尚未上线。
- 外链归档不保证上游删除后二进制仍存续。
- 完整客户端 `/restart`、任意启动器适配及断电耐久性尚未完成。
- seed 协议 v1 需手动安装，脚本不自动替换。
