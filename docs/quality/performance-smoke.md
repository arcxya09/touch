# 模拟器性能冒烟

手动运行 Actions 中的 **Emulator performance smoke (not device acceptance)**。该工作流使用 JDK 21、SDK 37 和独立 API 36 Google APIs x86_64 模拟器，不使用账号，API 指向本机不可用端口。

依次执行已核对名称的任务：

1. `:app:generateBaselineProfile`，只运行 `BaselineProfiles` 采集类，目标为 nonMinifiedRelease。
2. `:app:assembleBenchmark`，将采集的 profile 用于 benchmark 构建。
3. `:benchmark:connectedBenchmarkBenchmarkAndroidTest`，只运行 `StartupBenchmarks` 和 `ScrollBenchmarks`。

仅此手动工作流设置 `androidx.benchmark.suppressErrors=EMULATOR`。它不被 CI 或公开发布门禁调用。常规构建的 `automaticGenerationDuringBuild=false`，不会自动连接设备；`mergeIntoMain=true` 让生成的 profile 可被后续变体消费。工作流不提交或自动接受生成文件。

产物保留 30 天，包含 profile、benchmark APK、APK SHA-256、测试报告、原始 benchmark JSON、Perfetto trace 和执行阶段元数据。采集或测量失败时仍上传已有产物；请以 `metadata.json` 中的完成状态为准，不能将仓库原有 profile 当作本轮采集成功的证明。工作流成功时要求原始测量数据和非空 profile 均存在。

模拟器数据仅用于验证采集链路和发现明显回归。`physicalDeviceAcceptance` 与 `formalPerformanceGatePassed` 固定为 false；不证明实体机启动或滚动性能达标，不替代国内手机验收。比较性能须另外固定实际设备、系统、构建条件和输入场景，保留完整分布与原始数据。

工作流定义完成不代表它已运行成功。本地采集及正式性能结论以对应执行记录为准。
