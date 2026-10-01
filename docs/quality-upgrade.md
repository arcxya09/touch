# 品质升级交付与验收

本轮保持“番茄钟外层、可选隐私聊天”的产品定位，完善既有体验与数据边界。本文对应当前工作区实现；代码已接入不等于设备验收完成，也不代表已部署或发布。

候选版本定位为 **Touch 2.0**：`versionName=2.0.0`、`versionCode=16`，发布标签为 `v2.0.0`。本文保留本地交付时的验证记录；GitHub 发布流程以同一版本的源码构建独立签名候选包，调试包证据不能替代正式签名覆盖升级和真机验收。

本地评审用 [Touch 2.0 调试体验包](../dist/touch-2.0-debug.apk)已生成：包名 `com.arcxya09.touch.debug`、版本显示 `2.0.0-debug`，可与正式版并存，使用默认服务地址。它由隔离调试密钥签名，不用于正式版覆盖安装。设备功能测试使用另存的 loopback 调试包；体验包只核对构建、版本、证书与默认地址，没有连接生产后端做测试。APK 哈希、各轮原始结果和去重后的用例清单见[最终验证摘要](quality/evidence/verification-v2-summary.json)。这些本地 APK 位于忽略的 `dist/`，不随 Git 保存。

## 实现范围与维护入口

| 范围 | 本轮变化 | 主要入口 |
| --- | --- | --- |
| 页面与任务状态 | `Screen` 统一导航与返回；按 Session、Conversation、Attachment 等操作独立显示工作状态；会话读取使用账号、会话 ID、访问代次拒绝迟到结果 | [AppState.kt](../android/app/src/main/java/com/arcxya09/touch/AppState.kt)、[AppViewModel.kt](../android/app/src/main/java/com/arcxya09/touch/AppViewModel.kt)、[ConversationReader.kt](../android/app/src/main/java/com/arcxya09/touch/ConversationReader.kt) |
| 异步交互 | A→B→A 切换、草稿恢复、发送完成、搜索、引用高亮和附件预览均检查结果归属；取消单次传输不关闭其他请求；进度按 100ms 合并，开始与完成立即更新 | [Api.kt](../android/app/src/main/java/com/arcxya09/touch/data/Api.kt)、[真实 VM 竞态测试](../android/app/src/androidTest/java/com/arcxya09/touch/ViewModelConversationIsolationTest.kt) |
| 会话恢复 | 凭据读取失败保留本机内容与待发项，封闭旧登录态；经原有隐私门控后以原账号重新登录恢复。完整隐私配置或密钥损坏仍保持封闭，不提供绕过图案的入口 | [Repository.kt](../android/app/src/main/java/com/arcxya09/touch/data/Repository.kt)、[SecureSessionStore.kt](../android/app/src/main/java/com/arcxya09/touch/data/SecureSessionStore.kt)、[DataBoundaryTest.kt](../android/app/src/androidTest/java/com/arcxya09/touch/DataBoundaryTest.kt) |
| 导出与升级 | 加密保存导出请求身份和目标 URI；重建后处理未完成文件；复制失败清理目标并反馈。APK 哈希、身份、版本和证书检查放在后台，系统安装仍需用户确认 | [ExportCoordinator.kt](../android/app/src/main/java/com/arcxya09/touch/ExportCoordinator.kt)、[PendingExport.kt](../android/app/src/main/java/com/arcxya09/touch/PendingExport.kt)、[Updater.kt](../android/app/src/main/java/com/arcxya09/touch/update/Updater.kt) |
| 计时 | 时钟、存储、提醒接口与规则拆开；兼容原偏好键，处理重启、旧闹钟与到期重复事件；精确提醒权限变化时降级调度，错误只显示计时文案 | [TimerEngine.kt](../android/app/src/main/java/com/arcxya09/touch/timer/TimerEngine.kt)、[Pomodoro.kt](../android/app/src/main/java/com/arcxya09/touch/timer/Pomodoro.kt) |
| 服务端与运维 | 改密统一撤销旧会话；上传解析前鉴权、限制体积和并发并检查空间；管理员表单和错误页完善；维护与备份状态可诊断，增加隔离恢复检查 | [passwords.py](../server/app/passwords.py)、[uploads.py](../server/app/uploads.py)、[operations.py](../server/app/operations.py)、[verify-restore.sh](../ops/verify-restore.sh) |

