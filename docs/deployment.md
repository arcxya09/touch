# 部署与运维

目标域名 `chat.worldofmy.uk`，部署目录 `/opt/touch`。生产使用 Docker Compose，一个应用进程及 PostgreSQL。应用只映射 `127.0.0.1:18080`，数据库不映射公网端口。

## 初始化

1. 拉取仓库到 `/opt/touch`。
2. 将 `ops/.env.example` 复制为 `ops/.env`，设置 URL 安全的随机数据库密码，权限设为 600。
3. 在 `ops/` 执行 `docker compose up -d --build`，查看健康状态。
4. 将 `ops/Caddyfile` 作为独立站点纳入现有 Caddy 配置，先 `caddy validate` 再应用配置。不得覆盖其他站点。若现有全局配置禁用了 admin API，reload 不可用，需要安排一次短暂 Caddy 重启。
5. `docker compose exec app python -m app.cli create-admin`，在终端输入强密码。管理入口 `https://chat.worldofmy.uk/admin`。

首个管理员也可通过 `--password-stdin` 从受保护文件或管道输入密码。不要将密码作为命令行参数或写入 Shell 历史。

## 升级

先备份，获取已审核的源码，再 `docker compose up -d --build app`。容器启动前执行 Alembic 迁移；迁移失败则服务不启动。后续版本保持 `/api/v1` 兼容已发布客户端。

数据库迁移应为前向兼容。回滚应用前确认旧版本能读取当前数据库；恢复旧备份可能丢失备份后的消息，需要明确确认。

## 备份

`ops/backup.sh` 会短暂停止 Touch 应用以取得一致的数据库和附件快照，不停止数据库和其他服务。默认输出 `/var/backups/touch`，保留最近 7 天。备份文件权限受 `umask 077` 保护；建议额外复制到你控制的独立存储。

安装 `touch-backup.service` 和 `touch-backup.timer` 到 systemd 后启用 timer，每天凌晨执行。备份中的历史数据随备份过期才清除。

恢复命令：

```sh
sh /opt/touch/ops/restore.sh /var/backups/touch/<备份目录> --replace-touch-data
```

该操作先保存恢复前快照，再替换 Touch 数据。恢复后撤销所有会话，用户需重新登录。恢复演练应先在独立测试项目进行。

## 监测

- `/health` 检查数据库及可用磁盘；小于 256 MiB 时返回 503。
- `docker compose ps` 查看健康状态，`docker compose logs --tail 100 app` 查看错误。
- `df -h` 检查容量；`systemctl list-timers touch-backup.timer` 及备份目录检查备份是否成功。
- HTTP 访问日志关闭，应用日志不记录正文、Token 或密码。管理审计仅记录开户和账号状态操作。
- 应用和数据库设置内存上限；当前小服务器需要持续关注实际内存与磁盘使用，附件长期保存没有自动总容量配额。
