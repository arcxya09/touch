# APK 签名、Release 和更新

每个正式版本固定发布：`touch.apk`、`update.json`、`SHA256SUMS.txt`。不上传签名密钥、密码、生产 `.env` 或管理员凭证。

## 签名材料

首次生成 RSA 3072 位或更强的长期签名密钥，alias 为 `touch`。同一包名永久使用该密钥。将密钥及恢复所需密码保存在独立的安全备份中。

GitHub Actions Secrets：

- `TOUCH_KEYSTORE_BASE64`：JKS 文件的 Base64 编码；Base64 不是加密，必须作为 Secret 保存。
- `TOUCH_STORE_PASSWORD`：密钥库密码。
- `TOUCH_KEY_PASSWORD`：签名私钥密码。

本地构建使用 `TOUCH_KEYSTORE`、`TOUCH_STORE_PASSWORD`、`TOUCH_KEY_PASSWORD`、`TOUCH_KEY_ALIAS` 环境变量。未提供签名材料时不会假装生成正式签名包。

公钥证书指纹保存在 `signing-certificate.sha256`，发布时额外核对，防止错误更换 Secrets 后发布不同签名的 APK。该指纹不是私钥，可公开。

## 发布流程

1. 修改根目录 `version.properties` 和 `CHANGELOG.md`，严格递增 `versionCode`。
2. 完成服务端 SQLite/PostgreSQL 测试、Android 单元测试、Lint 和设备 CI；目标提交必须有成功的完整 CI 记录。
3. 提交代码，创建并推送与 `versionName` 对应的 `vX.Y.Z` 标签。
4. Release 工作流构建正式签名 APK，从 APK 读取包名、版本及 minSdk 生成更新清单。
5. 上传草稿附件，比较 GitHub 返回的附件大小和 SHA-256，通过后仍保留草稿，供独立验收下载同一份 APK。
6. 使用隔离账号完成真实历史已发布 APK 覆盖升级、签名/包名拒绝、国内实体机通知/后台返回/Doze 及离线恢复验收。按 `ops/release-evidence.example.json` 填写实际结果和可审阅的 HTTPS 证据链接；`releaseTag`、完整提交 SHA 和 APK SHA-256 必须与草稿一致。
7. 手动运行 **Record independent release validation**，提交标签和证据 JSON；成功后记录该运行 ID。
8. 手动运行 **Finalize verified draft**，输入同一标签及 `validation_run_id`。工作流验证证据所属运行、精确 APK 身份、签名、清单、版本递增和目标提交的完整 CI 后，才发布并标记 Latest。

已存在的 Release 不会被覆盖。工作流失败留下草稿时，先排查失败原因和附件；不要直接把不完整草稿设为 Latest。需要修正已经发布的安装包时，发布更高 `versionCode` 的新版本。

Finalize 不重新构建或替换附件。发布失败后仍须提供有效的独立验收运行 ID 才可重试；缺失、失败或身份不匹配的证据会保留草稿。证据工作流产物保留 90 天，过期后需重新记录验收证据。

仓库管理员须为 GitHub Environment `release-validation` 配置 required reviewers 和适用的部署分支/标签限制。工作流引用该环境不会自动创建审批人。签名源码 fixture 的覆盖安装脚本 `scripts/android_upgrade_check.ps1` 用于补充验证，输出明确标记未验证真实历史发布包及国内实体机；不能替代上述两个验收项目。

普通分支和 PR 只运行 CI，不接触签名 Secrets。标签事件只构建经核验的草稿；正式公开发布由独立验收后的 Finalize 执行。仓库管理员应限制有权推送标签的人员。

设备 CI 配置 API 29、31、33、36、37.0 的隔离模拟器，使用本地 fixture 和明确的测试类列表；空结果、失败及跳过都会阻断。37.0 使用 [官方 SDK 仓库](https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-3.xml) 公布的 `system-images;android-37.0;google_apis;x86_64`。矩阵配置和镜像存在不代表各设备测试已经执行成功，以对应提交的工作流记录为准。

## 客户端行为

应用在允许的前台界面每 24 小时自动检查一次，失败退避；设置中可手动检查。隐私模式必须先解锁，番茄钟界面不发起检查也不弹提示。

下载需要用户明确选择。APK 大小、SHA-256、包名、版本和签名均须一致。安装需系统授权及确认，不支持静默安装，也不会强制升级。安装授权后返回应用仍需经过隐私锁，再选择继续安装。

更新失败、GitHub 不可访问或用户取消不会阻止原有功能。调试版不读取正式更新源。升级保留本地数据，禁止破坏性数据库迁移。
