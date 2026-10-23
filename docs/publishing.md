# 发布、归档与 OpenList

本项目有两类发布，不能混用信任规则：

| 发布对象 | 信任依据 | 现状 |
| --- | --- | --- |
| 更新器单 JAR（内嵌 helper） | 本仓库官方 GitHub HTTPS API、资产 SHA256 与成功 CI 身份 | 由 [CI](ci.md) 构建与发布；不要求用户下载独立元数据或 Folkways 签名密钥 |
| Folkways 默认 GitHub 来源 | 所选仓库官方 Release 资产摘要、固定提交及对应成功发布 CI | `manifestUrl` 留空使用；仅空实例自动首装，已有手动/本地包不静默接管 |
| Folkways 五组件 | 可信发布者预置的 Ed25519 公钥、精确 payload 签名与每个 JAR 摘要 | 离线工具已实现；公共自动签名通道尚未启用 |

以下说明可选的 Folkways 签名镜像发布，不是普通用户默认 GitHub 首装的必需步骤。工具默认使用**逐 JAR 签名链接清单**，沿用真实 GitHub 资产，不需要重新制作 ZIP。清单恰含五个组件，客户端只下载所选模块。`pack` 保留可选 ZIP 兼容模式。

## 工具与权限边界

需要 Java 21 JDK、Python 3.11+，无第三方 Python 依赖。

- `tools/fetch_release.py`：只读抓取发布和资产，校验 digest 与前后快照。
- `tools/publish.py`：生成密钥、离线签名、验包、输出 OpenList 写入计划，或显式发布到本地挂载目录。
- `plan` 不读取令牌、不联网、不修改 OpenList 配置。
- 当前工具没有生产 OpenList HTTP 写入适配器；不要把 dry-run 计划当成已上传。

密钥应保存在仓库之外的受限目录或独立签名环境，不能进入 PR 工作流、客户端或发行包。这里不要求配置任何生产凭据。

## 目录布局

每个仓库与游戏版本独立，所有渠道按 MC 版本分组：

```text
folkways/
├── IzakyL/folkways/1.21.1/
│   ├── nightly/manifest.json
│   ├── alpha/latest.json
│   ├── alpha/0.1.0-alpha.1/manifest.json
│   ├── beta/latest.json
│   ├── beta/0.1.0-beta.1/manifest.json
│   ├── rc/latest.json
│   ├── rc/0.1.0-rc.1/manifest.json
│   ├── release/latest.json
│   └── release/0.1.0/manifest.json
└── davisye/folkways/1.21.1/...
```

这是布局示例，不表示每个渠道已有真实发布。nightly 最终只留一份最新清单，包含同一构建五 JAR 的 URL、大小与 SHA256；其余渠道按版本保留不可变清单。可选 ZIP 模式每套多一个 `bundle.zip`。

非 nightly 的 `latest.json` 与所指版本 `manifest.json` 字节完全相同，也是可验签的 envelope。客户端将 `manifestUrl` 配置为实际服务提供的该地址；nightly 则使用 `nightly/manifest.json`。不要猜测下载 URL 或省略存储站点已有的签名鉴权。

**元数据归档不等于二进制归档。** 外链依赖上游继续保留资产。长期存储需要不可变上游发布或真实的持久二进制副本，并将验证过的归档 URL 纳入签名清单。不得用会覆盖的 nightly 资产伪装正式版本归档。

## 1. 获取一致的发布输入

```powershell
python tools/fetch_release.py --repository IzakyL/folkways --tag nightly --output work/fetched --mirror-first
```

工具只接受两个允许的仓库。它解析真实 tag 的完整提交，要求五个 JAR 都有 GitHub asset SHA256 digest，下载后核对大小/摘要，前后比较 release、提交和所有资产的 ID/digest/size/更新时间。nightly 中途变化时丢弃整次输出，避免混代。失败可切换官方与允许的代理路由。

结果为 `work/fetched/jars/`、`provenance.json`、`release-snapshot.json`。它不签名、不上传。无法取得摘要的发布不能自动升级为可信输入。

## 2. 建立签名信任

在受限的签名环境中生成密钥；下面的路径仅为示例，实际应放在仓库之外：

```powershell
python tools/publish.py generate-key --private-key <private-key-file> --public-key <public-key-file>
```

命令不覆盖已有密钥。客户端只接收公钥文件内的 Base64 X509 DER Ed25519 公钥，并预先配置 `keyId → publicKey`。镜像同时提供的新公钥不能自行成为信任根。Windows 密钥文件权限继承父目录，运营方应限制该目录访问。

Envelope 结构为 `{keyId,payload,signature}`：payload 是**精确 JSON UTF-8 字节**的 Base64，signature 是这些字节的 Ed25519 签名。签名不依赖跨语言重新序列化 JSON。

