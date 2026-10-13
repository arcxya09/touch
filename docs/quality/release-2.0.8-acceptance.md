# 2.0.8 全项目 review 修复与精简

2026-10-13，使用者在审阅 [PR #1](https://github.com/arcxya09/touch/pull/1) 后明确要求“可以合并，然后发布release”。本次沿用 2.0.7 的正式发布决定；国内真机专项、真实历史正式签名包覆盖升级及发布构建性能的完整验收记录仍未齐全，不填报为通过。

版本为 2.0.8 / versionCode 24。PR #1 的 CI 在合并前全部通过：服务端 SQLite 测试（124 项通过、1 项跳过）与 PostgreSQL 测试、Ruff、Android 构建、单元测试及 Lint，以及 API 29、31、33、36、37.0 设备矩阵（含新增的 `MessageDeliveryInstrumentedTest.syncRedeliversUnconfirmedSendsButLeavesRejectionsToTheUser`）。发布标签仍由 Release 工作流对精确提交重新运行设备矩阵、服务端检查、签名指纹、APK 身份与附件摘要校验，结果以对应工作流和 Release 记录为准。

Room schema、本地加密数据格式和网络协议不变；服务端不新增迁移。`can_recall`、`capabilities` 字段与 `mobile_sessions.supports_recall` 列仅为兼容旧客户端保留。互相申请自动通过与停用/恢复账号通知联系人两项需要部署新版服务端；客户端可与旧服务端共同工作。
