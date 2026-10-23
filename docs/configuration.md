# 配置指南

共享配置在实例 `.folkways-updater/config.json`，没有第二份 NeoForge TOML 覆盖它。单 JAR 模式首次自动创建配置。

## 游戏内配置

在 NeoForge **Mods → Folkways Development Updater → 配置** 打开页面。可选择 Folkways 仓库、渠道、模块、本地开发保护、网络策略、自身更新、生命周期命令，以及轮询间隔、自身渠道和 CN 地域提示。

保存会在配置锁下合并最新文件，仅修改页面展示的字段，不重置签名清单、公钥和重启限额等高级设置。检查按钮先保存，再异步检查与预下载；状态和提示在主菜单可见。保存不等于立即安装，JAR 仍等整个 JVM 退出后由 helper 处理。

官方发行 JAR 且没有现有配置时，初始化 `localDevelopment=false`，启用受管更新。本地构建、NeoForge 开发环境保持保护；已有配置原值保留。下面是官方 nightly 新安装示例；初始自身通道随发行构建选择 nightly 或 release：

```json
{
  "localDevelopment": false,
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
| `localDevelopment` | `true` 阻止自动检查、预下载和替换；显式设置始终优先 |
| `commandsEnabled` | 默认 `false`；启用受 OP 4 控制的生命周期命令；状态/检查仍需 OP 4 |
| `selfUpdateEnabled` | updater 自身更新开关；所有开发保护仍优先 |
| `selfUpdateChannel` | 本仓库的 `nightly` 或 `release`；release 不包含 prerelease，尚无正式版本时保留现有安装 |
| `repository` | Folkways 源：`IzakyL/folkways` 或 `davisye/folkways` |
| `minecraft` | 当前仅 `1.21.1` |
| `channel` | Folkways 的 `release`、`rc`、`beta`、`alpha`、`nightly` |
| `modules` | 必含 `core`、`vanilla`；可加 `create`、`modulargolems`、`cookery`，不允许重复 |
| `manifestUrl` | 空值使用官方 GitHub Release；非空选择独立的可信签名清单 HTTPS 来源 |
| `publicKeys` | Folkways `keyId → Base64 X509 DER Ed25519 公钥`，由可信发布者预先提供 |
| `networkPolicy` | `mirror-first`、`direct-first`、`region` |
| `regionHint` | `region` 策略中填 `CN` 可优先镜像；不自动查 IP 地域 |
| `pollSeconds` | 轮询间隔 60–86400 秒，默认 900 |
| `restartLimit` / `restartCooldownSeconds` | 受监督专服重启的次数与间隔；不表示任意客户端已有重启适配 |

来源、本地保护和检查按钮的设置保存后生效；自动轮询间隔最迟在下一次检查后更新，立即检查无需重启。开发保护与关键安装配置在操作前重新检查，下载期间开启保护也会阻止安装。

## 本地开发保护

任一条件均阻止自动更新：配置 `localDevelopment=true`、环境变量 `FOLKWAYS_LOCAL_DEVELOPMENT=true`、JVM 参数 `-Dfolkways.localDevelopment=true`、标记 `.folkways-updater/LOCAL_DEVELOPMENT`、非生产 NeoForge 环境，或缺少官方 CI 身份的本地 updater 构建。

这些保护也约束退出后的独立 helper。本地构建不会因为旧配置为 `false` 就被官方发行覆盖。人工重新编译或改写过的受管 JAR 与归属记录不同，也不能被静默接管。

已有归属后手工替换 updater（包括不同文件名的官方新版）可以继续加载，但进入 manual/local 保护，保留旧基线，自动替换不擅自重新开启。不要通过强删事务日志来重置这一状态。

NeoForge 非生产环境、`-Dfolkways.localDevelopment=true` 或 `FOLKWAYS_LOCAL_DEVELOPMENT=true` 会留下 `LOCAL_DEVELOPMENT` 保护标记，供独立 helper 重新读取。关闭这些 flag 后，标记仍保持保护。确实要恢复 managed 时，先关闭实例，关闭对应 flag、将配置的 `localDevelopment` 设为 `false`，再主动移除该开发标记；源码构建和 manual/local 归属保护仍不会因此自动解除。

开发保护阻止之后的自动操作，不自动回滚已经完成的安装。建议修改保护时先关闭实例，保留已有配置和状态。

## 默认的官方 GitHub 来源

普通新用户无需填写密钥、单独下载元数据或运行 `adopt`。首次手工安装的 updater JAR 建立自身归属，后续按官方 GitHub API 资产摘要、完整构建身份与成功 CI 校验；镜像只传输字节。

`manifestUrl=""`、`publicKeys={}` 是默认官方 GitHub 来源。空实例可首次安装 Core、Vanilla 和缺失的 LDLib2；已受管文件可后续更新。Folkways 的资产摘要、固定提交、对应成功发布 CI 和选中模块集合必须一致；这里不把上游包称为本项目签名或构建。

已有手动或本地编译 Folkways 包不会被静默接管，也不通过配置开关重置其归属。LDLib2 以官方 Modrinth 项目/版本和文件摘要为依据；已有前置不应被未知下载覆盖。可选扩展要求已安装兼容的 Create、Modular Golems 或 Kaleidoscope Cookery。

当前两个源只有 nightly 实际发行；其他渠道若无符合条件的包，保留当前文件。Folkways 检查暂时不可用时不吞掉 updater 自身的检查结果。

## 可选的 Folkways 签名通道

公共自动签名发布仍延期。运营方实际提供可信清单与公钥后，可填写非空 `manifestUrl` 选择签名通道，并明确接管已安装的对应模块。源、MC、渠道、构建、序号及每个 JAR 都要校验；同一下载站提供的新公钥不能自行成为信任根。

签名通道不是默认 GitHub 首装或 updater 自身更新的前置条件。不要为绕过签名错误而关闭校验，或将未经验证的 URL 当作可信来源。

## 冲突与恢复

单 JAR 模式在 JVM 登记、helper 安装与下次加载之间设置门禁。启动与安装重叠可能要求重新启动；它不能承诺在第一次 Mod 扫描前拿到最新版本。

出现未完成事务、存活进程或不匹配归属时，不要强删日志、lease、状态文件或备份。按错误提示确认所有相关进程退出，再处理恢复。
