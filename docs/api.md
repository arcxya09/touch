# API v1

移动端接口前缀 `/api/v1`，JSON 请求；认证使用 `Authorization: Bearer <access_token>`。时间戳为 Unix 秒。没有公开注册接口。

## 账号

- `POST /auth/login`：`username`、`password`，返回 `access_token`、`refresh_token`、`expires_in` 和 `user`。
- `POST /auth/refresh`：`refresh_token`；刷新时轮换两个 Token。客户端必须串行刷新。
- `GET /auth/me`；`POST /auth/logout`。
- `POST /auth/password`：`current_password`、`new_password`；初始账号必须先完成改密。
- `POST /auth/verify-password`：`password`，用于验证隐私设置修改。

401 时，`X-Auth-Reason: expired` 允许尝试刷新；其他 401 应清除本地凭证并重新登录。隐私模式下仍须先经过图案解锁。登录 Token 默认有效 30 分钟，刷新凭证 30 天；新设备登录撤销旧会话。

## 个人资料与已读设置（1.0.3 起）

- `PATCH /auth/profile`：`display_name`（1–64 字，不能全空格）、`bio`（最多 160 字）。只编辑自己，拒绝额外字段，不允许更改角色或账号名。
- `POST /auth/avatar`：multipart 字段 `file`，图片最多 5 MiB、2000 万像素；服务端转为 512×512 JPEG 并移除元数据。`DELETE /auth/avatar` 移除头像。
- `GET /profiles/{user_id}/avatar/{avatar_version}`：须登录且已完成首次改密，返回当前头像；旧版本地址返回 404，响应禁止缓存。用户 JSON 含 `bio`、可空 `avatar_version`。
- `PATCH /auth/preferences`：`read_receipts_enabled` 布尔值，仅管理员可设置；普通用户返回 403。默认 false，只有自己的登录/刷新/资料响应返回此偏好。
- `/conversations` 仅在请求者为管理员且开关开启时返回 `peer_read_seq`。普通用户及关闭开关的管理员均不返回此字段。`read` 同步提示不携带对方位置，客户端重新读取会话接口，避免关闭后从积压事件泄漏位置。
- 后台 `/admin/profile` 编辑本人资料；`GET/POST /admin/users/{id}/profile` 管理用户昵称、简介和头像（不修改权限）；POST 须管理员会话和 CSRF。头像由鉴权的 `/admin/users/{id}/avatar` 返回。

## 联系人

- `GET /contacts/search?username=<完整账号>`，不提供模糊搜索或用户目录。
- `GET /contacts`，返回联系人及待处理申请。
- `POST /contacts/requests`：`user_id`。
- 管理员主动添加时返回 `state=accepted` 并立即建立会话；普通用户返回 `pending`，包括普通用户向管理员发起申请。权限只取自服务端账号，客户端不能通过提交角色字段绕过确认。
- `POST /contacts/{peer_id}`：`action` 为 `accept`、`reject`、`remove`；只有申请接收方能接受或拒绝。

## 消息

- `GET /conversations`：会话摘要、未读数、本人清理位置及发送权限。
- `GET /conversations/{id}/messages?before=<seq>&limit=50`：返回正序消息及 `has_more`；`before` 为不包含该位置的游标。
- `POST /conversations/{id}/messages`：`client_id`（UUID）、`kind`（text/image/file）、`text`、可选 `attachment_id`。
- `POST /conversations/{id}/read`：`seq`；本人已读位置只前进。
- `POST /conversations/{id}/clear`：将本人清理位置推进到当前最新消息。对方仍能读取；双方清理后物理回收消息。

重试须复用原 `client_id` 和原请求体；同一个 ID 携带不同内容返回 409。附件仅可由上传者关联一次。附件上传完成不等于消息发送完成。

## 同步

`GET /sync?cursor=0&limit=100` 返回 `events`、`cursor`、`has_more`。事件包含账号独立递增的 `seq`、`kind`、`payload`。消息事件携带可见消息；已被清理的历史仅返回 `noop`，不会通过旧事件复活。

客户端须将事件和新游标在同一 Room 事务内落库。`clear` 清理本人本地消息；联系人等变更重新获取摘要。`/ws` 使用同样的 Authorization 请求头，仅提示 `{"type":"sync","cursor":...}`，实际消息仍通过增量接口补齐。断线后必须主动补齐，不能只等待 WebSocket 提示。

## 文件

### 本机保留时间筛选（1.0.2 起）

`GET /conversations`、`GET /conversations/{id}/messages`、`GET /sync`、`GET /files/{id}` 均接受可选 `after_time`（非负 Unix 秒，默认 0）。只返回 `created_at > after_time` 的消息及关联附件。摘要的最近消息和未读数也按此筛选；过期同步事件返回空 `noop` 并正常推进游标，历史分页的 `has_more` 只计算保留期内记录。未发送附件按上传时间筛选。

该参数只限制当前请求返回的数据，不更新账号清理位置、不删除服务器数据，也不影响其他设备/账号。旧客户端省略参数时行为保持不变。Android 在加密的本机配置中保存每个账号只前进的截止时间，所有网络读取均携带截止时间，并在本地再次检查，防止请求期间到期的数据落库。延长时长、时钟回拨及同一安装中的重新登录均不会倒退截止时间；清除应用数据或重装会重置本机策略。

1.0.3 起，定时销毁默认关闭，每次开启默认 1 小时。关闭时不推进截止时间，但继续携带此前已持久化的截止线，以防恢复已经销毁的记录。schema 1 的本地策略迁移为 schema 2，默认关闭并保留各账号截止线。

这与 `POST /conversations/{id}/clear` 不同：后者是用户主动清空本人可见的服务器历史。本机定时销毁不会调用它。

### 传输

- `POST /files?kind=image|file`：multipart 字段 `file`；返回 `id/name/mime/kind/size/sha256`。
- `GET /files/{id}`：鉴权下载；未发送附件只对上传者可见，已发送附件按会话参与及本人清理位置检查。
- 默认图片 20 MiB、文件 100 MiB，图片须能够解码且不超过 4000 万像素；空文件拒绝。
- 未关联消息的附件 24 小时后清理；裸存储路径不公开。

## 管理后台

- `POST /admin/users`：表单 `username`、`display_name`、`password`、`csrf`、`role`（`user` 默认普通用户或 `admin` 管理员）。仅已改密的管理员可创建账号。
- 新建管理员首次网页登录跳转 `/admin/password`；修改临时密码前无法访问账号管理操作。普通用户不能登录管理后台。
- `GET /admin/users/{id}/delete` 只显示确认页；`POST` 同路径要求管理员会话、CSRF 和与原账号名完全一致的 `confirmation`。管理员账号不可删除。
- 删除账号会匿名化账号身份、清除凭据、撤销会话、移除联系人、推进被删除账号的历史清理位置，并通知其他联系人同步；原账号名可复用，但新账号获得新 ID，不能读取旧账号的数据。
- 为保留对方可见历史，数据库保留匿名身份及仍被历史引用的消息/附件。无引用附件由维护任务清理；已有备份按 7 天保留期到期。删除不能通过“恢复”操作撤销。
- 已发布 `/api/v1` 客户端继续兼容。用户 JSON 新增可选 `is_admin` 字段；已删除的会话对方显示为“已删除账号”，不可继续发送。

## 更新

公开稳定更新源：`https://github.com/arcxya09/touch/releases/latest/download/update.json`。清单格式以 `scripts/release_manifest.py` 为准。APK 下载绑定 `releaseTag`，不得在清单获取后再取 Latest APK。
