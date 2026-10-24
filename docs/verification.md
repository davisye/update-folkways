# 验证说明

自动化测试、真实进程验证与完整 Minecraft 运行分别记录。当前源码或某个环境通过，不代表任意启动器、未来 nightly 或全部模组组合已验收。

2026-10-24 起，本地开发仅由配置值控制、仅暂停 Folkways/依赖；自身更新独立。下文 2026-10-23 的验收与机器记录保持原始事实，适用此前的旧 policy，包括 IDE 标记生效、本地模式阻止自身替换等行为，不能据此推断新开关语义。[验收索引](acceptance/README.md) 说明适用范围。

## 可选扩展与自动前置验收

当前实现允许空实例直接选择三项扩展，按固定官方 Modrinth 目录准备必要依赖。[前置规则](dependencies.md) 记录固定版本和复用/冲突边界。

- 真实隔离 NeoForge 客户端中未安装三个主前置，仍能点击全选、取消后重选并保存；独立本地/自更新开关和隐藏字段保留继续通过。[界面记录](acceptance/2026-10-24-optional-prerequisites-client.json)。
- 默认 Java 21 无额外代理参数，使用正式 HTTPS API/Transport 从空 mods 预下载五个 Folkways 模块和五个库；每个摘要匹配。独立 JVM 离线安装后十份归属/摘要一致，pending/journal 清理，正式网络复查返回已是当前版本。该离线安装调用 Updater API，不能称为此场景实际退出 helper 安装。
- 另一个空白隔离实例只放当前候选 updater JAR，使用前项真实网络验证后复制的完整队列；实际内嵌 helper 在父 JVM 退出后安装十份文件，摘要和配置保持，下次探针接受已安装版本并只保留一代 helper。这项是离线真实辅助进程验收，不是第二次网络下载或 Minecraft 运行。
- 同一套文件在真实 NeoForge 21.1.233 专服加载，达到 Done、保存新世界并正常退出，十个已装文件和本地候选 updater 摘要未变。实测选中 L2ItemSelector 3.1.6、L2Tabs 3.0.6+7，与 JarJar 区间推导一致。[安装与加载记录](acceptance/2026-10-24-optional-prerequisites-install.json)。
- 该专服仍有上游非致命资源错误：未安装可选 Patchouli 时的 MG 指南 loot，以及 Cookery/Quark 兼容项的 tag 引用；没有据此自动安装未声明为必需的可选 Mod，也不把启动成功等同于全部玩法无误。未测试完整模组包客户端、真实玩家和全部存档玩法。
- 本地完整构建 Java 134 项，133 通过、1 项本机卷能力跳过；将系统代理查询总期限从 2 秒调至 5 秒后，17 项相关测试和最终打包再次通过。独立审阅另验证真实十 JAR/66 条必需依赖、固定 pin 篡改拒绝及默认 Java 完整下载。公开发行仍以最终提交的两平台 CI 和发布包身份为准。

## 本次开关契约验收

