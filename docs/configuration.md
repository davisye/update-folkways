# 配置指南

共享配置在实例 `.folkways-updater/config.json`，没有第二份 NeoForge TOML 覆盖它。单 JAR 模式首次自动创建配置。

## 游戏内配置

在 NeoForge **Mods → Folkways Development Updater → 配置** 打开页面。可选择 Folkways 仓库、渠道、模块、本地开发保护、网络策略、自身更新、生命周期命令，以及轮询间隔、自身渠道和 CN 地域提示。

保存会在配置锁下合并最新文件，仅修改页面展示的字段，不重置签名清单、公钥和重启限额等高级设置。检查按钮先保存，再异步检查与预下载；状态和提示在主菜单可见。保存不等于立即安装，JAR 仍等整个 JVM 退出后由 helper 处理。

没有现有配置时，`localDevelopment` 默认 `false`；不会按 updater 来源、源码构建或 IDE 环境自动改成 `true`。已有配置原值保留。下面是 nightly 新安装示例；初始自身通道随发行构建选择 nightly 或 release：

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
| `localDevelopment` | `true` 暂停 Folkways 和依赖的检查、预下载、安装及恢复写入；仅由这个配置值控制 |
| `commandsEnabled` | 默认 `false`；启用受 OP 4 控制的生命周期命令；状态/检查仍需 OP 4 |
| `selfUpdateEnabled` | 独立控制 updater 自身检查、预下载和退出后替换；不受 `localDevelopment` 影响 |
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

Windows 上若直接建连失败，会只读查询已启用的静态系统 HTTP 代理并额外尝试一次；没有新增代理配置字段，不修改系统或全局 JVM 属性。PAC、SOCKS 和代理认证不在此回退范围；官方 HTTPS、来源白名单、大小和摘要校验保持生效。

来源、两个开关和检查按钮的设置保存后生效；“保存”和“保存并检查”都会立即安排检查，无需重启。已有后台检查时仍可保存，旧轮次结束后立即检查最后一次保存的设置；中间选择合并，旧轮次结果不会覆盖新来源提示。Folkways 操作前重新检查本地开发值，下载期间打开它也会阻止后续 Folkways/依赖安装；自身更新继续遵守独立开关。

## 本地开发与自身更新

本地开发按钮只代表 `config.localDevelopment`。它只暂停 Folkways 及其依赖，不暂停更新器自身。需要保留正在编译的 Folkways 时，主动在页面打开本地开发；需要同时保留自己编译的 updater，再关闭自身更新。

| `localDevelopment` | `selfUpdateEnabled` | Folkways / 依赖 | updater 自身 |
| --- | --- | --- | --- |
| `false` | `true` | 可更新 | 可更新 |
| `true` | `true` | 暂停 | 可更新 |
| `false` | `false` | 可更新 | 暂停 |
| `true` | `false` | 暂停 | 暂停 |

这里的“可更新”仍要求来源、摘要、归属、进程和事务校验通过。开关不绕过安全校验，也不清除未完成事务。Folkways 的手动或本地包仍不会被静默接管。

旧 `-Dfolkways.localDevelopment`、`FOLKWAYS_LOCAL_DEVELOPMENT`、`LOCAL_DEVELOPMENT` 标记和 NeoForge 开发环境不再覆盖配置。旧文件可以留存，无需通过删标记恢复更新；若旧配置保存了 `true`，在页面主动关闭即可。

开启本地开发不会撤销已完成安装；退出 helper 仍须遵守该配置，保留 Folkways 的待安装或恢复证据。自更新可继续检查、预下载、安装和恢复；关闭 `selfUpdateEnabled` 才会暂停自身通道。

## 手动更换 updater

已登记的 updater 仅改文件名、内容摘要与构建身份相同时，可以重新绑定到实际加载路径，保留原高水位和待更新记录。手动换入字节不同的官方新版，必须匹配官方 GitHub 资产摘要和成功 CI 身份，才能恢复自身托管；校验暂不可用时保留文件并提示，不自动打开本地开发，也不阻止 Folkways 检查。

源码或其他本地 updater 的身份同样不改变本地开发值。当前手动包的历史 nightly 资产已清理、无法取得该包的官方证明时，也不应永久停止自身候选检查。允许自身更新时，独立下载验证的官方候选仍须绑定当前实际文件，再由退出 helper 复核后替换；不能借重新托管降低序号、覆盖未知改动或跳过事务恢复。IDE 中若没有已安装 JAR，则没有自身替换目标。

