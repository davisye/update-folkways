# CI 与更新器自身发行

工作流为 [`.github/workflows/ci.yml`](../.github/workflows/ci.yml)。公开 Release 只发行一个可直接放入 Minecraft `mods` 的 NeoForge 1.21.1 Mod JAR。

## 质量门禁

- 每个 PR、main push 和 `v*` tag push 都在 Ubuntu 24.04 与 Windows 上使用 Java 21、Python 3.12 构建。`Java 21 / ubuntu-24.04`、`Java 21 / windows-latest` 两个检查名称保持稳定。
- 两个平台都运行 Gradle 的全部 Java/进程测试以及 `tests/publisher` 下的 Python 发布测试。两边均成功后，发布 job 才能开始。
- `prepare` 校验外层 Mod 和内嵌 helper 的完整构建身份、Java 21 class 版本、Mod 版本与 helper 运行依赖，并生成只在当前 Actions run 内使用的发行清单。
- 两个平台使用实际发行 JAR 中的 helper 做离线首次运行烟测：隔离临时实例、默认已发行包自动管理、真实父 Java 进程退出后 helper 执行、JAR 摘要不变、第二次启动清理上次已退出的 helper。此探针不启动 Minecraft、不访问网络、不修改用户实例；它不能替代真实 NeoForge 客户端验收。
- PR 只有 `contents: read`，不运行发布 job。没有 `pull_request_target`、跨 run 的 `workflow_run` 下载或来自 PR 的写权限。
- 公开产物只从当前 run 的 Linux 构建取得。候选产物保留两天，Java 测试报告保留三天；产物和报告名包含 `run_attempt`，避免同次 run 重跑时读取旧身份。

发布失败后选择 **Re-run all jobs**，重新通过两平台质量门禁。仅重跑 failed release job 不会复用上一 attempt 的构建文件。PR 旧验证可取消；发布 job 按渠道串行，发布途中不自动取消。

## 单 JAR 格式

公开附件只有：

```
folkways-updater-neoforge-1.21.1-<version>.jar
```

Mod JAR 的 `META-INF/folkways-updater-build.json` 使用 schema 1、`component=companion`。`META-INF/folkways-updater/helper.jar` 内嵌独立 fat helper，包含 Gson、退出后更新入口及 `component=bootstrap` 的 BuildInfo。两层的版本、完整提交、runId、runNumber、runAttempt、sequence、channel 和 Minecraft 版本必须相同。

`updater-core:bootstrapJar` 仍是构建中的内部打包任务，不是需要用户单独安装的附件。`dist` 仅保留 Mod JAR 与内部 `update-metadata.json`，后者绑定当前候选摘要、文件名和构建来源，供发布 job 再次校验。发布脚本显式只上传该 JAR；内部清单、standalone JAR、seed 脚本和 checksums 均不上传。

## 自动发行与恢复

受信任 main push 通过检查后更新 `nightly`。发布脚本确认提交仍是 main 当前提交，并从既有 nightly 的 companion JAR 读取高水位：先以官方 API 返回的 SHA256 digest 和大小校验下载内容，再读取 JAR 内 BuildInfo。读取最多 64 MiB，缺少可信摘要、构建来源无效或候选不明确时停止发布。

新 JAR 上传并与 GitHub 返回的 digest/size 校验成功后，才清理旧附件并完成发布。成功后的 nightly 只有一个 Mod JAR。原来包含 standalone、脚本和清单的旧 nightly 可以迁移；历史版本 Release 保留。

上传与清理之间不是跨附件原子事务，期间客户端可能遇到多个 companion 附件，须拒绝并稍后重试。发布中断后，只有文件名、官方 digest、size 都与本次候选完全相同的已上传 JAR 可作为受限恢复对象：忽略它后仍须唯一确认旧包高水位，其他未知附件不能决定信任。已完成的同字节 nightly 重跑不写入；旧 sequence 不覆盖新包。