## 3. 生成逐 JAR 清单

从本次 provenance 取完整提交，为对应仓库/MC/渠道分配严格递增的正整数 sequence；不要只比较 `0.1.0` 或本地时间。

```powershell
python tools/publish.py manifest --jar-dir work/fetched/jars --provenance work/fetched/provenance.json --output work/signed-release --repository IzakyL/folkways --build-id <full-commit-sha> --channel nightly --version nightly --sequence <positive-sequence> --tag nightly --key-id primary --private-key <private-key-file> --public-key <public-key-file>
python tools/publish.py verify --package work/signed-release --public-key <public-key-file>
python tools/publish.py plan --package work/signed-release --public-key <public-key-file> --mount folkways
```

`sign-release` 是 `manifest` 的别名。默认读取 provenance 同目录的 `release-snapshot.json`；签名前核对 tag、完整提交、五资产 ID/大小/摘要、本地字节与实际 modId。签名清单覆盖 repository、MC、loader、channel、buildId、sequence 和所有组件信息。

逐 JAR 模式只输出 `manifest.json`，不复制 JAR、不创建 ZIP、不上传文件。`verify` 验证签名和结构，**不会重新下载远程 JAR**；字节证据来自获取输入时的校验，客户端安装前仍须自行验包。

组件必须恰好是 `core`、`vanilla`、`create`、`modulargolems`、`cookery`，每个包括 `file`、`size`、`sha256`、有效 HTTPS `url`，可附 `mirrors`。默认推荐官方 canonical URL，再由客户端网络策略选择 GitHub 代理。Core/Vanilla 必装并不意味着其他三个组件可来自另一构建。

正式渠道先抓取**实际非 nightly tag**，再给 `manifest` 对应 `--tag`、`--channel`、`--version`。工具拒绝不匹配的来源、提交、tag 或摘要，不为不存在的资产编造 URL。

## 4. 本地挂载目录发布

当运营方确认 OpenList 挂载的是已授权的本机目录，可发布到一个预先存在的测试 root：

```powershell
python tools/publish.py publish-local --package work/signed-release --public-key <public-key-file> --root work/mirror-fixture --execute-local
```

按仓库/MC/渠道加跨进程锁，覆盖该渠道全部归档和 latest。默认逐 JAR 模式把小清单写到同目录临时文件，fsync 后原子替换；相同包重试幂等。不会复制或上传组件 JAR。

- nightly 拒绝旧序号，提交后只留最新一套。
- 非 nightly 版本目录不可被不同内容覆盖；相同字节可幂等重试。
- 归档验包后才更新 `latest.json`，小序号只补档、不倒退指针；相同序号不同清单拒绝。
- 归档提交后 latest 更新失败，可重试同一包，无需删除已发布版本。
- 工具拒绝相关路径中的符号链接、junction 和其他 reparse point。

## 可选 ZIP 模式

只有部署方确实需要单包，且拥有真实可下载的 bundle URL 时才使用 `pack`。它接受与 `manifest` 相同的输入与签名参数，并额外要求 `--bundle-url`；`--bundle-mirror` 也必须返回同一 ZIP 字节。五个原始 JAR 不是完整 ZIP 的镜像。

本地 ZIP 发布先换 bundle，最后提交 manifest。这是**逐文件原子 rename + manifest-last**，不是跨文件原子事务。在切换或中断窗口中，读端可能看到旧清单与新 ZIP，必须按摘要拒绝并有限重试。

事务期间 `.publisher/` 保存有界恢复日志与上一对文件。下一次同目标发布先验证提交结果，或恢复旧套；初次归档仅清理日志明确记录的未完成文件。成功后清理旧恢复数据。不要将这一机制解释为断电持久性保证。

## OpenList 接入验收

UrlTree 适合展示只读外链；可写能力由后端驱动决定。`PUT /api/fs/put`、创建目录、重命名都需要相应授权。HTTP 200 或 WebDAV 声明支持 MOVE，不能证明任务完成、访客已可读取，或后端提供原子操作。[OpenList FS API](https://openlistteam.github.io/docs/guide/api/fs.html)

生产适配器上线前，应在授权测试挂载依次验证：最小权限账号、上传任务完成、写后读回 SHA256、访客鉴权、缓存失效、同时发布、中断恢复、正式版本防覆盖、latest 不倒退，以及上游删除后归档二进制仍可获取。

`github.davisye.cn` 的 Release JAR 路径经过完整下载和 Range 字节验证，代理格式为 `/_github/<official-host>/<path>`。raw 文本 Range 仍有已知压缩表示边界，不能以 Release 验证推断所有路径正确。客户端按签名摘要验包并在允许路由间回退；代理不是信任根。
