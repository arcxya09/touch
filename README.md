# Touch番茄钟

Android 10+ 私人单聊与番茄钟。账号由管理员创建；隐私模式通过隐藏九宫格图案进入聊天，离开前台立即锁定。

客户端原生 Kotlin / Compose；服务端 FastAPI / PostgreSQL；APK 使用 GitHub Release 发布并在应用内检查更新。

## 功能

- 管理员开户、首次改密、单设备会话、精确账号搜索与好友申请。
- 文字、图片和文件单聊，离线补齐、幂等重试及个人历史清理。
- 图片、PDF、UTF-8 文本预览；其他附件可通过外部应用查看或导出。
- 专注与休息计时、后台结束提醒、隐藏九宫格和系统截图保护。
- 读取本仓库最新正式 Release，用户确认后下载，核对哈希、包名、版本及签名，再交给系统安装。

图片限制 20 MiB，其他文件限制 100 MiB。后台不保证聊天提醒。服务器保存可读取的消息，**不提供端到端加密**。隐私模式保护应用界面，不控制导出文件或外部应用。

## 目录

| 目录 | 内容 |
|---|---|
| `android/` | Android 应用、单元测试和设备测试 |
| `server/` | REST/WebSocket、网页管理后台、迁移和接口测试 |
| `ops/` | Docker Compose、Caddy、备份与恢复 |
| `scripts/` | Release 清单生成与附件核验 |

## 本地服务

需要 Python 3.12。以下命令在 `server/` 中执行：

```sh
python -m venv .venv
. .venv/bin/activate
pip install -r requirements-dev.txt
mkdir -p data
alembic upgrade head
python -m app.cli create-admin
SECURE_COOKIES=false uvicorn app.main:app --reload
```

Windows 使用 `.venv\Scripts\Activate.ps1`，并通过 `$env:SECURE_COOKIES='false'` 设置开发 Cookie。SQLite 仅用于开发和部分测试，生产使用 PostgreSQL。

管理后台：`/admin`；接口文档：`/docs`；健康检查：`/health`。管理员初始密码通过终端安全输入，没有默认密码。

## Android 构建

需要 JDK 21、Android SDK 37.0 和 Build Tools 36.0.0：

```sh
cd android
./gradlew assembleDebug testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest
```

正式 API 地址为 `https://chat.worldofmy.uk`，可在构建前设置 `TOUCH_API_BASE` 覆盖。正式包名 `com.arcxya09.touch`，调试包名附加 `.debug`，调试版本关闭正式更新通道。

Windows 中文目录出现 Gradle 测试进程类加载错误时，使用临时英文盘符映射工作目录后构建，例如 `subst T: <项目完整路径>`；确认该盘符未被使用，构建后移除映射。不要因此跳过测试。

## 部署和发布

参见 [部署说明](docs/deployment.md)、[发布与签名](docs/releases.md)、[接口约定](docs/api.md)、[验证记录](docs/verification.md)。

签名密钥、账号密码及 `.env` 必须单独保管。丢失签名密钥将无法按当前方案对已安装 APK 覆盖升级。

