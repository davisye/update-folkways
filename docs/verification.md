# 验证说明

自动化测试、真实进程验证与完整 Minecraft 运行分别记录。当前源码或某个环境通过，不代表任意启动器、未来 nightly 或全部模组组合已验收。

## 当前单 JAR 模式

单 JAR 模式改变了安装与生命周期边界：普通启动器启动现有版本，Mod 预下载，独立 helper 等整个 JVM 退出后安装。它不继承旧 Bootstrap 扫描前更新的保证。

当前已完成下述本地 NeoForge 生产专服生命周期实测。公开发行的单 JAR CI 与两个公开版本的网络升级结果仍待补充；以下是完整验收标准，不能全部视为已通过：

1. 只下载一个公开发行的 companion JAR 放入空白测试实例，无 standalone/scripts/metadata/keys；首次自动初始化、释放 helper，正常检查与预下载。
2. 新官方实例无配置时 managed；既有配置不覆盖；源码构建、NeoForge dev、显式 true/env/marker 阻止网络和替换，独立 helper 同样重查。
3. helper 只在精确父 JVM 退出且无其他游戏 lease 后安装，原 companion 路径和归属保留，两个实际版本的官方摘要与构建身份一致。
4. 人为重叠“旧 JVM 退出 / helper 每个替换阶段 / 新 JVM 启动及较晚构造”，确保不让混代集合进入世界；冲突启动可以被拒绝并提示重开。
5. 覆盖坏摘要、失败 CI、旧序号、人工改写、路径链接、并发 helper、半下载与安装中断。保留旧可用文件或恢复状态，缓存有界。
6. `/shutdown` 的权限、倒计时、取消和正常存档仍有效；drop-in 专服 `/restart` 单独验证握手、异常退出和重启限额，完整客户端重启不在当前范围。

实际通过结果应写明版本、提交、系统、预期与观察，不使用旧方式的测试数量替代。

### 本地专服重启进程回归

Windows 的 7 项重启测试已通过，包含实际父 JVM/helper/下一代 Java 进程，以及 Windows 参数解析：保留工作目录、空格、中文、空参数、字面引号、反斜线、10K 长参数，支持通过 PATH 启动的 `java`。未完成停止凭证、错误 token、未完成事务、禁用命令和预算耗尽均不重启。

这组测试证明 Java 进程级交接，Minecraft 的实际停止与保存另按下一节验证，不能由参数测试代替。

### 真实 NeoForge 专服生命周期

Windows / Java 21 / NeoForge 21.1.233 的隔离实例只安装一个 updater JAR，使用合成 CI 形状的测试构建，**不是公开发行身份验证**。实际生产加载通过 ModList、class resource、物理 JAR 身份门禁，helper 自动释放并 ready。

通过 OP 4 管理接口发出 `/restart 0` 后，日志确认玩家/世界/区块正常保存，原 JVM 以 0 退出；helper 启动新整 JVM，原世界重新加载，并建立下一代 helper。`restartLimit=1` 时第二次重启在停服前拒绝，状态命令仍成功；之后 `/shutdown 0` 正常保存并退出。两个游戏 JVM 及 helper 最终终止，`level.dat` 和旧备份均有内容，`localDevelopment=true` 时 updater 摘要不变、无遗留事务日志。

脱敏 [机器记录](acceptance/2026-10-23-dropin-lifecycle.json) 只保留事实和边界。该测试没有完整 Folkways 包、真实玩家或 Minecraft 客户端，也没有验证两个公开单 JAR 版本之间的网络更新或断电恢复。

## 已有核心验证

已有自动化覆盖签名/摘要、来源高水位、模块混包、选择下载、归属与本地改动保护、逐文件事务、中断恢复、pending/previous、链接路径、真实子 JVM 和发布幂等恢复等行为。[CI 文档](ci.md) 与 [工作流](../.github/workflows/ci.yml) 是当前执行命令的依据。

另外已用两个源仓库的十个真实 Folkways JAR 核对 GitHub digest，并实际验证代理完整下载与 `bytes=0-127`；用五 JAR 输入完成逐 JAR 签名互操作及两/五模块选择。临时 NeoForge 专服做过正常停止与外部 supervisor 重启；这些不等于完整 Folkways 包或新 drop-in 重启已验证。

OpenList 访客目录和跳转读回证明外链分发路径可用，不证明自动签名或持久二进制归档。raw 文本 Range 的压缩表示问题仍有边界。

## 历史：外部 Bootstrap/seed 验收

2026-10-23 的 [run 39953990440](https://github.com/davisye/update-folkways/actions/runs/39953990440) 对应提交 `218b9f6fb741b103c847b78a9516035f03257f3d`：Windows 55 Java 通过、零跳过；Linux 53 通过、2 项 Windows 专用测试跳过；两端 43 Python 通过，并执行实际 seed 首装烟测。六附件下载字节与官方 API 摘要一致。

随后 [run 39954859064](https://github.com/davisye/update-folkways/actions/runs/39954859064) 发布 7.1，在 Windows 隔离实例以 6.1 原 seed 完成 `6.1 → 7.1`。两个 JAR 摘要及嵌入构建身份匹配新发布，旧 companion 路径保留，pending/journal/runtime/child 清理，备份保留一套。首次检查暂不可用时保留旧文件并启动；后续预下载和安装成功，未对暂不可用原因下结论。

这项实验使用外部 seed 和独立前台 Java，没有启动 Minecraft 或真实世界；不是新 drop-in 模式的端到端证明。[历史机器记录](acceptance/2026-10-23-self-update.json) 保留精确身份与边界，nightly 历史附件可按策略清理。

## 后续目标环境验收

- 新 drop-in 模式的真实 Windows/Linux 网络升级、重开竞态与中断恢复。
- 完整 Folkways 模组包、所选扩展及目标存档的升级后加载与重新打开。
- 常用客户端启动器中的退出行为与错误提示；完整客户端重启仍需适配。
- 真实 OpenList 后端的任务完成、写后读回、缓存、并发与最小权限账号。
- 断电、磁盘故障与多文件耐久性，不能由普通故障注入替代。

验收日志只提交脱敏的必要证据，不包含本机路径、SSH/生产配置、令牌、启动器会话、世界或玩家数据。
