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
2. 完成服务端测试、Android 单元测试和 Lint，并验证升级保留数据。
3. 提交代码，创建并推送与 `versionName` 对应的 `vX.Y.Z` 标签。
4. Release 工作流构建正式签名 APK，从 APK 读取包名、版本及 minSdk 生成更新清单。
5. 上传草稿附件，比较 GitHub 返回的附件大小和 SHA-256，通过后发布并标记 Latest。

已存在的 Release 不会被覆盖。工作流失败留下草稿时，先排查失败原因和附件；不要直接把不完整草稿设为 Latest。需要修正已经发布的安装包时，发布更高 `versionCode` 的新版本。

如果构建和附件上传已成功，仅最后的发布步骤失败，可在 Actions 手动运行 **Finalize verified draft**，输入现有草稿标签。此流程不替换附件，会重新下载并核对远端摘要、APK 身份、固定签名证书、清单及版本递增后才发布。缺失或不匹配的附件会让流程失败并继续保留草稿。

普通分支和 PR 只运行 CI，不接触签名 Secrets。正式发布在标签事件下执行，仓库管理员应限制有权推送标签的人员。

## 客户端行为

应用在允许的前台界面每 24 小时自动检查一次，失败退避；设置中可手动检查。隐私模式必须先解锁，番茄钟界面不发起检查也不弹提示。

下载需要用户明确选择。APK 大小、SHA-256、包名、版本和签名均须一致。安装需系统授权及确认，不支持静默安装，也不会强制升级。安装授权后返回应用仍需经过隐私锁，再选择继续安装。

更新失败、GitHub 不可访问或用户取消不会阻止原有功能。调试版不读取正式更新源。升级保留本地数据，禁止破坏性数据库迁移。
