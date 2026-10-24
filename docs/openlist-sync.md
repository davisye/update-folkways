# OpenList nightly 自动同步

这个任务刷新 OpenList 中 Folkways 的下载链接，独立于更新器发布。它不构建 JAR，不创建签名清单，也不把文件上传到 OpenList。

固定目标是同一台主机上的 `http://127.0.0.1:5244`，存储 `id=5`、驱动 `UrlTree`、实际挂载 `/Guest/folkways`；访客目录为 `/folkways`。仅同步以下两个分支：

```text
IzakyL/folkways/1.21.1/nightly
davisye/folkways/1.21.1/nightly
```

每次从官方 GitHub Release 获取真实五组件文件名、大小和摘要，再生成对应 `github.davisye.cn/_github/` 外链。不能假设附件永远叫 `0.1.0.jar`；例如 fork 使用过 `0.1.0-nightly.jar`。`release`、`rc`、`beta`、`alpha` 归档及其他目录不由这个任务修改。

每个来源分别验证：发布未完成、资产缺失或某一源不可用时保留该源上一份完整目录，另一源通过验证仍可更新。没有新的可用快照时不清空已有目录。GitHub 会替换 nightly 附件，定时检查之间及替换进行中的窗口仍可能出现 404，不能承诺零中断。

官方元数据核对与有限 Range 探测只证明本次目录信息和下载路径可用；**Range 不是整个 JAR 的 SHA256 验证**。需要完整内容验证的安装器仍应下载后检查官方摘要。外链目录也不提供长期二进制归档保证。

## 运行边界

- Python 3 标准库；服务器已有 `/usr/bin/python3`，部署前检查实际版本和脚本支持范围。
- 专用账号 `folkways-sync`，无交互登录；程序目录 `/opt/folkways-openlist-sync` 由 root 持有、服务账号不可写。
- 持久状态及备份位于 `/var/lib/folkways-openlist-sync`，运行目录位于 `/run/folkways-openlist-sync`，均为专用账号的 `0700` 目录；状态、锁及备份文件为 `0600`。不要将状态目录设为 root 独占、导致服务账号无法写入。
- root 将获批使用的令牌置于 `/etc/folkways-openlist-sync/openlist-token`，目录 `0700`、文件 `0600`。令牌不写入仓库、命令参数、环境变量或日志。
- **这是 OpenList 管理令牌，本身具有管理权限，并非仅限 id=5 的凭据。** id、挂载、驱动、变更范围校验是脚本的应用层限制；凭据失窃后不能靠这些校验限制其他程序。
- 不新增监听端口，不修改容器、OpenList 服务、Worker、DNS 或防火墙。只需要访问既有 loopback API、DNS 和外部 HTTPS。

