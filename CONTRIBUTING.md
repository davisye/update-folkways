# 贡献指南

感谢改进 Folkways Updater。先阅读 [README](README.md) 的兼容范围与开发保护；涉及启动、安装或更新信任的改动，请同时查看 [架构](docs/audit.md)。

## 本地开发

需要 Java 21 JDK 和 Python 3.11+，使用仓库的 Gradle Wrapper。

```powershell
.\gradlew.bat build --console=plain
python -m unittest discover -s tests/publisher -v
```

Linux 使用 `./gradlew`。首次构建需下载依赖。其他发布测试命令以 [CI](docs/ci.md) 为准，提交前运行与你的改动相关的检查即可。

在独立测试实例开发，显式保留 `localDevelopment=true`，或创建 `.folkways-updater/LOCAL_DEVELOPMENT`。源码构建和 NeoForge 开发环境同样受保护；官方发行新实例的 managed 默认值不应被当作本地开发设置。不要把测试安装指向日常存档或生产发布目录。

## 提交与 Pull Request

main 通过 Pull Request 合并，要求 Windows 与 Ubuntu 的 CI 均通过并与主分支保持同步；禁止强推及删除。维护者同样遵循检查，依赖更新不会自动合并。

先搜索现有 [Issues](https://github.com/davisye/update-folkways/issues)。小范围修复可直接提交 PR；较大的协议或架构调整，请先说明目标、兼容性和失败时行为。

一个 PR 尽量解决一个具体问题。描述触发条件、改变后的行为、验证结果及未覆盖的限制。启动、事务、自更新和发布代码应有针对失败或恢复路径的行为测试，避免只复述实现的断言。不要顺带格式化无关代码。

保持既有安全边界：开发模式优先、无静默接管、真实游戏进程退出前不替换、签名/来源/摘要失败时拒绝安装、正式版本不可变、缓存有界。跨平台路径改动须考虑 Windows junction、文件占用及 Linux 行为。

## Drop-in 改动的验证

单 JAR 模式检查与预下载后，helper 等整个 JVM 退出才安装。涉及初始化或事务的改动，应覆盖新官方安装/已有配置/本地构建三种情形，以及父 JVM 退出、新 JVM 较晚构造、helper 每个写入阶段的重叠。冲突时可以拒绝启动，但不能让混代文件进入世界。

默认官方 Folkways 来源还应覆盖空集合首装、已有手动包拒绝接管、依赖缺失/版本冲突、切源或切换签名通道、部分新增文件的恢复。依赖写入不能绕过本地开发保护；真实网络测试应记录官方项目/版本/资产摘要，不把成功 CI 当作签名证明。

旧外部 seed 的进程测试不是 drop-in 已验证的替代品。公开结果必须注明真实 Minecraft/存档是否测试；不要把前台 Java 探针写成完整游戏测试。

## 问题报告

提供版本、操作系统、Java/NeoForge 版本、源与通道、是否开发模式、可复现步骤、预期和实际结果。日志仅附与问题相关的脱敏片段，不要上传完整实例、世界或账号会话。

发现安全漏洞请按 [SECURITY.md](SECURITY.md) 私下报告，不在公开 Issue 中附可利用细节。

## 不应提交的内容

不要提交密钥、token、Cookie、私有配置、`.env`、启动器账号/会话参数、本机绝对路径、SSH 配置、服务器管理日志、生产备份或玩家数据。测试夹具应使用虚构值和临时目录；真实下载二进制放在忽略的本地工作目录。

第三方代码或素材需要保留许可与来源。贡献遵循本仓库现有 [MIT 许可证](LICENSE)，第三方依赖见 [THIRD_PARTY.md](THIRD_PARTY.md)。

## 客户端配置页验收

编译验收入口：`./gradlew -PclientUiSmoke :companion:compileClientSmokeJava`。有图形环境时运行 `./gradlew -PclientUiSmoke :companion:runClient`（Windows 用 `gradlew.bat`）。仅使用独立的 `work/client-config-smoke`，自动核对实际配置工厂、按钮、保存合并、本地保护与页面边界，截图后关闭自己的测试客户端。此源码不进入发行 JAR；不要把测试运行目录改为实际游戏实例。