## 默认的官方 GitHub 来源

普通新用户无需填写密钥、单独下载元数据或运行 `adopt`。首次手工安装的 updater JAR 建立自身归属，后续按官方 GitHub API 资产摘要、完整构建身份与成功 CI 校验；GitHub 校验 API 和附件均按网络设置选择直连或镜像；镜像会参与校验数据的信任。

`manifestUrl=""`、`publicKeys={}` 是默认官方 GitHub 来源。空实例可首次安装 Core、Vanilla 和缺失的 LDLib2；已受管文件可后续更新。Folkways 的资产摘要、固定提交、对应成功发布 CI 和选中模块集合必须一致；这里不把上游包称为本项目签名或构建。

已有手动或本地编译 Folkways 包默认保持保护。需要更新时，在“更多设置 → 接管现有并更新”确认本次来源、频道和模块；授权只用于这一轮，后续普通轮询不继承强制接管。只有能明确识别的五种单模块 Folkways JAR 可登记；已有受管文件被修改仍拒绝，手动前置与复合/嵌套包不接管。未选中的手动 Folkways 模块需先勾选或由用户移出。完整官方包校验通过后才保存归属与队列；当前 JAR 不变，完全退出后先备份再替换。本地开发开启时暂停此操作，自更新仍独立。LDLib2 以官方 Modrinth 项目/版本和文件摘要为依据；已有前置不应被未知下载覆盖。三个可选扩展无需预装前置就能勾选；检查时会按实际 JAR 声明和固定官方目录补齐缺失的 Create、Modular Golems、Kaleidoscope Cookery 及间接依赖。兼容前置只读复用，冲突保留原文件并提示；取消扩展不自动卸载已安装前置。详见 [前置安装规则](dependencies.md)。

切换 IzakyL 与 davisye 的 nightly 后，结果会显示本轮检查的仓库和频道。两个仓库分别记录版本序号，fork 的 CI 编号较小也能正常切换；同源仍保留防回退与摘要保护。切换先预下载完整模块集合，完全退出整个游戏后才替换，当前运行中的 Folkways 不会立即变化。若目标源校验或依赖检查失败，保留现有安装；其它源的旧队列不会误装。

当前两个源只有 nightly 实际发行；其他渠道若无符合条件的包，保留当前文件。Folkways 检查暂时不可用时不吞掉 updater 自身的检查结果。

## 可选的 Folkways 签名通道

公共自动签名发布仍延期。运营方实际提供可信清单与公钥后，可填写非空 `manifestUrl` 选择签名通道，并明确接管已安装的对应模块。源、MC、渠道、构建、序号及每个 JAR 都要校验；同一下载站提供的新公钥不能自行成为信任根。

签名通道不是默认 GitHub 首装或 updater 自身更新的前置条件。不要为绕过签名错误而关闭校验，或将未经验证的 URL 当作可信来源。

## 冲突与恢复

单 JAR 模式在 JVM 登记、helper 安装与下次加载之间设置门禁。启动与安装重叠可能要求重新启动；它不能承诺在第一次 Mod 扫描前拿到最新版本。

出现未完成事务、存活进程或不匹配归属时，不要强删日志、lease、状态文件或备份。按错误提示确认所有相关进程退出，再处理恢复。

GitHub 校验 API 与 JAR 共用 `networkPolicy`：`mirror-first` 先尝试 `https://github.davisye.cn/_github/api.github.com/...`，失败后直连；`direct-first` 反向；`region` 按地域提示决定顺序。元数据仅接受固定仓库、HTTPS、限量 JSON，API 重定向不跟随，仓库/CI/摘要/序号约束继续校验。经镜像取得的数据依赖该镜像正确转发，不提供独立数字签名保证；Modrinth API 仍使用审核过的官方地址。

## 更新提醒

自动弹窗和服务器聊天仅提示新准备好的待安装更新；“已是最新”、本地保护和暂不可用等检查结果仍可在配置页或状态命令查看。同一更新按验证后的构建与内容身份去重，Folkways 与更新器自身独立记录，提醒不依赖翻译文字或另一个通道的状态。记录在实例 `.folkways-updater/notified-updates.json`，原子保存近期 256 条摘要；未展示、已撤销的检查和空服务器不会消耗提醒。存储不可写时保留本次 JVM 内去重；新构建、来源或所选更新内容改变时可再次提醒。
