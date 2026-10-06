# 部署与运维

目标域名 `chat.worldofmy.uk`，部署目录 `/opt/touch`。生产使用 Docker Compose，一个应用进程及 PostgreSQL。应用只映射 `127.0.0.1:18080`，数据库不映射公网端口。

## 初始化

1. 拉取仓库到 `/opt/touch`。
2. 将 `ops/.env.example` 复制为 `ops/.env`，设置 URL 安全的随机数据库密码，权限设为 600。
3. 在 `ops/` 执行 `docker compose up -d --build`，查看健康状态。
4. 将 `ops/Caddyfile` 作为独立站点纳入现有 Caddy 配置，先 `caddy validate` 再应用配置。不得覆盖其他站点。若现有全局配置禁用了 admin API，reload 不可用，需要安排一次短暂 Caddy 重启。
5. `docker compose exec app python -m app.cli create-admin`，在终端输入强密码。管理入口 `https://chat.worldofmy.uk/admin`。

首个管理员也可通过 `--password-stdin` 从受保护文件或管道输入密码。不要将密码作为命令行参数或写入 Shell 历史。

Compose 的应用构建上下文是仓库根目录，Dockerfile 为 `server/Dockerfile`，以便把根目录 `version.properties` 一起打包。手动构建须在仓库根执行 `docker build -f server/Dockerfile .`，不能把 `server/` 当作上下文。`server/Dockerfile.dockerignore` 仅放行服务端源码和版本文件，排除数据、缓存及 `.env`。可设置 `TOUCH_BUILD` 为部署提交 SHA；`TOUCH_VERSION` 仅用于显式覆盖版本，默认读取打包的版本文件。

## 升级

先备份，获取已审核的源码，再 `docker compose up -d --build app`。容器启动前执行 Alembic 迁移；迁移失败则服务不启动。当前会话能力迁移为 `a731c02e9714`，旧客户端正常收发保持兼容；遇到旧端不能表达的既有撤回状态时按 [消息动作协议](message-actions.md) 返回明确的 409 更新提示。

数据库迁移应为前向兼容。回滚应用前确认旧版本能读取当前数据库；恢复旧备份可能丢失备份后的消息，需要明确确认。

发布客户端前必须确认生产服务端实际升级。构建时设置 `TOUCH_BUILD` 为完整提交 SHA，随后执行 `python ops/verify_deployment.py --url https://chat.worldofmy.uk --version <版本号> --build <完整提交 SHA>`；仅 HTTP 200 不代表协议已部署。2.0.3 修复的撤回缺失正是旧服务端没有撤回接口及 `can_recall` 字段造成的。迁移保留原登录状态，双方新版客户端下次 `/auth/me` 同步会登记能力，无需强制退出重登；旧端仍按兼容协议限制撤回。

## 备份

`ops/backup.sh` 会短暂停止 Touch 应用以取得一致的数据库和附件快照，不停止数据库和其他服务。默认输出 `/var/backups/touch`，保留最近 7 天。备份文件权限受 `umask 077` 保护；建议额外复制到你控制的独立存储。

新备份先写入隐藏的 `.partial` 目录，完成数据库和附件校验清单 `files.sha256` 后写入 `COMPLETE` 标记，再原子重命名为正式快照。恢复应用成功后更新 `/data/backup-success.txt`；失败不会把未完成快照报告为成功。

安装 `touch-backup.service` 和 `touch-backup.timer` 到 systemd 后启用 timer，每天凌晨执行。备份中的历史数据随备份过期才清除。

恢复命令：

```sh
sh /opt/touch/ops/restore.sh /var/backups/touch/<备份目录> --replace-touch-data
```

该操作先检查数据库转储和可用的附件校验清单，再保存恢复前快照并替换 Touch 数据。恢复后撤销所有会话，用户需重新登录；替换阶段失败会让应用保持停止，须排查恢复状态后再启动。

可先执行 `sh /opt/touch/ops/verify-restore.sh /var/backups/touch/<备份目录> --isolated`。脚本在唯一命名、无网络的临时 PostgreSQL 17 容器中恢复数据库，核对数据库引用的每个附件大小与 SHA-256，完成或失败后只移除该临时容器，不连接生产数据库。需要本机 Docker；脚本存在或语法通过不等于恢复演练已执行。

## 监测

- `/health` 返回实际打包版本及构建标识，并检查数据库和可用磁盘；数据库不可用或空闲空间小于 256 MiB 时返回 503。外部探针应检查该地址及 JSON `status`，而非根路径。
- `docker compose ps` 查看健康状态，`docker compose logs --tail 100 app` 查看错误。
- `df -h` 检查容量；`systemctl list-timers touch-backup.timer` 及备份目录检查备份是否成功。管理后台显示备份成功时间，超过 36 小时标为过期；维护任务状态仅覆盖当前应用进程，重启后重新开始记录。
- HTTP 访问日志关闭，应用日志不记录正文、Token 或密码。管理审计仅记录开户和账号状态操作。
- 应用和数据库设置内存上限；当前小服务器需要持续关注实际内存与磁盘使用，附件长期保存没有自动总容量配额。
- API 附件/头像及后台头像上传均在 multipart 读取前鉴权，检查请求上限和可用空间。每进程默认只允许两个上传，满载返回 429；磁盘不足返回 503。未知长度流也受字节数限制，上传过程中持续检查空间。