服务使用 `LoadCredential` 将 root 文件交给专用进程，`ProtectSystem=strict` 下仅专用状态/运行目录可写。systemd 自动管理这些目录，凭据在服务运行时位于 `/run/credentials/folkways-nightly-sync.service/openlist-token`，不能把该临时路径当成始终存在的普通文件。[systemd 257 执行环境与凭据](https://www.freedesktop.org/software/systemd/man/257/systemd.exec.html)

## 部署与首次验收

以下由获授权的管理员在目标主机执行。先审核脚本与单元，确认同一目录不存在其他 cron、面板任务或 timer。只启用这一套 timer。

1. 创建专用系统账号 `folkways-sync` 及同名组；无登录 shell，不加入 Docker 或其他管理组。安装已审核脚本为 `/opt/folkways-openlist-sync/sync_openlist.py`，root 所有、普通用户只读。将本仓库两个单元安装到 `/etc/systemd/system/`，权限 `0644`。
2. 在 root 私有目录安全写入获批管理令牌，设为 root 所有、`0600`；不要通过 `echo <token>`、命令参数或提交文件传递它。已有令牌文件应备份并按实际需要轮换。
3. 检查单元，但暂不启用 timer：

```sh
sudo systemd-analyze verify /etc/systemd/system/folkways-nightly-sync.service /etc/systemd/system/folkways-nightly-sync.timer
```

先用相同服务沙箱执行 dry-run。创建临时覆盖文件 `/run/systemd/system/folkways-nightly-sync.service.d/10-dry-run.conf`，内容如下；`ExecStart=` 空行用于清除原命令：

```ini
[Service]
ExecStart=
ExecStart=/usr/bin/python3 /opt/folkways-openlist-sync/sync_openlist.py --state-dir /var/lib/folkways-openlist-sync --token-file /run/credentials/folkways-nightly-sync.service/openlist-token
```

```sh
sudo systemctl daemon-reload
sudo systemctl show folkways-nightly-sync.service -p ExecStart
sudo systemctl start folkways-nightly-sync.service
sudo journalctl -u folkways-nightly-sync.service -n 100 --no-pager
```

dry-run 仍会读取管理 API 与探测外链，只是不写 OpenList；并非完全离线。核对计划仅涉及两个 nightly 分支、归档无变化，且备份/状态目录归属正确。不要将 dry-run 退出成功当成写入验收。

获准执行后，仅移除上述 `10-dry-run.conf` 覆盖文件，重新加载并确认 `ExecStart` 包含 `--execute`。先手动运行一次 service，检查写后读回及访客目录：

```sh
sudo systemctl daemon-reload
sudo systemctl show folkways-nightly-sync.service -p ExecStart
sudo systemctl start folkways-nightly-sync.service
sudo journalctl -u folkways-nightly-sync.service -n 100 --no-pager
```

验收包括两源各五文件的实际名称与大小、OpenList 返回的下载链接、重定向目标及有限 Range 的状态和字节。使用 API 实际给出的鉴权/签名链接，不能猜无签名 `/d/` URL。保留写前备份与写后读回证据；确认其他存储、归档和服务未受影响后才启用：

```sh
sudo systemctl enable --now folkways-nightly-sync.timer
sudo systemctl list-timers --all folkways-nightly-sync.timer
```

## 调度、状态与暂停

timer 在开机两分钟后触发，此后每次 service 完成后十分钟再触发，附加最多三十秒随机延迟与一秒调度精度。若启用 timer 时开机已超过两分钟，第一次可能立即触发。`oneshot` 不设置 `RemainAfterExit`；同一个 service 正在运行时，timer 不会另外启动一份。[systemd 257 定时器语义](https://www.freedesktop.org/software/systemd/man/257/systemd.timer.html)

不使用另一套 cron/面板调度，也不设置对该单调定时器无效的 `Persistent=true`。脚本在状态目录加非阻塞进程锁，防止手工执行与 timer 并发。service 自身不循环重试；失败由日志与后续十分钟调度处理。GitHub 限流截止时间保存在 `githubBackoffUntil`，截止前跳过请求，避免每个 timer 周期重复撞限流。

```sh
sudo systemctl status folkways-nightly-sync.timer --no-pager
sudo systemctl show folkways-nightly-sync.service -p Result -p ExecMainStatus
sudo journalctl -u folkways-nightly-sync.service --since today --no-pager
sudo -u folkways-sync /usr/bin/python3 /opt/folkways-openlist-sync/sync_openlist.py --state-dir /var/lib/folkways-openlist-sync --status
```

`--status` 只读取本地 `state.json`，不需要令牌、不访问管理 API。`lastRun`、`lastResults` 和各来源的 commit、sequence、fingerprint 是本地审计信息，并非即时远端健康探测。退出码 `0` 表示两源均被接受；`1` 也可能是一源已成功更新、另一源保留旧目录，应结合 JSON 中的 `acceptedSources`、`sources` 和写后读回来判断，不能单凭 failed 就认定没有写入。

暂停后续自动运行：

```sh
sudo systemctl disable --now folkways-nightly-sync.timer
```

这不会强制中断已经开始的同步。正常维护应等待当前 oneshot 完成，再处理状态或恢复，避免同时运行手工进程。保留 `/var/lib/folkways-openlist-sync` 及备份，不用删除状态的方法绕过未完成事务。令牌轮换后由下一次 service 启动重新加载，无需重启 OpenList。

`state.json` 保存已接受快照；`backups/storage-5-*.json` 保存修改前配置，成功运行或完整恢复后保留最近五份；未完成事务的备份会保留；`journal.json` 记录尚未完成的变更。下次 `--execute` 会先处理该 journal，再开始新同步；dry-run 遇到未完成事务会明确失败，不自行恢复。暂停 timer、确认当前 service 已结束后，可手动启动原 service 完成这一步；若返回 `recovery-blocked-by-manual-change`，需人工审核，不能删除 journal 或覆盖人工修改来强行继续。

OpenList 管理 API 没有提供 compare-and-swap 前提条件。脚本写前重读只能缩小竞争窗口，不能承诺与其他管理员并发修改绝对隔离；同步和恢复时不要同时手工编辑这份 UrlTree。已完成发布的历史备份回滚需单独审核实际配置与指定备份，当前 CLI 不提供任意备份文件直接写回的参数。不要直接改 SQLite、重置令牌、改签名/访客权限或重建整棵目录作为恢复手段。
