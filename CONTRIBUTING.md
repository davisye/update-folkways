# 贡献指南

感谢改进 Folkways Updater。先阅读 [README](README.md) 的兼容范围与开发保护；涉及启动、安装或更新信任的改动，请同时查看 [架构](docs/audit.md)。

## 本地开发

需要 Java 21 JDK 和 Python 3.11+，使用仓库的 Gradle Wrapper。

```powershell
.\gradlew.bat build --console=plain
python -m unittest discover -s tests/publisher -v
```

Linux 使用 `./gradlew`。首次构建需下载依赖。其他发布测试命令以 [CI](docs/ci.md) 为准，提交前运行与你的改动相关的检查即可。

在独立测试实例开发，保留 `localDevelopment=true`，或创建 `.folkways-updater/LOCAL_DEVELOPMENT`。不要把测试安装指向日常存档或生产发布目录。

## 提交与 Pull Request

main 通过 Pull Request 合并，要求 Windows 与 Ubuntu 的 CI 均通过并与主分支保持同步；禁止强推及删除。维护者同样遵循检查，依赖更新不会自动合并。

先搜索现有 [Issues](https://github.com/davisye/update-folkways/issues)。小范围修复可直接提交 PR；较大的协议或架构调整，请先说明目标、兼容性和失败时行为。

一个 PR 尽量解决一个具体问题。描述触发条件、改变后的行为、验证结果及未覆盖的限制。启动、事务、自更新和发布代码应有针对失败或恢复路径的行为测试，避免只复述实现的断言。不要顺带格式化无关代码。

保持既有安全边界：开发模式优先、无静默接管、真实游戏进程退出前不替换、签名/来源/摘要失败时拒绝安装、正式版本不可变、缓存有界。跨平台路径改动须考虑 Windows junction、文件占用及 Linux 行为。

## 问题报告

提供版本、操作系统、Java/NeoForge 版本、源与通道、是否开发模式、可复现步骤、预期和实际结果。日志仅附与问题相关的脱敏片段，不要上传完整实例、世界或账号会话。

发现安全漏洞请按 [SECURITY.md](SECURITY.md) 私下报告，不在公开 Issue 中附可利用细节。

## 不应提交的内容

不要提交密钥、token、Cookie、私有配置、`.env`、启动器账号/会话参数、本机绝对路径、SSH 配置、服务器管理日志、生产备份或玩家数据。测试夹具应使用虚构值和临时目录；真实下载二进制放在忽略的本地工作目录。

第三方代码或素材需要保留许可与来源。贡献遵循本仓库现有 [MIT 许可证](LICENSE)，第三方依赖见 [THIRD_PARTY.md](THIRD_PARTY.md)。