AppViewModel 仍协调现有功能，没有更换整个应用框架。新增读取边界用于测试真实页面竞态；写入事务、权限和保留策略仍由 Repository 负责。

## 数据库 v3 与兼容协议

[数据库迁移](../android/app/src/main/java/com/arcxya09/touch/data/EncryptedDatabase.kt)保留 `1→2→3` 路径，禁止破坏性重建；[Room schema](../android/app/schemas/com.arcxya09.touch.data.TouchDatabase/3.json)随代码保存。

| 路径 | 迁移与保持条件 |
| --- | --- |
| 明文旧库 → 加密库 | 使用独立临时副本，核对版本、完整性和已有表行数后替换；校验失败保留原文件。 |
| v1 → v2 | 为 `message`、`attachment`、`draft` 回填 `conversationId`、`seq`、`createdAt`、`attachmentId` 并建立索引；迁移测试包含旧草稿，断言时间与会话字段恢复。 |
| v2 → v3 | 新增 `visibility_marks`、`local_cutoffs`、`file_deletions`；旧本机删除标记和清理线只迁移给原缓存 owner。 |
| 删除与附件清理 | 本机可见性规则按账号独立保存，缓存清空或更换账号不复活旧消息；文件删除记录先持久化，失败可重试。 |
| 定时销毁 | 读取即时遵守单调截止线；物理清理按到期时间调度，最多 60 秒维护兜底，失败另行重试；进入后台保存截止线。关闭或延长保留期不恢复已销毁内容。 |

对应回归入口：[ReplyStorageTest 的真实 v1 迁移与草稿回填](../android/app/src/androidTest/java/com/arcxya09/touch/ReplyStorageTest.kt)、[VisibilityMigrationTest](../android/app/src/androidTest/java/com/arcxya09/touch/VisibilityMigrationTest.kt)、[LocalMessageActionsTest](../android/app/src/androidTest/java/com/arcxya09/touch/LocalMessageActionsTest.kt)、[RetentionPolicy](../android/app/src/main/java/com/arcxya09/touch/data/RetentionPolicy.kt)、[AttachmentCleanup](../android/app/src/main/java/com/arcxya09/touch/data/AttachmentCleanup.kt)。这些程序测试不能替代真实历史正式 APK 的覆盖安装。

客户端声明 `X-Touch-Capabilities: recall-v1`；服务端在有效移动会话中记录能力，只有双方当前会话均支持时开放撤回。旧客户端遇到不能解释的撤回状态时收到 409 更新提示，避免错误呈现或跳过事件。[协议实现](../server/app/capabilities.py)与[服务端迁移 a731c02e9714](../server/migrations/versions/a731c02e9714_session_capabilities.py)须一起部署；应先在隔离环境迁移服务端、验证新旧客户端组合，再放行客户端升级。部署与新旧正式客户端混用验收：**未执行**。

## 设计系统与用户入口

[Theme.kt](../android/app/src/main/java/com/arcxya09/touch/ui/Theme.kt)统一暖色浅底、森林绿强调色及对应深色语义色，同时定义中文字号、行高和圆角。[Components.kt](../android/app/src/main/java/com/arcxya09/touch/ui/Components.kt)统一页面、表单、设置行、加载与空态；启动窗口、安全遮罩和系统栏使用对应浅深色资源。

- 番茄钟：保留隐藏解锁交互，适配窄屏、横屏和大字；普通语义只描述计时功能。
- 会话与聊天：区分加载、空列表、断线重试和缓存可读状态；消息、引用、附件、输入区的层级与动作反馈统一。
- 设置：归并为“编辑个人资料”“修改账号密码”“隐私与安全”“本机数据”“通知与后台运行”“关于与更新”；子页和系统返回走同一路径。
- 无障碍：设置行有明确开关语义，表单提示随校验状态出现；使用合成数据的[组件预览](../android/app/src/main/java/com/arcxya09/touch/ui/ComponentPreviews.kt)和[设计测试](../android/app/src/androidTest/java/com/arcxya09/touch/DesignSystemInstrumentedTest.kt)覆盖浅深色与大字布局。TalkBack 真机操作和各厂商显示效果仍需下表验收。

