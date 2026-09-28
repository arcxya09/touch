# API v1

移动端接口前缀 `/api/v1`，JSON 请求；认证使用 `Authorization: Bearer <access_token>`。时间戳为 Unix 秒。没有公开注册接口。

## 账号

- `POST /auth/login`：`username`、`password`，返回 `access_token`、`refresh_token`、`expires_in` 和 `user`。
- `POST /auth/refresh`：`refresh_token`；刷新时轮换两个 Token。客户端必须串行刷新。
- `GET /auth/me`；`POST /auth/logout`。
- `POST /auth/password`：`current_password`、`new_password`；初始账号必须先完成改密。
- `POST /auth/verify-password`：`password`，用于验证隐私设置修改。

401 时，`X-Auth-Reason: expired` 允许尝试刷新；其他 401 应清除本地凭证并重新登录。隐私模式下仍须先经过图案解锁。登录 Token 默认有效 30 分钟，刷新凭证 30 天；新设备登录撤销旧会话。

## 联系人

- `GET /contacts/search?username=<完整账号>`，不提供模糊搜索或用户目录。
- `GET /contacts`，返回联系人及待处理申请。
- `POST /contacts/requests`：`user_id`。
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

- `POST /files?kind=image|file`：multipart 字段 `file`；返回 `id/name/mime/kind/size/sha256`。
- `GET /files/{id}`：鉴权下载；未发送附件只对上传者可见，已发送附件按会话参与及本人清理位置检查。
- 默认图片 20 MiB、文件 100 MiB，图片须能够解码且不超过 4000 万像素；空文件拒绝。
- 未关联消息的附件 24 小时后清理；裸存储路径不公开。

## 更新

公开稳定更新源：`https://github.com/arcxya09/touch/releases/latest/download/update.json`。清单格式以 `scripts/release_manifest.py` 为准。APK 下载绑定 `releaseTag`，不得在清单获取后再取 Latest APK。
