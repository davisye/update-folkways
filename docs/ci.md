# CI 与更新器自身发行

工作流为 [`.github/workflows/ci.yml`](../.github/workflows/ci.yml)。它发行更新器自己的两个 JAR，不重新打包 Folkways 模组。

## 质量门禁

- 每个 PR 和 main push、`v*` tag push 都在 Ubuntu 24.04 与 Windows 上使用 Java 21、Python 3.12 构建。
- 两个平台都运行 Gradle 的全部 Java/进程测试及 `tests/publisher` 下的 Python 发布测试。只有两个平台均成功，发布 job 才能开始。
- `prepare` 后，两个平台都用实际 Release 形状的两个 JAR 与本机 seed 执行首次安装烟测：隔离临时实例、显式 adopt、默认本地开发模式、真实前台 `java -version`。检查安装前后 JAR 摘要不变、归属记录完整、runtime 清空且无 child barrier；不启动 Minecraft、不访问网络、不修改用户实例。Windows 用临时 PowerShell 包装脚本传递真实 `string[]`，Linux 使用 bash 与 flock。
- PR 只有 `contents: read`，不运行发布 job。没有 `pull_request_target`、跨 run 的 `workflow_run` 下载或来自 PR 的写权限。
- 产物只从当前 run 的 Linux 构建获取；两个平台都独立检查 JAR 构建身份、Java 21 class 版本、伴随 Mod 版本与 checksums。
- 候选产物保留两天，测试报告保留三天。PR 的旧验证可取消；发布 job 按渠道串行，不在发布途中自动取消。

候选产物和报告名都包含 `run_attempt`，发布 job 只下载当前 attempt 的候选文件，避免重跑时读取旧版本身份或同名 artifact 冲突。发布失败后应选择 **Re-run all jobs**，重新通过两个平台的质量门禁；只重跑 failed release job 不会复用上一 attempt 的构建文件。

## 自动发行

受信任 main push 通过检查后更新 `nightly`。发布脚本确认当前提交仍是 main 最新提交，并拒绝用旧 sequence 覆盖新 nightly。新文件上传并与 GitHub 返回的 asset digest/size 校验后，才发布最终清单并清理旧附件，成功后 nightly 只保留一套六个文件。

`vMAJOR.MINOR.PATCH`（也接受 semver prerelease 后缀）tag 通过检查后创建对应版本。提交必须已经位于 main 的历史中；不能仅给任意 PR 提交加 tag 就取得发布资格。已公开的版本不覆盖、不删档。相同提交的未公开失败草稿可以重试；已经发布的 tag 需用新版本号重新发行。

草稿恢复使用有权限的 Release 列表分页查询，因为按 tag 查询只返回已公开版本。只有同 tag 唯一且指向当前完整提交的草稿可复用；有重复草稿、其他提交或查询不完整时停止发布，避免另建同名草稿或覆盖未知状态。

发布 job 从受信任 main 检出发布工具，校验 Actions run 的仓库、push 事件、工作流路径、完整提交和构建标识。只有这个 job 请求临时 `GITHUB_TOKEN` 的 `contents: write` 和 `actions: read`；不使用长期 PAT，不新增签名密钥 secret。

## 文件与版本

每个 Release 有六个附件：

| 文件 | 用途 |
| --- | --- |
| `folkways-bootstrap-<version>-standalone.jar` | 独立 Bootstrap 与更新核心 |
| `folkways-updater-neoforge-1.21.1-<version>.jar` | NeoForge 游戏内伴随 Mod |
| `launch.ps1`、`launch.sh` | 首次手工安装的稳定 seed 启动入口 |
| `update-metadata.json` | 自更新机器清单，绑定两个 JAR 的版本、摘要和构建来源 |
| `checksums.sha256` | 两个 JAR、两个 seed 与元数据的 SHA256 |