尺寸、语义色、中文排版、触达与组件约定见[设计规范](design-system.md)。仓库已保存 [14 张合成内容截图](quality/screenshots)：三页的浅/深色正常与恢复状态共 12 张，加 320dp 计时页和深色 200% 大字计时页。采集设备、密度、字体比例和截图边界记录在设计规范；截图不包含系统栏或键盘，不能替代整机交互验收。

新增[截图比较工具](../scripts/check_design_screenshots.py)与[比较说明](quality/screenshot-comparison.md)，按文件和尺寸匹配，输出 JSON 与差异图，不自动接受新基线。使用实际新采集目录替换下列候选路径；默认逐像素一致，改变容差需人工决定：

```powershell
python scripts/check_design_screenshots.py --baseline docs/quality/screenshots --candidate artifacts/design-candidate --output artifacts/design-comparison
```

Touch 2.0 最终的[两轮静态稳定性比较](quality/evidence/design-v2-static-stability/comparison.json)与[人工审查后基线比较](quality/evidence/design-v2-accepted-comparison/comparison.json)均为 **14 / 14 通过，零容差、零变化像素**。首次比较的两张小屏图捕获到点击涟漪，旧失败报告保留；改为点击前截图并重复验证后，仅更新这两张基线，理由见[设计文档修正记录](design-system.md#小屏基线修正记录)。

## 性能模块的实际边界

新增独立的 [:benchmark 模块](../android/benchmark/build.gradle.kts)，通过变体生成的 `BuildConfig.TARGET_PACKAGE` 选择被测应用：profile 的 `nonMinifiedRelease` 目标为 `com.arcxya09.touch`，性能测量的 `benchmark` 目标为 `com.arcxya09.touch.benchmark`，不能用测试 runner 的 `targetContext.packageName` 猜测目标。前者采集时使用隔离 loopback 配置，后者 API 固定为本机不可用端口；所有样板使用合成内容，不需账号。

[TimerBenchmarks.kt](../android/benchmark/src/main/java/com/arcxya09/touch/benchmark/TimerBenchmarks.kt)包含两个 Baseline Profile 场景：计时页启动与开始/暂停/重置，以及合成会话列表和聊天滚动；另有 `StartupBenchmarks` 的 20 次冷启动与 `ScrollBenchmarks` 的 10 次帧耗时采样。样板 Activity 仅进入 benchmark / nonMinifiedRelease，正式 release 不包含该入口；profile 排除样板 Activity 自身，复用生产组件路径。

存储规模测量的 Touch 2.0 [原始 JSON](quality/evidence/storage-v2-api36-debug.json)：API 36、`sdk_gphone64_x86_64` 模拟器、debug 构建，50,000 条合成消息、每页 50 条、100 次 Repository 分页读取，P95 为 **29.368061ms**；清空本机记录耗时 **920.608868ms（约 920.6ms）**。测试入口为 [StoragePerformanceTest](../android/app/src/androidTest/java/com/arcxya09/touch/StoragePerformanceTest.kt)。这是本次条件下的实测记录，JSON 明确标记 `formalPerformanceGatePassed=false`，不等于 Release 或实体机达标，也不代表相对旧版的收益。

两个 Baseline Profile 场景已采集成功，见 [API 36 采集 XML](quality/evidence/baseline-collector-api36.xml)：2 项通过、0 跳过。**采集发生在版本锁定前的 1.0.14 候选，覆盖与 Touch 2.0 相同的主要执行路径，并非一次 2.0 采集器运行。** 已保存 [baseline-prof.txt](../android/app/src/main/generated/baselineProfiles/baseline-prof.txt)（2,158,126 字节，约 2.158MB）及 [startup-prof.txt](../android/app/src/main/generated/baselineProfiles/startup-prof.txt)（1,633,559 字节，约 1.634MB）。Touch 2.0 Release 构建已由 R8 消费这些规则，APK 实际含 `assets/dexopt/baseline.prof` 9,808 字节与 `baseline.profm` 622 字节；正式变体不含 `DesignBenchmarkActivity`。

Touch 2.0.0 / code 16 的 **R8 压缩 Benchmark APK** 已完成 20 次冷启动和 10 次滚动采样；[原始 JSON](quality/evidence/performance-v2-api36.json)、[执行日志](quality/evidence/performance-v2-api36.txt)和[绑定 APK SHA-256 的统计摘要](quality/evidence/performance-v2-summary.json)已保存。2 个 JUnit 测试通过只表示测量流程完成。环境为 API 36.1（运行时报告 SDK 36）、x86_64 模拟器、SwiftShader 软件渲染，并显式抑制 `EMULATOR` 检查；30 份 Perfetto trace 保留在本机 `.local/quality-performance-v2`，摘要记录各文件哈希。

| 测量 | 中位数 / P50 | P95 | 统计口径 |
| --- | --- | --- | --- |
| 冷启动首帧 / 完整显示 | 1,293.073ms | 1,494.10929ms | 两项本轮数值相同；20 次运行，P95 用排序后第 `ceil(0.95×20)` 个值，即索引 18。 |
| 滚动帧 CPU 时长 | 37.926977ms | 62.4690135ms | 10 轮、共 8,336 个原始帧样本；保留 AndroidX 报告的分位数。 |
| 滚动帧超时 `frameOverrunMs` | 33.2354785ms | 69.16699325ms | 同上；不套用冷启动的最近秩算法替换工具原始分位数。 |

软件模拟器采样中 **8,193 / 8,336 帧（98.2845%）满足 `frameOverrunMs > 0`**，按全部帧样本汇总而非平均各轮比例。这是软件模拟器上的帧超时观察，不能等同于正式实体机慢帧阈值。CPU P95 约 62.5ms、超时 P95 约 69.2ms 的负面结果保留，不据此宣称“流畅”或“达标”；也没有旧版同条件对照，不能声称性能已提升。

摘要的 `formalPerformanceGatePassed` 与 `physicalDeviceAcceptance` 均为 `false`。**正式 Release 的真机启动/滚动性能验收未执行。** 后续须固定实体机、系统、构建条件及输入场景，保留完整分布和原始 trace，再定位卡顿与评估变化。

## 可复现验证

以下命令从仓库根目录运行。Android 需 Java 21、仓库配置要求的 Android SDK；Python 依赖来自 [requirements-dev.txt](../server/requirements-dev.txt)。只有隔离模拟器连接测试机，不使用生产账号或生产数据。不要无参数执行全部 Android instrument 测试：`Upgrade*` 属于专用流程，缺少参数应失败。

### 构建、JVM 与 Lint

```powershell
.\android\gradlew.bat -p android :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --stacktrace
```

报告位于 `android/app/build/reports/tests/testDebugUnitTest/`、`android/app/build/reports/lint-results-debug.html`。新核心用例见 [AppStateTest](../android/app/src/test/java/com/arcxya09/touch/AppStateTest.kt)及 [TimerEngineTest](../android/app/src/test/java/com/arcxya09/touch/TimerEngineTest.kt)。

### 隔离设备测试

先在终端 A 启动一次性 fixture；目录名必须以 `touch-device-` 开头且没有既有 fixture 数据库：

```powershell
python -m pip install -r server/requirements-dev.txt
$env:TOUCH_TEST_DATA_DIR = Join-Path $env:TEMP ("touch-device-" + [guid]::NewGuid())
python server/tests/device_fixture.py
```

终端 B 构建 loopback 调试包并运行显式用例：

```powershell
$env:TOUCH_API_BASE = 'http://127.0.0.1:8010'
adb devices
adb -s emulator-5554 reverse tcp:8010 tcp:8010
$classes = @(
  'PrivacyInstrumentedTest', 'LocalStorageInstrumentedTest', 'ReplyStorageTest',
  'LocalMessageActionsTest', 'NotificationNavigationTest', 'DataBoundaryTest',
  'VisibilityMigrationTest', 'ExportRecoveryTest', 'ViewModelConversationIsolationTest', 'AttachmentDraftInstrumentedTest',
  'DesignSystemInstrumentedTest'
) | ForEach-Object { "com.arcxya09.touch.$_" }
$classArgument = $classes -join ','
.\android\gradlew.bat -p android :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=$classArgument" "-Pandroid.testInstrumentationRunnerArguments.touchTestUser=verify_local_ci" "-Pandroid.testInstrumentationRunnerArguments.touchTestPassword=test-only-local-123" --stacktrace
python server/tests/check_device_results.py android/app/build/outputs/androidTest-results
```

`emulator-5554` 替换为唯一已连接的隔离模拟器编号。fixture 密码为仓库公开的本地测试数据。XML、HTML 报告分别在 `android/app/build/outputs/androidTest-results/` 与 `android/app/build/reports/androidTests/`；截图按设计测试输出位置采集。CI 的 [device-tests.yml](../.github/workflows/device-tests.yml)已配置 API **29/31/33/36/37.0** 矩阵，并拒绝空报告和跳过用例；这是配置范围，不能写作已全部实跑通过，完整矩阵结果仍待整合。

### 服务端与恢复

```powershell
python -m ruff check server/app server/tests scripts ops
Push-Location server
python -m pytest -q
# 第二轮只连接事先创建的一次性 PostgreSQL 测试库。
$env:TEST_DATABASE_URL = 'postgresql+psycopg://touch:test_only@127.0.0.1:5432/touch_test'
python -m pytest -q
Remove-Item Env:TEST_DATABASE_URL
Pop-Location
```

Linux/Docker 隔离恢复检查：`sh ops/verify-restore.sh /absolute/path/to/test-backup --isolated`。它在无网络的一次性 PostgreSQL 容器中恢复、去除会话并核对附件，不能把生产 `restore.sh` 当成测试命令。真实备份恢复演练：**未执行**。

### 性能采集

任务名已核对：先通过 `:app:generateBaselineProfile` 采集两个 profile 场景，再构建消费规则的 benchmark APK，最后运行冷启动与滚动测试。上述本地采集和软件模拟器测量均已有执行记录，实体机性能验收仍未执行。仅连接专用测试设备；profile 目标与正式包名相同，应使用没有个人数据的隔离环境，并显式设置本地 API：

```powershell
$env:TOUCH_API_BASE = 'http://127.0.0.1:1'
.\android\gradlew.bat -p android :app:generateBaselineProfile --no-daemon --stacktrace
.\android\gradlew.bat -p android :app:assembleBenchmark --no-daemon --stacktrace
.\android\gradlew.bat -p android :benchmark:connectedBenchmarkBenchmarkAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.arcxya09.touch.benchmark.StartupBenchmarks,com.arcxya09.touch.benchmark.ScrollBenchmarks" --no-daemon --stacktrace
```

不要为正式验收添加模拟器错误抑制参数。若为调试流程在模拟器采集，必须单独标记模拟器、构建变体和抑制项，不能提交为实体机性能或国内 OEM 发布证据。切回设备功能测试时恢复 `TOUCH_API_BASE=http://127.0.0.1:8010`；正式构建由发布工作流设置实际 API。

本轮 Windows 本地构建使用 **JDK 21** 和 `--no-daemon`，并以 `subst T:` 将仓库根目录映射为无中文路径后从 `T:\` 执行，避免原生构建工具处理中文工作区路径失败。复现前先核对 T: 是否空闲或已映射到本仓库，不覆盖其他映射；CI 工作区无须这一步。本机隔离调试密钥位于 `.local/android-user`，构建时保持 `ANDROID_USER_HOME` 指向该目录，避免 app 与 runner 误用不同调试证书。本轮最终 runner 曾以同一隔离调试密钥重新签名，APK 哈希见摘要；未动正式密钥。只重放本轮模拟器冒烟时才额外传入 `"-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR"`，并保留这项抑制记录。

独立的[模拟器性能冒烟工作流](../.github/workflows/performance-smoke.yml)及[执行边界说明](quality/performance-smoke.md)支持手动重复这条链路并上传原始产物。工作流定义不代表远端运行成功，也不计入实体机发布验收。

### 同签名源码 fixture 升级

[android_upgrade_check.ps1](../scripts/android_upgrade_check.ps1)依次运行 seed、APK 反例检查、覆盖安装和 verify，要求每阶段实际执行成功，不接受跳过。需准备匹配包名和证书的未混淆旧版本源码 fixture、目标 APK、instrumentation APK、清单，以及错误签名/错误包名 APK；先启动上述一次性 fixture。

```powershell
.\scripts\android_upgrade_check.ps1 -Serial emulator-5554 -BaselineFixtureApk artifacts\baseline-fixture.apk -BaselineSourceRef vX.Y.Z -TargetApk artifacts\touch.apk -TestApk artifacts\release-test.apk -TargetManifest artifacts\update.json -WrongSignerApk artifacts\wrong-signer.apk -WrongPackageApk artifacts\wrong-package.apk -ExpectedVersionCode 123 -OutputDirectory artifacts\upgrade-proof
```

路径、源码 ref、版本号需换成实际输入；SDK 工具不在 PATH 时传 `-Adb`、`-Aapt`（aapt2）、`-ApkSigner` 的完整路径。脚本只接受无既有应用的专用模拟器，并断开设备默认外网后使用 `adb reverse`。输出 `upgrade-proof.json` 明确标记 `signed-source-fixture-upgrade`；**该流程未执行，且即便通过，也不能作为历史正式 APK 覆盖升级或国内真机证据。**

## 发布门禁

当前工作流分三步，不能直接把未经验收的草稿标为 Latest：

1. [release.yml](../.github/workflows/release.yml)：设备矩阵、测试、签名和清单验证后只创建草稿。
2. 完成真实历史正式 APK 覆盖安装、错误包拒绝、国内实体机通知/后台返回/Doze、离线恢复；按 [release-evidence.example.json](../ops/release-evidence.example.json)填入真实设备信息与可访问证据链接。运行 [release-validation.yml](../.github/workflows/release-validation.yml)，验证记录绑定目标提交和 APK SHA-256。
3. [finalize-release.yml](../.github/workflows/finalize-release.yml)要求独立验证 run ID、相同目标 APK/提交及成功的完整 CI，复核后才发布。仓库仍需实际配置 `release-validation` Environment 审核保护；仓库设置配置及远端工作流运行：**未执行/未核实**。

本地可预检证据结构与绑定：

```powershell
$targetCommit = git rev-parse HEAD
python ops/release_evidence.py --evidence artifacts/release-validation.json --manifest artifacts/update.json --apk artifacts/touch.apk --commit $targetCommit
```

校验 JSON 不能替代人工检查证据真实性。正式 APK 的隔离验收需先确定兼容该原件的测试后端连接方案并使用临时测试账号；连接方案未就绪时保持“未执行”，不得以修改 API 地址后重新签名的 APK 充当正式原件。

## 真机验收记录模板

每台设备复制一份：日期 `____`；执行人 `____`；厂商/型号 `____`；Android/系统版本 `____`；APK SHA-256/证书指纹/提交 `____`；旧 APK 来源与哈希 `____`；隔离后端 `____`；测试账号 `____`。填写“通过/失败/未执行”和证据路径，不以“无异常”替代操作结果。

| 项目 | 操作与通过条件 | 当前状态 | 证据/缺陷 |
| --- | --- | --- | --- |
| 视觉与大字 | 浅/深色、窄屏、大字、横屏检查计时、会话、聊天、表单与设置；按钮可达、文本无关键截断、键盘不遮提交 | 未执行 | ____ |
| TalkBack | 顺序读取普通控件、操作整行开关和错误提示；隐藏图案不暴露点位或聊天信息 | 未执行 | ____ |
| 隐私冷启动/返回 | 杀进程、最近任务、锁屏、应用切换、外部链接返回；敏感内容出现前始终先遮罩/解锁，截图策略生效 | 未执行 | ____ |
| 通知导航 | 普通/隐私模式分别点消息通知，冷启动与热启动均到正确会话；计时通知不进入聊天 | 未执行 | ____ |
| 国内后台策略 | 拒绝/允许通知、自启动与省电限制、前台服务启动失败、进程回收后再打开；显示可恢复状态，离线消息补齐 | 未执行 | ____ |
| Doze 与计时 | 熄屏到期、Doze、重启、改时间、撤销精确提醒权限；不重复提醒，旧闹钟不结束新一轮；记录实际偏差 | 未执行 | ____ |
| 会话竞态 | 弱网 A→B→A、返回再进入、搜索离页、发送期间继续输入；迟到结果不串会话、不清新草稿/新预览 | 未执行 | ____ |
| 网络与取消 | Wi-Fi/移动网络切换、离线重试、单独取消附件；其他同步继续，重试不重复发消息 | 未执行 | ____ |
| 凭据边界 | 隔离测试设备注入凭据不可读状态；旧内容不展示，本机待发/草稿保留，原账号恢复；完整隐私损坏保持封闭 | 未执行 | ____ |
| 本机删除/迁移 | v2→v3、退出再登录、换账号、同步与历史加载；删除标记按 owner 保留，不重现已清内容 | 未执行 | ____ |
| 保留策略 | 开启、缩短、延长、关闭、后台/重启、调整时间；截止线不回退，过期引用和附件立即不可读 | 未执行 | ____ |
| 附件导出 | 选择器返回、旋转、进程重建、复制中止、空间不足和撤回；成功文件完整，失败/中断文件清理或明确提示 | 未执行 | ____ |
| 新旧协议 | 新新、新旧组合执行同步与撤回；能力不足不开放撤回，旧端遇不支持状态明确更新，不推进错误游标 | 未执行 | ____ |
| 正式覆盖安装 | 原签名真实已发布 APK 写入测试会话/草稿/待发/隐私与计时状态，覆盖安装目标原件；版本递增、内容与设置保持、门控不被绕过 | 未执行 | ____ |
| 错误升级包 | 大小、哈希、包名、版本、证书分别错误；均被拒绝，原应用可继续使用 | 未执行 | ____ |
| 改密/管理端 | 并发改密与旧会话访问、表单错误回填、删除账号确认；旧会话失效，错误页不丢关键输入/不泄露内容 | 未执行 | ____ |
| 性能与恢复 | 同设备冷启动采集、长列表交互、长时间计时、一次性备份恢复；保留原始结果与异常，不先填性能结论 | 未执行 | ____ |

## 验证结果：已确认部分与外部验收

目标版本为 **Touch 2.0 / 2.0.0 / versionCode 16**。以下记录按实际运行范围区分，不把候选版本的设备证据自动计入最终版本：

| 项目 | 已确认结果与边界 |
| --- | --- |
| Android 构建 | Touch 2.0 Release、Benchmark、Debug 与测试 APK 均构建成功；Release 仍为**未签名** APK，未作为正式包发布。 |
| JVM | Debug、Release 各运行同一组 29 项，均通过、0 跳过；不是 58 个不同用例。 |
| Lint | Debug：0 错误、28 警告、4 提示；Release：0 错误、29 警告、4 提示。警告和提示未宣称清零。 |
| 服务端 | 最终 [完整 XML](quality/evidence/server-tests.xml)记录 **105 通过、1 跳过**；唯一跳过项需要 PostgreSQL，当前未连接该测试库。锁后旧令牌回归通过确定性测试模拟鉴权窗口，不能代替真实 PostgreSQL 并发测试。 |
| 静态与截图 | Ruff 通过；截图比较工具 6 项测试通过。14 张实际设计截图两轮零容差稳定，人工审查后的基线比较 14 项全部通过。 |
| Baseline Profile | [采集器 XML](quality/evidence/baseline-collector-api36.xml)记录 2 场景通过、0 跳过，源自版本锁定前 1.0.14 候选；Touch 2.0 R8 已消费规则且 APK 含编译 profile，详见性能章节。 |
| 设备功能 | 最终 Touch 2.0 在隔离 API 36.1 模拟器上 **44 个不同用例通过**，去重后的最新结果无失败或跳过；[摘要](quality/evidence/verification-v2-summary.json)保留原始轮次、首次缺参数跳过及两个测试旧期望/定位失败，修正后的真实设备复验已通过。完整 CI API 矩阵和真机未宣称全部实跑通过。 |
| 存储测量 | [Touch 2.0 API 36 debug JSON](quality/evidence/storage-v2-api36-debug.json)已保存；P95 29.368061ms、清理约 920.6ms，仅为所述模拟器与构建条件的记录。 |
| 冷启动与滚动 | 2.0.0/code 16 压缩 Benchmark 包完成 20 次冷启动、10 次滚动及 2 个 JUnit 测量测试；[汇总](quality/evidence/performance-v2-summary.json)保留软件模拟器的高帧超时结果。流程完成不代表性能达标，物理设备验收未执行。 |

正式签名历史升级、国内 OEM 真机、正式 Release 真机性能采集、生产部署与发布均未执行。本文件提供实施入口和验收步骤，不代填证据。
