# 安装与更新器自更新

使用独立测试实例，先关闭游戏并备份世界/配置。需要 Java 21。Windows 使用 PowerShell；Linux seed 需要 Bash 和 `flock`。seed v1 只支持前台 `java` / `java.exe` 命令，不接受分离进程的 GUI 启动器、`javaw` 或 shell 包装器。

本项目不安装 Minecraft、NeoForge、Folkways 或扩展所需的第三方前置 Mod。

## 首次安装

1. 从本仓库同一次 [Release](https://github.com/davisye/update-folkways/releases) 下载 Bootstrap、伴随 Mod、对应系统 seed、checksums 和元数据，核对 `checksums.sha256`。
2. 将 `folkways-updater-neoforge-1.21.1-<version>.jar` 放进实例 `mods/`；避免同时保留两份 updater 伴随 Mod。
3. 把 `launch.ps1` 或 `launch.sh` 放在你管理启动配置的位置，保留下载的 Bootstrap 作为首次安装来源。
4. 使用下面的 seed 命令。它会复制 Bootstrap 到 `<instance>/.folkways-updater/bin/folkways-bootstrap.jar`，并显式记录这两个现有 updater JAR 的归属。

Bootstrap 和伴随 Mod 必须来自同一版本；本地源码构建会被识别为开发构建并受到自身更新保护。首次安装是手动信任步骤，seed 不会自行挑选陌生 JAR 或接受新下载站作为信任根。

国内首次下载也可使用 nightly 的同附件镜像：[launch.ps1](https://github.davisye.cn/_github/github.com/davisye/update-folkways/releases/download/nightly/launch.ps1)、[launch.sh](https://github.davisye.cn/_github/github.com/davisye/update-folkways/releases/download/nightly/launch.sh)、[update-metadata.json](https://github.davisye.cn/_github/github.com/davisye/update-folkways/releases/download/nightly/update-metadata.json)。两个 JAR 的完整文件名应从同次官方 Release/元数据确认，再套用 `https://github.davisye.cn/_github/github.com/davisye/update-folkways/releases/download/nightly/<附件名>`。首次仍由用户确认可信来源；运行时以官方 API 摘要及 CI 身份作校验，镜像仅传输字节。

### Windows 专服示例

示例目录是相对当前终端的 `instance/` 和 `downloads/`。将 `<version>` 替换成下载版本，并按实际 NeoForge 安装调整参数文件路径：

```powershell
$gameJvm = @(
  'java',
  '@user_jvm_args.txt',
  '@libraries/net/neoforged/neoforge/21.1.233/win_args.txt',
  'nogui'
)
.\launch.ps1 -GameDirectory .\instance `
  -BootstrapJar .\downloads\folkways-bootstrap-<version>-standalone.jar `
  -CompanionJar .\instance\mods\folkways-updater-neoforge-1.21.1-<version>.jar `
  -Dedicated -GameCommand $gameJvm
```

`-Java` 可指定用于 Bootstrap/helper 的 Java 21 可执行文件；`$gameJvm[0]` 是游戏 JVM，两者需分别正确配置。脚本参数传递为数组，不要拼成一个 shell 命令字符串。

### Linux 专服示例

```bash
bash ./launch.sh \
  --game-dir ./instance \
  --bootstrap-jar ./downloads/folkways-bootstrap-<version>-standalone.jar \
  --companion-jar ./instance/mods/folkways-updater-neoforge-1.21.1-<version>.jar \
  --dedicated -- java @user_jvm_args.txt \
  @libraries/net/neoforged/neoforge/21.1.233/unix_args.txt nogui
```

`--java` 可指定 helper 的 Java；默认使用 `JAVA_HOME/bin/java`，未设置时使用 PATH 中的 `java`。游戏启动参数中的相对路径由实例目录解析。

首次安装成功后，同一 seed 启动命令可省略 `BootstrapJar` / `CompanionJar`（Linux 为对应长选项）；它们不是每次重置归属的开关。seed 和两个 JAR 有独立用途，不要将 Bootstrap 放到 mods 目录。

## 初始配置保持开发保护

首次创建的 `.folkways-updater/config.json` 中 `localDevelopment=true`。此时 seed 仍可启动实例，但不会自动检查、预下载或替换 Folkways / updater。

只启用**更新器自身更新**时，可在 seed 首次接管后设置 `localDevelopment=false`、`selfUpdateEnabled=true`，保留 Folkways 的 `manifestUrl=""` 和 `publicKeys={}`。这会启用官方 updater 通道，Folkways 自动更新仍未配置，也不需要先执行 Folkways `adopt`。

以后要同时启用 **Folkways 更新**，再按 [配置指南](configuration.md) 配置其可信清单、公钥、源和模块，并接管已安装 Folkways 文件。Folkways 首次接管与 updater 首次接管是两个操作：

```powershell
java -jar .\instance\.folkways-updater\bin\folkways-bootstrap.jar status --game-dir .\instance
java -jar .\instance\.folkways-updater\bin\folkways-bootstrap.jar adopt --game-dir .\instance
```

`adopt` 记录 Folkways 所选模块；seed 的 `CompanionJar` / `self-adopt` 记录 updater 自身两 JAR，不会替你接管 Folkways。

## 自更新如何工作

配置 `selfUpdateEnabled=true`，`selfUpdateChannel` 选择本仓库的 `nightly` 或 `release`。它独立于 Folkways 的五渠道；所有本地开发保护仍优先。

1. 直连固定仓库的官方 GitHub HTTPS API，读取公开 release、资产 SHA256/大小与 Actions run；不带凭据，不使用镜像授权信任，也不跟随 API 重定向。
2. 下载并校验 `update-metadata.json`，绑定版本、完整提交、两个 JAR、递增 sequence 以及官方 `ci.yml` 的成功 push 构建。清单文件和 JAR 可以经允许镜像传输，但必须匹配官方 API 提供的摘要。
3. 游戏运行期间只预下载到 `self-pending/`。人工修改的受管文件、开发构建、旧序号或错构建均阻止替换。
4. 下次 seed 启动时，从固定 bin 复制独立 runner，先运行 `self-prepare`。helper 在 session/child/whole-JVM lease 门禁下恢复或安装两个 updater JAR，然后退出。
5. seed 等 helper 退出，再从新 bin 复制新的 runner，启动 Bootstrap 和游戏 JVM。避免覆盖正在运行的 Bootstrap 或游戏内 companion。

可信 API 不可达或官方构建尚未完成时，保留现有 updater 继续启动；公开 PR 产物不能触发安装。CI 发布 job 尚在运行时，run 还不是 `completed/success`，短暂拒绝是预期行为。

自更新替换的是已接管的原文件路径，伴随 Mod 的旧文件名可以保留、内部版本会更新。不要因为文件名还带旧版本就额外放入第二份 JAR。

## 固定 seed 与旧入口

seed 协议 v1 首次手工安装，脚本本身不自动替换。新 seed 协议需要用户审阅并手动迁移。

旧式直接调用 `java -jar <bootstrap.jar> launch ...` 仍可监督游戏和处理 Folkways 更新；自身更新仅检查/预下载并提示改用 seed。它不会覆盖当前正在使用的 Bootstrap。没有显式 updater 归属时，先完成 seed 首次安装。

诊断操作包括 `status`、`self-check` 和由 seed 调用的 `self-prepare`。不要通过删除 `self-state.json`、恢复日志或进程门禁来强行重设归属；先关闭所有相关进程并检查错误及备份。

## 客户端接入边界

客户端需要启动器提供有效的账号、会话、classpath 和游戏参数，再把真实前台 Java 命令交给 seed。不要将账号会话写入公开仓库或日志。

本项目尚未提供通用启动器适配，也不支持完整客户端 `/restart`。启动一个 GUI 启动器后立即退出的包装脚本无法让 supervisor 可靠判断游戏是否结束，不属于受支持接入方式。

## 恢复与缓存

Folkways 与 updater 有独立 pending、状态和事务日志。两 JAR 自更新采用逐文件原子替换与恢复备份，仍不是跨文件原子事务。发生未完成事务、仍有游戏 JVM，或路径/归属异常时会停止安装或启动门禁，不能用强删文件代替确认。

runner 使用唯一目录和 PID/启动时间 lease。只清理确认已退出的运行副本；启动阶段未知的目录会保留供人工检查，不能承诺任何异常下都零残留。正常下载和事务成功后清理临时候选与旧恢复集合，缓存不会按每次轮询无限累积。