`vMAJOR.MINOR.PATCH`（也接受 semver prerelease 后缀）tag 通过检查后创建对应版本。提交须已位于 main 历史；已公开的版本不可覆盖、不删档。相同提交的未公开草稿可重试。按 tag 的 REST 查询只返回公开版本，因此恢复时使用授权 Release 列表分页查找，只有同 tag 唯一且指向当前完整提交的草稿可复用。

发布 job 从受信任 main 检出发布工具，校验 Actions run 的仓库、push 事件、工作流路径、提交和构建标识。只有此 job 请求临时 `GITHUB_TOKEN` 的 `contents: write` 和 `actions: read`；不使用长期 PAT、不新增签名密钥 secret。

## 版本与信任

main 的版本为 `0.1.0-nightly.<run_number>.<run_attempt>`，版本 tag 去掉 `v` 得到对应版本。BuildInfo 中 `runNumber` 为整数，`sequence=run_number * 1000 + run_attempt`，`runId` 为官方 Actions run 查询标识。CI 将 `-Pversion`、`-PbuildId`、`-PrunId`、`-PrunNumber`、`-PrunAttempt`、`-Psequence`、`-Pchannel` 传给 Gradle；本地未注入这些参数的构建保持开发身份。

GitHub 为同一 workflow 的 `run_number` 提供逐次递增保证，`run_id` 只保证唯一。保持 `.github/workflows/ci.yml` 路径和身份稳定，不删除重建复位计数；每次 attempt 限制在 1–999。迁移工作流前须设计可信序号迁移。

自动私钥签名尚未启用。客户端固定以公开仓库 `davisye/update-folkways` 的官方 GitHub API 为信任来源，核验附件 digest/size，以及包内提交、版本和成功的官方 `ci.yml` push run。run 需已 `completed/success`；发布 job 仍运行时可暂时拒绝并稍后重试。镜像可传输相同字节，不能独立决定可信摘要。

已发行的单 JAR 首次运行自动建立管理归属、预下载更新，退出后的 helper 执行替换；不需要用户安装脚本或 standalone。默认本地源码构建受保护，显式启用本地开发模式也禁止替换。真实游戏的退出事件、文件锁和重新启动行为仍需客户端与服务端验收，离线探针只覆盖进程和包格式。

## Action 固定版本

以下完整 commit 在 2026-10-23 从官方发布 tag 解析；工作流使用 SHA：

| Action | 官方版本 | 固定 commit |
| --- | --- | --- |
| [actions/checkout](https://github.com/actions/checkout/releases/tag/v7.0.1) | v7.0.1 | `3d3c42e5aac5ba805825da76410c181273ba90b1` |
| [actions/setup-java](https://github.com/actions/setup-java/releases/tag/v6.1.0) | v6.1.0 | `b843ebf9b913146b38da2eaa7aeddf76074ae1ad` |
| [actions/setup-python](https://github.com/actions/setup-python/releases/tag/v7.1.0) | v7.1.0 | `6d4fd22b20465ccc0f7354476588e8df7c9ca0e0` |
| [actions/upload-artifact](https://github.com/actions/upload-artifact/releases/tag/v7.0.2) | v7.0.2 | `cf430e030ddbb5b0abf93d22962f4752f3646cd9` |
| [actions/download-artifact](https://github.com/actions/download-artifact/releases/tag/v8.0.2) | v8.0.2 | `9000827ccba6bdab643e8b6fd33ac0654aef8333` |
| [gradle/actions](https://github.com/gradle/actions/releases/tag/v6.4.0) | v6.4.0 | `3f5f9adaf7d9fecd50b5935e54106014257a94e6` |

参考：[GitHub 最小权限](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#permissions)、[固定 Action SHA](https://docs.github.com/en/actions/reference/security/secure-use#using-third-party-actions)、[同工作流传递产物](https://docs.github.com/en/actions/tutorials/store-and-share-data)、[run_number 与 run_id 的保证](https://docs.github.com/en/actions/reference/workflows-and-actions/variables)。
