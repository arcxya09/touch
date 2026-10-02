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
5. 上传草稿附件，比较 GitHub 返回的附件大小和 SHA-256，通过后仍保留草稿。`scripts/verify_release.py` 默认只接受草稿，防止误用已公开版本。
6. 经负责人明确同意，可把这份已校验的草稿公开为 **Prerelease**，供手动下载安装验收，保持 Latest 不变。预发布说明必须列明尚未完成的验收；没有实测的国内实体机或真实历史升级不得填写为通过。公开预发布不表示正式验收完成，也不部署生产服务。
7. 使用隔离账号完成真实历史已发布 APK 覆盖升级、签名/包名拒绝、国内实体机通知/后台返回/Doze 及离线恢复验收。按 `ops/release-evidence.example.json` 填写实际结果和可审阅的 HTTPS 证据链接；`releaseTag`、完整提交 SHA 和 APK SHA-256 必须与同一候选一致。
8. 手动运行 **Record independent release validation**，提交公开预发布标签和真实证据 JSON；成功后记录该运行 ID。
9. 手动运行 **Finalize verified candidate**，输入同一标签及 `validation_run_id`。工作流验证证据所属运行、精确 APK 身份、签名、清单、版本递增和目标提交的完整 CI 后，才将同一份候选附件转为稳定版并标记 Latest。

已存在的 Release 不会被覆盖。工作流失败留下草稿时，先排查失败原因和附件；不要直接把不完整草稿设为 Latest。需要修正已经发布的安装包时，发布更高 `versionCode` 的新版本。

Finalize 不重新构建或替换附件。发布失败后仍须提供有效的独立验收运行 ID 才可重试；缺失、失败或身份不匹配的证据不会将候选转为稳定版。证据工作流产物保留 90 天，过期后需重新记录验收证据。

仓库管理员须为 GitHub Environment `release-validation` 配置 required reviewers 和适用的部署分支/标签限制。工作流引用该环境不会自动创建审批人。签名源码 fixture 的覆盖安装脚本 `scripts/android_upgrade_check.ps1` 用于补充验证，输出明确标记未验证真实历史发布包及国内实体机；不能替代上述两个验收项目。

独立验收 `record` job 保持 `contents: read`，读取已公开的预发布候选；[GitHub 草稿列表只向拥有 push 权限的调用者返回](https://docs.github.com/en/rest/releases/releases#list-releases)，因此只读验收任务不能依赖私有草稿。Record 和 Finalize 显式传入 `--allow-prerelease`，允许校验草稿或公开预发布，仍拒绝已公开稳定版；附件大小、SHA-256 和全部正式验收门槛不变。

公开预发布使用单次状态更新，不能先发布为稳定版再改标记：

```sh
gh release edit v2.0.0 --repo arcxya09/touch --verify-tag --prerelease --draft=false --latest=false
```

执行前保存 `/repos/arcxya09/touch/releases/latest` 返回的标签和客户端更新 URL 返回的清单；执行后确认候选 `draft=false`、`prerelease=true`、三份附件大小和摘要不变，且 Latest 标签及客户端清单仍指向原稳定版。默认稳定通道及 2.0.0 旧客户端读取 `https://github.com/arcxya09/touch/releases/latest/download/update.json`；GitHub 的 [Latest 接口排除草稿及预发布](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)。

普通分支和 PR 只运行 CI，不接触签名 Secrets。标签事件只构建经核验的草稿；经明确授权可公开预发布供验收，稳定版及 Latest 仍由独立验收后的 Finalize 执行。仓库管理员应限制有权推送标签的人员。

设备 CI 配置 API 29、31、33、36、37.0 的隔离模拟器，使用本地 fixture 和明确的测试类列表；空结果、失败及跳过都会阻断。37.0 使用 [官方 SDK 仓库](https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-3.xml) 公布的 `system-images;android-37.0;google_apis;x86_64`。矩阵配置和镜像存在不代表各设备测试已经执行成功，以对应提交的工作流记录为准。

## 客户端行为

“关于与更新”的“包含预发布版本”默认关闭，选择在本机持久保存。开启后，手动和自动检查均在稳定清单之外检查仓库最近 100 个 Release 中已公开、带已上传 `update.json` 的预发布；草稿不参与。清单 URL 由固定仓库与合法版本标签构造，不采用外部附件地址。仅从系统兼容且 versionCode 高于已安装版本的清单中选择最大值，同版本优先正式版，不按发布时间降级。预发布提示有明确标记，APK 包名、版本、大小、哈希与签名校验保持一致。切换通道清除旧提示与检查退避；下载期间暂时禁用开关。

应用在允许的前台界面每 24 小时自动检查一次，失败退避；设置中可手动检查。隐私模式必须先解锁，番茄钟界面不发起检查也不弹提示。

下载需要用户明确选择。APK 大小、SHA-256、包名、版本和签名均须一致。安装需系统授权及确认，不支持静默安装，也不会强制升级。安装授权后返回应用仍需经过隐私锁，再选择继续安装。

更新失败、GitHub 不可访问或用户取消不会阻止原有功能。调试版不读取正式更新源。升级保留本地数据，禁止破坏性数据库迁移。
