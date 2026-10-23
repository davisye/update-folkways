# 配置指南

Bootstrap 和伴随 Mod 共用实例目录中的 `.folkways-updater/config.json`，没有第二份 NeoForge TOML 配置覆盖它。`init` 创建安全默认值，`status` 可用于查看状态。

```json
{
  "localDevelopment": true,
  "commandsEnabled": false,
  "selfUpdateEnabled": true,
  "selfUpdateChannel": "nightly",
  "repository": "IzakyL/folkways",
  "minecraft": "1.21.1",
  "channel": "nightly",
  "modules": ["core", "vanilla"],
  "manifestUrl": "",
  "publicKeys": {},
  "networkPolicy": "mirror-first",
  "regionHint": "",
  "pollSeconds": 900,
  "restartLimit": 5,
  "restartCooldownSeconds": 10
}
```

## 字段

| 字段 | 作用与范围 |
| --- | --- |
| `localDevelopment` | 默认 `true`；阻止 Folkways 和更新器自身检查、预下载与替换 |
| `commandsEnabled` | 默认 `false`；启用受 OP 4 控制的停止/重启命令；状态/检查仍只需 OP 4 |
| `selfUpdateEnabled` | 允许更新器自身检查；开发保护仍优先 |
| `selfUpdateChannel` | 本仓库更新器的 `nightly` 或 `release`，独立于 Folkways 通道 |
| `repository` | `IzakyL/folkways` 或 `davisye/folkways` |
| `minecraft` | 当前仅 `1.21.1` |
| `channel` | Folkways 的 `release`、`rc`、`beta`、`alpha` 或 `nightly` |
| `modules` | 必含 `core`、`vanilla`；可加 `create`、`modulargolems`、`cookery`；不允许重复 |
| `manifestUrl` | Folkways 签名清单的 HTTPS 地址；空值表示尚未配置该更新通道 |
| `publicKeys` | `keyId → Base64 X509 DER Ed25519 公钥`；由可信发布者线下提供 |
| `networkPolicy` | `mirror-first`、`direct-first`、`region` |
| `regionHint` | `region` 策略下本地填写 `CN` 可优先镜像；不查询 IP 地域 |
| `pollSeconds` | 游戏内检查间隔，60–86400 秒，默认 900 |
| `restartLimit` | 一次 supervisor 会话最多重启 0–50 次，默认 5 |
| `restartCooldownSeconds` | 重启间隔 1–600 秒，默认 10 |

游戏内轮询启用与间隔变化在下次启动生效。保护开关在每次更新操作前重新检查；发布源或模块等配置在操作期间变化会阻止安装。

## 只启用更新器自身更新

首次通过 seed 接管官方 updater 两 JAR 后，设置 `localDevelopment=false`、`selfUpdateEnabled=true`，选择 `selfUpdateChannel` 即可。Folkways 的 `manifestUrl`、`publicKeys` 可以继续留空，不需要先接管 Folkways 文件，也不会自动替换它们。两条通道的配置与信任来源独立。

如果仍在本地开发并希望保护全部产物，保持 `localDevelopment=true`，它也会关闭 updater 自身更新。

## 从本地构建切到受管更新

以下步骤专指启用 **Folkways Mod 更新**，不是更新器自身更新的前置条件。

1. 关闭实例，备份存档与配置。
2. 安装同一构建的 Core、Vanilla 及所选扩展，删除重复版本；更新器伴随 Mod 是独立文件。
3. 配置准确的源、通道、模块、签名清单地址和可信公钥。
4. 明确要启用受管更新后，再将 `localDevelopment` 设为 `false`，同时检查其他开发保护开关。
5. 执行 `adopt --game-dir <instance>` 记录现有 Folkways 文件名和 SHA256。此操作不会下载或替换文件。
6. 用 `check --game-dir <instance>` 验证通道，并通过外部 Bootstrap 启动测试实例。

已存在归属记录时，`adopt` 拒绝重置，防止静默覆盖本地更改。不要通过删除状态文件绕过文件归属或恢复错误；先检查错误、实例运行状态和备份。切换模块或源需要在关闭的测试实例中重新核对安装集与归属记录。

## 开发保护

任一条件即可保护实例：配置为 `true`、环境变量 `FOLKWAYS_LOCAL_DEVELOPMENT=true`、Bootstrap JVM 的 `-Dfolkways.localDevelopment=true`、`.folkways-updater/LOCAL_DEVELOPMENT` 文件，或伴随 Mod 运行在非生产 NeoForge 环境。

seed 会启动多个 Java 进程；使用环境变量或标记文件更容易让这些进程保持相同的开发保护。若使用 JVM 参数，应确保传给 Bootstrap 的 JVM，而不只是游戏 JVM。

即使保护开关关闭，人工重新编译或修改过的受管 JAR 与归属记录不一致时仍拒绝替换。开发保护不会回滚先前已经完成的安装。

## 网络与信任

建议清单签入官方 canonical GitHub URL，由 `networkPolicy` 控制下载顺序。GitHub 代理采用 `/_github/<hostname>/<path>`；不是把完整 URL 拼在域名后。显式列出的镜像也必须返回与签名摘要相同的字节。

Folkways 公钥必须预先可信，不能从同一个镜像自动获取并接受新公钥。清单验签、范围、递增序号和 JAR 摘要检查通过才会进入待更新集合。更新器自身的官方 GitHub/CI 信任链与此配置的公钥独立，详见 [安装与自更新](installation.md)。

## 故障恢复

启动失败时先读错误并确认实例真正退出。不要在仍存活的游戏 JVM 旁边启动第二个 updater，或删除 `child.json`、事务日志、备份来强行继续。

启动中断留下不确定的 `launching` 门禁时，需要人工确认所有相关进程已退出后再按错误提示处理。`pending.previous` 是替换待更新缓存时的恢复集合；若新旧集合同时存在但新集合损坏，程序会保留旧集合并停止，而不会先删除恢复副本。
