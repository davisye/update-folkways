# 安装与退出后更新

新安装只需一个 NeoForge Mod JAR。需要 Java 21 与 NeoForge 1.21.1；先关闭实例，备份重要存档和配置。

## 安装

1. 从本仓库 [Releases](https://github.com/davisye/update-folkways/releases) 下载 `folkways-updater-neoforge-1.21.1-<version>.jar`。
2. 放入实例 `mods/`，确认没有另一份 updater JAR。
3. 用原来的启动器照常启动。无需单独下载 standalone Bootstrap、seed 脚本、元数据或公钥。

用于退出后安装的独立 helper 已内嵌在 Mod 中，自动释放到 `.folkways-updater/`。不要手工把 helper 放进 mods。

国内下载可在官方 Release 确认完整 JAR 名称后，使用 `https://github.davisye.cn/_github/github.com/davisye/update-folkways/releases/download/nightly/<完整JAR文件名>`。首次下载属于人工信任步骤；运行时不能用镜像自己的摘要代替官方 API 与 CI 身份验证。

本项目不代装 Minecraft、NeoForge、Folkways 或扩展的第三方前置 Mod。

## 第一次运行会发生什么

第一次加载你手工安装的现有版本，登记它的文件归属，并检查更新。没有现有配置的官方发行版默认 `localDevelopment=false`；已存在的配置保持原值。本地源码构建、NeoForge 开发环境及显式开发保护继续禁用自动更新。

下载通过验证后，游戏提示更新已准备。后台 helper 等待**整个游戏 JVM**退出，而不是只等待退出世界或集成服务器停止。你可以正常继续游戏，更新不会原地覆盖已加载文件。

JVM 退出后，helper 重查其他游戏进程、开发保护、文件归属、待安装内容与事务状态，再安装。下一次照常启动即可加载新版本。

## 不必为了自身更新配置 Folkways 签名

updater 自身固定使用本仓库的官方发布与成功 CI 构建。`selfUpdateEnabled=true` 时可检查自身，`selfUpdateChannel` 可选择 `nightly` 或 `release`。

Folkways 的 `manifestUrl` 和 `publicKeys` 可以继续留空。这不影响 updater 自身检查，但表示 Folkways 自动更新未配置。公共 Folkways 自动签名发布仍延期；不要为了启用自身更新填写陌生密钥或未经验证的清单地址。

## 退出、重开与失败

- **刚退出又立即重开：** 若与 helper 安装重叠，进程/事务门禁可能中止这次启动。按提示等待安装完成后再开，避免混代文件进入世界。
- **下载或官方 API 暂不可用：** 保留现有 updater。已加载版本可继续运行，后续再检查。
- **系统关机或 helper 被终止：** 可能保留待更新或恢复状态；不能保证本次已安装，按下次提示处理。
- **开发保护开启或受管 JAR 被手工改动：** 游戏仍可加载手工安装的完整 updater，但自动替换被保护，旧归属不静默重置。未完成事务或启动冲突仍会拦截加载。

这个流程不控制启动器的扫描前时机，也不保证每次启动都已经是最新。中止启动的门禁是冲突保护，不应通过强删文件绕过。

## 本地开发

保留/设置 `localDevelopment=true`，或创建 `.folkways-updater/LOCAL_DEVELOPMENT`。环境变量 `FOLKWAYS_LOCAL_DEVELOPMENT=true` 与 JVM 参数 `-Dfolkways.localDevelopment=true` 也可启用保护。

本地构建身份和 NeoForge 开发环境继续受保护，即使从前的配置是 managed 模式。修改配置后，helper 在安装前仍需重新检查保护。字段说明见 [配置指南](configuration.md)。

IDE、JVM `-D` 与环境变量保护会持久留下 `LOCAL_DEVELOPMENT` 标记，避免父进程退出后 helper 丢失开发设置。恢复 managed 需要关闭对应 flag、配置为 `false`，并在实例关闭后主动移除该标记；不能只改一个开关。

## 专服停止与重启

启用 `commandsEnabled=true` 后，OP 4 可用 `/shutdown [seconds]` 或 `/restart [seconds]`，默认 10 秒，范围 0–300 秒；发出停止请求前可用对应的 `cancel` 取消。

drop-in helper 能精确恢复原 Java 参数和工作目录时，专服重启走正常保存、父 JVM 退出、helper 启动新整 JVM。helper、命令参数或重启预算不可用时，`/restart` 在停服前拒绝并保留运行；默认一次会话最多 5 次、冷却 10 秒。已有同名命令不会被覆盖。

该流程已在单 updater 的隔离 NeoForge 专服实测，边界见 [验证说明](verification.md)。客户端完整重启暂不支持；重启内置服务器不能让客户端重新加载 Mod。

## 恢复与缓存

独立 helper 使用进程身份、状态、恢复日志和有限备份管理安装；检测到存活 JVM、未完成事务或不匹配文件时停止。逐文件原子替换不等于多个 JAR 的文件系统原子事务，也不保证断电耐久性。

不要手工删除进程 lease、事务日志、归属记录或待更新目录来强行继续。先关闭相关实例并检查错误与备份。正常成功后清理待安装和临时运行文件；启动阶段未知的残留会保留供检查。

## 旧 Bootstrap/seed 方式迁移

早期版本需要手动安装 standalone Bootstrap、`launch.ps1` / `launch.sh` 和伴随 Mod，由外部 supervisor 创建游戏 JVM。旧的 `6.1 → 7.1` 验收属于这种方式，不是单 JAR 模式的证明。

迁移时先关闭原监督进程与游戏，备份配置和存档，移除 mods 中的旧 updater JAR，再放入支持 drop-in 的新版单 JAR，并恢复使用普通启动器入口。保留原 `.folkways-updater/config.json`；已有 `localDevelopment=true` 不会自动关闭。不要把旧 seed 和新 helper 同时作为日常启动流程。

遇到旧事务未完成的提示时先按旧方式完成恢复，不通过删除日志跳过。单 JAR 模式无需用户重新下载和手动管理旧的 bin、seed 或发行元数据。