main 的版本为 `0.1.0-nightly.<run_number>.<run_attempt>`；版本 tag 去掉 `v` 得到对应版本。metadata 与 JAR build-info 的 `runNumber` 为整数，sequence 为 `run_number * 1000 + run_attempt`，同一次 run 的重试也有更大序号。`runId` 保留为字符串，用来查询官方 Actions run；客户端同时核对官方 `run_number`。JAR 内 `META-INF/folkways-updater-build.json` 记录相同版本、提交、run 和 sequence，本地未注入这些参数的构建保留开发身份。

GitHub 为同一 workflow 的 `run_number` 提供逐次递增保证，而 `run_id` 只保证唯一。必须保持 `.github/workflows/ci.yml` 的工作流路径和身份稳定，不能删除后重建并复位计数；若确需迁移，应先设计受信任的序号迁移，而不是让客户端忽略高水位。每次 attempt 限制在 1–999，避免与下一个 run 的序号空间重叠。

CI 将 `-Pversion`、`-PbuildId`、`-PrunId`、`-PrunNumber`、`-PrunAttempt`、`-Psequence`、`-Pchannel` 传给 Gradle。不能只依赖文件名或固定 `0.1.0-dev` 比较新旧。

## 信任与安装边界

自动私钥签名目前未启用。自更新客户端以固定公开仓库 `davisye/update-folkways` 的**官方 GitHub API**为信任来源：检查 asset 的 GitHub digest/size，并把清单提交与对应 Actions run 关联。run 必须已经 `completed/success` 才安装，因此发布 job 尚在运行时可能暂时提示稍后重试。用户镜像可传输相同字节，不能独立决定可信摘要。

首次安装仍需按 README 手工放好 seed、Bootstrap 和伴随 Mod。seed 协议 v1 不自动替换脚本；两个 JAR 的自更新通过运行副本、离线 helper 和启动门禁完成。只执行旧的 `java -jar` 入口不能保证覆盖正在使用的 Bootstrap。

GitHub Release 附件集合没有多文件原子提交保证。上传中断或不同代缓存必须导致客户端拒绝安装并有限重试；不得以 HTTP 200 或“附件存在”代替摘要与 run 验证。稳定版本的长期可用性取决于仓库保留策略，nightly 明确只保留最新一套。

## Action 固定版本

2026-10-23 从官方仓库发布 tag 解析到以下完整 commit，工作流使用 SHA 而不是可变 tag：

| Action | 官方版本 | 固定 commit |
| --- | --- | --- |
| [actions/checkout](https://github.com/actions/checkout/releases/tag/v7.0.1) | v7.0.1 | `3d3c42e5aac5ba805825da76410c181273ba90b1` |
| [actions/setup-java](https://github.com/actions/setup-java/releases/tag/v6.1.0) | v6.1.0 | `b843ebf9b913146b38da2eaa7aeddf76074ae1ad` |
| [actions/setup-python](https://github.com/actions/setup-python/releases/tag/v7.1.0) | v7.1.0 | `6d4fd22b20465ccc0f7354476588e8df7c9ca0e0` |
| [actions/upload-artifact](https://github.com/actions/upload-artifact/releases/tag/v7.0.2) | v7.0.2 | `cf430e030ddbb5b0abf93d22962f4752f3646cd9` |
| [actions/download-artifact](https://github.com/actions/download-artifact/releases/tag/v8.0.2) | v8.0.2 | `9000827ccba6bdab643e8b6fd33ac0654aef8333` |
| [gradle/actions](https://github.com/gradle/actions/releases/tag/v6.4.0) | v6.4.0 | `3f5f9adaf7d9fecd50b5935e54106014257a94e6` |

参考：[GitHub 最小权限](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#permissions)、[固定 Action SHA](https://docs.github.com/en/actions/reference/security/secure-use#using-third-party-actions)、[同工作流传递产物](https://docs.github.com/en/actions/tutorials/store-and-share-data)、[run_number 与 run_id 的保证](https://docs.github.com/en/actions/reference/workflows-and-actions/variables)。
