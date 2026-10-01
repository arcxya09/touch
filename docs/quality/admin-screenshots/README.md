# 管理后台本地烟测截图

四张截图来自 2026-10-01 的一次隔离本地 SQLite fixture，使用 Google Chrome 与 Python Playwright 采集。页面仅含 `verify_local_*` 测试账号，没有连接生产服务或使用生产账号。

| 图片 | 场景 | 图片尺寸 |
| --- | --- | --- |
| [login](admin-quality-login.png) | 登录 | 1280 × 900 |
| [desktop](admin-quality-desktop.png) | 桌面账户管理 | 1280 × 2053 |
| [mobile](admin-quality-mobile.png) | 390px 手机宽度账户管理 | 390 × 2740 |
| [mobile-errors](admin-quality-mobile-errors.png) | 手机宽度表单错误反馈 | 390 × 2813 |

检查了桌面/手机无页面横向溢出，错误登录保留账号名，以及重复开户保留非密码输入、清空密码。完整 PNG 高度由页面内容决定，不等于视口高度。采集后还有一处表单标签对齐调整，因此这些是过程审阅证据，并非最终源码逐像素基线。

[metadata.json](metadata.json) 记录来源、采集文件时间、图片尺寸、SHA-256 和未记录信息。这批截图没有经过正式基线批准，不代表国内手机浏览器覆盖，也不能据此宣称所有表单流程已通过。后续固定环境重新采集并审阅后，可使用 [截图比较工具](../screenshot-comparison.md) 检查回归。
