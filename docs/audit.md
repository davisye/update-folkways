# 启动顺序与信任边界

本页记录影响实现的公开技术约束。源码核查基线为 Minecraft 1.21.1、NeoForge 21.1.233、ModLauncher 11.0.5、FancyModLoader 4.0.42；它不是对所有加载器版本的保证。

## 为什么采用外部 Bootstrap

对上述版本的 source JAR 核查得到以下顺序：

1. `Launcher.run` 先发现 transformation services，再初始化它们。
2. 服务发现先调用 BOOT 层 `ITransformerDiscoveryService.candidates`。
3. FML 的 `ModDirTransformerDiscoverer` 已在此时遍历 mods 目录、检查 JAR 中的服务候选。
4. 候选文件加入 SERVICE 层并构建层后，加载 `ITransformationService`。
5. `onLoad`、参数处理、`initialize` 之后才进入 `beginScanning`，后者触发 FML 正式 Mod 扫描。

因此 `onLoad` 早于 FML 正式 Mod discovery，却不能保证早于任何 mods JAR 的探查或打开。普通 Mod 也不能自动部署到更早的 BOOT 层。依赖此 SPI 替换已打开文件缺少可靠排序保证，Windows 文件占用会进一步限制替换。

Bootstrap 在创建真实游戏 JVM 之前完成文件处理。伴随 Mod 不安装 transformation service，不注入加载器；游戏内操作仅准备下一次启动的更新。

核查入口为 `Launcher`、`TransformationServicesHandler.discoverServices`、`ModDirTransformerDiscoverer.candidates` 和 `FMLServiceProvider.beginScanning`。公开源码：[ModLauncher](https://github.com/McModLauncher/modlauncher)、[FancyModLoader](https://github.com/neoforged/FancyModLoader)。核查应以兼容基线的 source JAR 为准，不能把主分支执行顺序直接当作旧版本证据。

## 三个进程边界

| 进程 | 职责 |
| --- | --- |
| 固定 seed | 手动安装的稳定入口；复制 runner、先运行自更新准备 helper，退出后启动新 Bootstrap |
| Bootstrap / supervisor | 本地开发保护、进程门禁、验包、恢复、安装、创建并等待真实游戏 JVM |
| 游戏 JVM / 伴随 Mod | 定时检查、提示、预下载、正常停止；不原地覆盖运行中的 JAR |

supervisor 的独占锁防止多个监督者同时运行。持久 `child.json` 记录启动阶段或子进程 PID 与启动时间；监督者意外退出后，存活子进程仍阻止下一次替换。无法确认启动结果时保留门禁，要求人工确认，优先保护正在运行的实例。

客户端启动器可能分离进程，或必须管理登录与会话。只有能够提供真实前台 JVM、并等待它退出的接入方式才能满足该进程边界；任意启动器快捷方式都不自动等于受监督游戏进程。

## 文件、事务与缓存

每个下载文件受大小、SHA256、文件名、实际 modId 和选择集约束。归属记录明确哪些已安装 JAR 可由 updater 管理；人工改动的文件不被静默接管。

安装采用逐文件原子 rename、恢复日志和上一套备份，受监督启动必须在完整提交或恢复后继续。这不提供多个独立 JAR 的文件系统原子性，也未完成断电耐久性认证。运行期间只保留一个完整 pending 集合；替换缓存时可短暂保留 previous 集合作为恢复点。已完成事务清理临时候选和旧恢复数据，避免缓存无限增长。

路径校验拒绝受管路径中的符号链接和 Windows reparse point/junction。测试覆盖拒绝路径逃逸和链接路径；它不是允许不可信本机用户同时任意改写实例目录的隔离容器。

## 发布信任

Folkways 更新信任预配置的 Ed25519 公钥，而非托管站点名称。签名覆盖精确 payload 字节，以及仓库、MC、渠道、构建、单调序号与每个 JAR 的摘要。镜像只负责传输。相同 mod 版本字符串不足以证明同一构建。

更新器自身更新固定信任本仓库官方 GitHub HTTPS 发布与成功 CI 构建身份，校验资产摘要及元数据绑定。普通 PR、未知构建和任意镜像元数据不能授权替换。官方 GitHub 或受信仓库/CI 权限被攻破不在此信任模型可独立解决的范围内。

## 外部分发约束

- Folkways 原始发布是多个独立 JAR，不能假设存在完整 ZIP 或现成签名索引。
- 变化中的 nightly 可能出现资产短时缺失或混代；下载前后快照与签名摘要不一致必须丢弃重试。
- `github.davisye.cn` 采用 `/_github/<official-host>/<path>` 路由。Release JAR 完整下载和字节 Range 已做实际验证；raw 文本路径仍有压缩表示的 Range 边界，不应推广为所有路由的保证。
- OpenList UrlTree 提供外链目录，不能凭目录项证明底层二进制长期保留。
- OpenList API 的创建、上传、重命名权限和行为取决于存储驱动；HTTP 200、WebDAV 方法列表都不足以证明跨文件原子发布或上传任务完成。

发布布局、签名与存储验收见 [发布镜像指南](publishing.md)，当前验证范围见 [验证说明](verification.md)。