本次针对独立开关、旧 IDE/JVM/env/marker 不覆盖配置、updater 同摘要改名、官方手动升级的来源核验、实际文件快照、自身校验暂不可用不阻断 Folkways，以及完整事务/进程门禁做了定向验证。下列本地实验保留原始范围；随后 [28.1 主分支 CI](https://github.com/davisye/update-folkways/actions/runs/39991401019) 已通过公开门禁，Windows Java 105 项通过、Linux 103 项通过/2 项平台专用跳过，两端 Python 64 项通过，公开单 JAR 的国内镜像摘要一致。

当前已在真实隔离 NeoForge 21.1.233 / Java 21 Windows 客户端验证配置页：旧开发标记不覆盖关闭状态，主动打开按钮后配置为 true；local=true 仍能按检查按钮并完成独立自身检查，Folkways 检查暂停。因为该场景为 IDE 运行，自身状态明确显示“没有已安装的更新器 JAR 可替换”，不能把这次通过当作已完成自身下载或安装。高级字段保存保留、基础/高级两页布局、返回原页面通过，截图已检查。[当前界面机器记录](acceptance/2026-10-24-independent-client-configuration.json) 保留事实与边界。

本轮本地完整构建及客户端验收源码编译成功；该全量轮次 Java 104 项，103 通过、1 项因本机测试卷不提供 8.3 别名跳过。随后新增的 local=true 自身离线恢复用例单独运行通过：恢复原 JAR、保留已接受的更高序号/摘要、清理自身 journal，Folkways 本地文件与队列哨兵不变。该轮源码共有 105 项，随后公开 CI 结果见上文；当时本地 Python 发布测试 59 项通过，补充时间边界回归后公开 CI 为 64 项。

真实独立 helper 使用当前候选字节码与内嵌 payload、合成 CI 身份 `0.1.0-ci.900.1` 完成退出后自身安装；当 `localDevelopment=true` 且旧 marker 存在时，Folkways 文件和 pending 摘要保持，下一次探针清理旧 helper。[实际 helper 机器记录](acceptance/2026-10-24-independent-self-helper.json) 标明这是预先验证队列的离线 fixture，未进行公开发行或真实网络更新，也未启动 Minecraft。

另将已有官方 20.1 JAR、归属和配置复制到隔离目录，验证同摘要改名可重新绑定、关闭状态不被旧 marker 覆盖；主动 local=true 后，正式 Java HTTP 通过官方 API 完成自身检查并返回已是当前 20.1。[手动安装机器记录](acceptance/2026-10-24-manual-official-self-check.json) 不包含原实例路径。此场景未改实际用户实例，也没有启动 Minecraft/helper 或替换 JAR，不能替代不同字节版本的网络升级证明。

## 当前单 JAR 模式

单 JAR 模式改变了安装与生命周期边界：普通启动器启动现有版本，Mod 预下载，独立 helper 等整个 JVM 退出后安装。它不继承旧 Bootstrap 扫描前更新的保证。

已完成公开单 JAR 的 Windows 网络升级，以及下述本地 NeoForge 生产专服生命周期实测。以下仍是完整验收标准，不能全部视为已通过：

1. 只下载一个公开发行的 companion JAR 放入空白测试实例，无 standalone/scripts/metadata/keys；首次自动初始化、释放 helper，正常检查与预下载。
2. 新实例配置默认 local=false，既有值不覆盖；只有 config.localDevelopment 暂停 Folkways/依赖，独立 helper 同样重查；selfUpdateEnabled 分别控制自身更新，身份和旧环境标记不自动开关。
3. helper 只在精确父 JVM 退出且无其他游戏 lease 后安装，原 companion 路径和归属保留，两个实际版本的官方摘要与构建身份一致。
4. 人为重叠“旧 JVM 退出 / helper 每个替换阶段 / 新 JVM 启动及较晚构造”，确保不让混代集合进入世界；冲突启动可以被拒绝并提示重开。
5. 覆盖坏摘要、失败 CI、旧序号、人工改写、路径链接、并发 helper、半下载与安装中断。保留旧可用文件或恢复状态，缓存有界。
6. `/shutdown` 的权限、倒计时、取消和正常存档仍有效；drop-in 专服 `/restart` 单独验证握手、异常退出和重启限额，完整客户端重启不在当前范围。

实际通过结果应写明版本、提交、系统、预期与观察，不使用旧方式的测试数量替代。

### 公开单 JAR 自身更新

Windows / Java 21 的隔离 Java 进程以官方 `0.1.0-nightly.14.1` 自动建立归属，实际从官方 API 校验、预下载 `14.2`，等精确父进程退出后由内嵌 helper 安装。安装字节匹配下一版官方资产摘要，原 Mod 文件名和配置字节保留，一套备份匹配原 JAR；pending 与事务清理，下一次启动接受新身份并清理已退出的 helper。

两版均来自提交 `137136547dda463478c3f293a8f7bfde8ab9879d` 的 [run 39968500131](https://github.com/davisye/update-folkways/actions/runs/39968500131/attempts/2)，分别为 attempt 1 / 2、序号 14001 / 14002；这是两个公开发行身份的更新，不是两个不同源码提交。attempt 2 的 Windows Java 80/80，Linux 78 通过、2 项平台专用跳过；两端 Python 55 通过，实际内嵌 helper 烟测通过。

两个发布的镜像下载字节分别匹配官方摘要；更新运行配置为 mirror-first，但该次内部下载路由未单独埋点，不能称为运行时路由证明。此网络场景没有启动 Minecraft；实际 NeoForge 存档与生命周期另见下节。脱敏 [机器记录](acceptance/2026-10-23-dropin-public-upgrade.json) 保存精确身份、摘要与边界，nightly 旧附件可按策略清理。

### 默认 Folkways 首装与客户端配置

本轮新增默认官方 GitHub 来源、空集合首装、缺失 LDLib2 安装与游戏内配置页。定向核心测试覆盖空集合归属、已有手动/本地包保护、错误摘要/CI、缺库/旧库、切源/签名通道、开发保护、重复文件、首装恢复与存活游戏门禁；依赖解析另覆盖合法空白/引号表名和预发布版本下限。

两个源均用真实 Core、Vanilla、LDLib2 字节与已获取的官方 release/run 快照完成隔离 `checkAndStage → offline apply → already current`。安装后摘要匹配对应官方资产；原本没有 Folkways，最终为三 JAR 基础集合。此 harness 注入缓存内容，commit API 响应由目标 SHA 生成，**没有走 Java 正式 HTTP Transport，也没有启动 Minecraft**。[机器记录](acceptance/2026-10-23-folkways-first-install.json) 明确保留这些边界。

2026-10-23 旧 policy 轮次的本地 Windows / Java 21 完整构建记录 97 项 Java 测试：96 通过、1 项因测试卷没有 8.3 根路径别名跳过；Python 发布测试 55 通过。两平台 CI 还编译客户端验收源码，正式 JAR 检查确认它没有被打包。

2026-10-23 旧 policy 的真实隔离 NeoForge 21.1.233 客户端已打开实际注册的配置工厂：来源与本地开发按钮可点，保存写入共享配置，打开草稿后外部修改的重启字段保持，当时 IDE 开发标记仍生效；缺少前置的三个可选扩展保持可见禁用。基础/高级两页真实 framebuffer 截图已检查，控件均在屏幕内，返回原页面和正常退出通过。[界面机器记录](acceptance/2026-10-23-client-configuration.json) 保存结果，不代表当前 IDE 标记仍可覆盖配置。此场景不执行客户端内网络更新。

另已使用正式 Java HTTP Transport 对全新隔离目录完成真实网络首装：官方 API、所选上游固定提交/成功 CI、实际附件与 LDLib2 均未注入测试数据。预下载 JVM 退出后，由另一 JVM 离线安装三份文件，再从官方 API 复查已是最新；摘要与归属一致，pending/journal 清理。网络配置为 mirror-first，内部路由未单独埋点；该网络场景不启动 Minecraft 或 drop-in helper。[真实网络机器记录](acceptance/2026-10-23-folkways-network-first-install.json) 与下面的 Minecraft 加载、界面结果分别记录。

真实首装互通得到的上游 Core、Vanilla、LDLib2，加本地开发候选 updater，在独立 NeoForge 21.1.233 生产专服实际加载成功，达到 Done 后正常保存和退出，产生 level.dat，四份 JAR 摘要保持不变。[专服机器记录](acceptance/2026-10-23-first-install-dedicated.json) 记录结果；网络已显式关闭，此场景验证加载/保存，不等同于从 Mod 轮询到退出 helper 的完整链路。

### 本地专服重启进程回归

Windows 的 7 项重启测试已通过，包含实际父 JVM/helper/下一代 Java 进程，以及 Windows 参数解析：保留工作目录、空格、中文、空参数、字面引号、反斜线、10K 长参数，支持通过 PATH 启动的 `java`。未完成停止凭证、错误 token、未完成事务、禁用命令和预算耗尽均不重启。

这组测试证明 Java 进程级交接，Minecraft 的实际停止与保存另按下一节验证，不能由参数测试代替。

### 真实 NeoForge 专服生命周期

Windows / Java 21 / NeoForge 21.1.233 的隔离实例只安装一个 updater JAR，使用合成 CI 形状的测试构建，**不是公开发行身份验证**。实际生产加载通过 ModList、class resource、物理 JAR 身份门禁，helper 自动释放并 ready。

通过 OP 4 管理接口发出 `/restart 0` 后，日志确认玩家/世界/区块正常保存，原 JVM 以 0 退出；helper 启动新整 JVM，原世界重新加载，并建立下一代 helper。`restartLimit=1` 时第二次重启在停服前拒绝，状态命令仍成功；之后 `/shutdown 0` 正常保存并退出。两个游戏 JVM 及 helper 最终终止，`level.dat` 和旧备份均有内容，`localDevelopment=true` 时 updater 摘要不变、无遗留事务日志。

脱敏 [机器记录](acceptance/2026-10-23-dropin-lifecycle.json) 只保留事实和边界。该测试没有完整 Folkways 包、真实玩家或 Minecraft 客户端，也没有验证两个公开单 JAR 版本之间的网络更新或断电恢复。

其中 local=true 时 updater 摘要保持不变是旧 policy 下的观察，不是当前本地开发开关能暂停自身更新的保证。

## 已有核心验证

已有自动化覆盖签名/摘要、来源高水位、模块混包、选择下载、归属与本地改动保护、逐文件事务、中断恢复、pending/previous、链接路径、真实子 JVM 和发布幂等恢复等行为。[CI 文档](ci.md) 与 [工作流](../.github/workflows/ci.yml) 是当前执行命令的依据。

另外已用两个源仓库的十个真实 Folkways JAR 核对 GitHub digest，并实际验证代理完整下载与 `bytes=0-127`；用五 JAR 输入完成逐 JAR 签名互操作及两/五模块选择。临时 NeoForge 专服做过正常停止与外部 supervisor 重启；这些不等于完整 Folkways 包或新 drop-in 重启已验证。

OpenList 访客目录和跳转读回证明外链分发路径可用，不证明自动签名或持久二进制归档。raw 文本 Range 的压缩表示问题仍有边界。

## 历史：外部 Bootstrap/seed 验收

2026-10-23 的 [run 39953990440](https://github.com/davisye/update-folkways/actions/runs/39953990440) 对应提交 `218b9f6fb741b103c847b78a9516035f03257f3d`：Windows 55 Java 通过、零跳过；Linux 53 通过、2 项 Windows 专用测试跳过；两端 43 Python 通过，并执行实际 seed 首装烟测。六附件下载字节与官方 API 摘要一致。

随后 [run 39954859064](https://github.com/davisye/update-folkways/actions/runs/39954859064) 发布 7.1，在 Windows 隔离实例以 6.1 原 seed 完成 `6.1 → 7.1`。两个 JAR 摘要及嵌入构建身份匹配新发布，旧 companion 路径保留，pending/journal/runtime/child 清理，备份保留一套。首次检查暂不可用时保留旧文件并启动；后续预下载和安装成功，未对暂不可用原因下结论。

这项实验使用外部 seed 和独立前台 Java，没有启动 Minecraft 或真实世界；不是新 drop-in 模式的端到端证明。[历史机器记录](acceptance/2026-10-23-self-update.json) 保留精确身份与边界，nightly 历史附件可按策略清理。

## 后续目标环境验收

- 单 JAR 模式的 Linux 真实网络升级、快速重开竞态与中断恢复；Windows 两个公开自身发行的网络升级已完成，但没有启动 Minecraft。
- 完整 Folkways 模组包、所选扩展及目标存档的升级后加载与重新打开。
- 常用客户端启动器中的退出行为与错误提示；完整客户端重启仍需适配。
- 真实 OpenList 后端的任务完成、写后读回、缓存、并发与最小权限账号。
- 断电、磁盘故障与多文件耐久性，不能由普通故障注入替代。

验收日志只提交脱敏的必要证据，不包含本机路径、SSH/生产配置、令牌、启动器会话、世界或玩家数据。

## OpenList 主机侧 nightly 同步

2026-10-24，两平台 CI 通过的 [PR #9](https://github.com/davisye/update-folkways/pull/9) 已部署到 sz：专用账号、systemd 凭据及每次完成后十分钟调度生效。真实 dry-run 和首次 execute 均通过；重复 execute 两源均返回 unchanged，OpenList modified 与目录内容保持不变，无恢复 journal。仅同步两个 nightly 外链，未上传 JAR；Range 探针不等于全文件哈希验证，也不保证上游替换窗口零 404。[脱敏部署记录](acceptance/2026-10-24-openlist-sync.json) 保存精确代码摘要、提交和范围。

## GitHub API 镜像、切源与一次性接管

2026-10-24 的 Windows / Java 21 完整候选构建通过：178 项 Java 测试，177 通过、1 项因测试卷没有 8.3 根路径别名跳过。新增测试验证 API 双路故障切换、绑定配置快照的接管授权、检查合并与旧请求撤销、原包备份，以及未知 JAR 内嵌 Folkways 的拒绝。独立复审无剩余 P1/P2。

使用正式 Java Transport 并主动阻断 `api.github.com` 直连，更新器及两源 Folkways 的真实 Release JSON 均经 `github.davisye.cn/_github/api.github.com/` 成功取得；此项证明元数据镜像路由，完整包校验另由核心测试覆盖。真实隔离 NeoForge 21.1.233 客户端打开配置工厂，基础、高级和接管确认页布局检查通过；本地模式禁用接管，确认页列出源/频道/模块，取消不保存也不排队。该 UI 场景没有执行网络安装或验证实际用户存档升级。[脱敏候选验收记录](acceptance/2026-10-24-client-mirror-takeover.json) 保留实际范围。

## 同一更新只提醒一次

2026-10-24，提醒改为各通道的已验证待安装更新身份，跨重启记录近期 256 项。完整 Windows / Java 21 候选构建 198 项 Java 测试：197 通过、1 项既有 8.3 路径环境跳过；独立复审 29 项定向测试全过，覆盖首次与缓存命中、翻译变化、两源/两通道、历史文件异常及展示/取消并发。隔离 NeoForge 21.1.233 配置界面回归通过；提醒去重由核心/队列夹具验证，未对真实玩家实例反复弹窗做在线验收。[脱敏记录](acceptance/2026-10-24-update-reminders.json) 保存范围。
