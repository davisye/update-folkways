# 启动、退出与信任边界

## 单 JAR 模式的承诺

用户将一个 updater Mod 放入 mods，使用普通启动器。Mod 在本次运行中检查和预下载，内嵌的独立 helper 等整个父 JVM 退出后安装，后续启动加载已安装的版本。

这不是启动器级的扫描前更新。第一次仍运行用户手工安装的版本；网络、退出、系统关机与启动冲突都会影响更新何时完成。不能宣传“每次启动必定最新”。

## 为什么不通过 ModLauncher SPI 抢先替换

源码核查基线为 Minecraft 1.21.1、NeoForge 21.1.233、ModLauncher 11.0.5、FancyModLoader 4.0.42：

1. transformation service 的发现先调用 BOOT 层 discovery service。
2. FML 已在此时探查 mods 中的服务候选 JAR，再建立 SERVICE 层。
3. 随后加载 `ITransformationService` 并调用 `onLoad`、`initialize`。
4. `beginScanning` 才进入 FML 的正式 Mod discovery。

`onLoad` 虽早于正式 Mod discovery，却没有早于所有 mods JAR 打开的保证。普通 Mod 的构造阶段更晚，不能用它证明可以安全替换本次扫描的文件。[ModLauncher](https://github.com/McModLauncher/modlauncher)、[FancyModLoader](https://github.com/neoforged/FancyModLoader) 提供公开源码入口；核查旧版本应使用对应 source JAR。

因此单 JAR 模式保留当前加载集，安装放在 JVM 退出后。早期外部 Bootstrap/seed 仍是另一种部署方式，不能把它的扫描前能力套用到 drop-in 模式。

## 游戏进程与 helper

游戏 JVM 先登记 PID/启动时间 lease，再在同一更新锁下检查事务和安装完成标记，同时比对 NeoForge 扫描版本、类加载器提供的构建身份与当前物理 JAR。helper 等待精确父进程身份退出，并在持锁安装前和每次修改前重查其他存活 JVM、对应通道开关与文件归属。

快速重开游戏是正常需要处理的冲突场景：新 JVM 可能已经开始加载文件，尚未执行 Mod 构造。只看当下进程列表或“journal 是否存在”不够；安装完成标记与启动时刻的交叉检查用于拦截这种较晚进入门禁的启动。出现冲突时应保留恢复证据并中止加载，避免混代文件进入世界，之后重新启动。

完整客户端重启需要独立启动器适配。仅重启集成服务器不会让客户端重新加载 Mod。专服自动重启也必须有明确的 helper 握手和进程身份验证，不能把“父进程已退出”直接等同于授权重启。

启动时刻与安装标记的比较依赖稳定的系统时钟。helper 观察到壁钟实际倒退时保留异常标记并拒绝继续；周期采样不能证明采样间完全回拨再前跳从未发生。这不是跨进程的文件系统原子装载保证。

## 开发保护与初始归属

本地开发只由 `config.localDevelopment` 控制，默认 `false`，已有配置不覆盖。身份、IDE、旧 JVM/env 设置与标记文件不自动修改此值。该开关仅停止 Folkways 与依赖的检查、预下载、安装和恢复写入。

updater 自身由 `selfUpdateEnabled` 独立控制，本地开发开启不阻止自身检查、预下载、安装或恢复。IDE 中没有实际已安装 JAR 时没有自身替换目标；它不是“本地开发自动开启”的依据。

首次手工放入 updater JAR 是用户的初始信任步骤。仅改名且与已登记摘要/身份相同的文件可以重绑定，保留原高水位。字节不同的手动官方新版须先通过官方资产和 CI 校验后恢复自身托管。其他本地 updater 可在自身更新开启时获取经过完整验证的官方候选，但退出前后都要核对实际源文件快照，不能用身份变化绕过路径、摘要、序号和恢复检查。

自身身份校验失败或暂不可用，不改变本地开发值，也不阻断 Folkways 通道。未完成事务和并发游戏门禁仍可阻止本次加载或写入；两个开关不是跳过恢复的许可。

## 发布信任

updater 自身固定核对 `davisye/update-folkways` 的官方 GitHub HTTPS API：release asset 大小与 SHA256、同一次成功 `ci.yml` push、完整提交、递增序号和 JAR 内构建身份。下载字节可以经允许的镜像传输；镜像不能独立决定可信摘要。SHA256 不是数字签名。

Folkways 默认从所选仓库的官方 GitHub Release 获取，校验官方资产大小、SHA256、固定提交及对应成功发布 CI，下载前后复核发行快照。该信任依赖所选仓库及 GitHub；上游 JAR 没有本更新器的构建身份元数据，成功 CI 记录不能称为文件签名或独立构建证明。

`manifestUrl` 非空时选择独立签名通道，信任预配置的 Ed25519 公钥。签名覆盖精确 payload、源、MC、渠道、构建、序号及所有组件摘要。公共自动签名发布仍未启用，不影响默认官方来源。

缺失的基础前置 LDLib2 使用固定官方 Modrinth 项目/版本与官方文件摘要，不从第三方下载站取得新密钥。Create、Modular Golems、Cookery 的前置需要事先满足，不通过猜测文件名或安装任意搜索结果补齐。

受信 GitHub 仓库/CI 权限或本机同权限用户被攻破不在这套机制能独立消除的信任范围内。

## 文件事务与缓存

逐文件原子 rename、恢复日志、上一套备份和启动门禁共同防止未完成安装被当作完整状态。受管目录中的符号链接与 Windows reparse point/junction 被拒绝；实例根目录的同文件别名归一化不放宽子目录检查。

多个独立 JAR 没有跨文件的单次文件系统原子替换。日志和完成标记必须按顺序提交，出现新 JVM 或保护变化时保留未完成状态并停止。当前机制不提供断电耐久性保证。

正常成功后清理 pending、候选和临时 helper，备份保留有界一套。未知启动阶段的运行目录可能保留以供人工检查，不承诺所有异常下都零残留。

官方 Folkways 首次安装只允许空的 Folkways 集合。已有手动、本地编译或其他通道管理的包不静默接管。新增文件与已有受管文件在日志中区分：恢复只删除本次事务创建且摘要仍匹配的文件，不能因为原集合为空而删除用户后来放入的 JAR。前置依赖也受同一开发保护、进程门禁、归属及恢复检查约束。

## 外部分发

Folkways 原始发布是多个 JAR，不能假设有 ZIP 或签名索引。变化中的 nightly 需要快照与摘要一致性检查。OpenList UrlTree 是外链目录，不保证底层资产长期存在；上传、缓存和原子重命名取决于实际存储驱动。

`github.davisye.cn` 的 Release JAR 完整字节和 Range 经过实际验证；raw 文本 Range 仍有压缩表示边界，不能将结果扩展为所有路径。详见 [发布镜像](publishing.md) 与 [验证范围](verification.md)。
