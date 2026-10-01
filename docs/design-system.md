# Touch 设计系统与视觉基线

本轮设计采用森林绿、暖中性色和系统中文字体，强调清晰层级、稳定布局、及时且局部的反馈。外层番茄钟、隐藏入口和隐私边界保持原有产品定位。解锁、退后台及安全遮蔽立即切换，不用过渡动画延迟门控；普通计时语义不暴露隐藏入口。

## 主题角色

所有页面通过 [TouchTheme](../android/app/src/main/java/com/arcxya09/touch/ui/Theme.kt) 使用 Material 语义角色，不在页面中重新定义品牌色。系统窗口、遮罩与栏图标对应 `values` / `values-night` 资源。以下为主要角色，完整色表以代码为准。

| 用途 / 角色 | 浅色 | 深色 |
| --- | --- | --- |
| 页面底色 `background` / `surface` | `#F8F7F2` | `#151C17` |
| 主文字 `onSurface` | `#232D26` | `#E4E9DF` |
| 辅助文字 `onSurfaceVariant` | `#586158` | `#B8C3B6` |
| 主操作、进度 `primary` | `#38634A` | `#A6D2AE` |
| 主操作文字 `onPrimary` | `#FFFFFF` | `#153923` |
| 分组背景 `surfaceContainerLow` | `#F3F3ED` | `#1C241E` |
| 发出消息 `primaryContainer` | `#DCEADD` | `#2C4E36` |
| 发出消息文字 `onPrimaryContainer` | `#173C25` | `#DCEADD` |
| 细边界 `outlineVariant` | `#D6DDD2` | `#414D42` |
| 错误、破坏性操作 `error` | `#AD3A35` | `#FFB4AA` |

正文与背景的计算对比度为浅色 13.27:1、深色 14.06:1；辅助文字为 5.99:1、9.52:1。此结果针对上述纯色组合，不能替代透明叠层、图片上文字或实际设备显示的检查。状态同时使用文案和动作说明，不只依赖颜色。

## 排版、尺寸与触达

- 正文与中文标题采用系统 `SansSerif`，避免下载字体或假定某一厂商字形。字号随用户字体设置缩放；保留自然换行，不用固定高度截断多行说明。
- 常用样式为页面标题 `21sp / 30sp`、小节标题 `17sp / 26sp`、正文 `16sp / 26sp`、次级正文 `14sp / 22sp`、说明 `12sp / 20sp`，分别表示字号与行高。时间等次要标签可用 `11sp / 16sp`。
- 计时数字使用等宽字体，以圆盘尺寸计算视觉字号；字号仅随系统字体设置适度增大，避免非线性缩放挤压数字。阶段和操作文字仍正常缩放，并自动换行。
- 常用间距为 4、8、12、16、20、24、32dp；圆角角色为 6、10、16、24、28dp。设置/表单页左右留白 24dp，最大内容宽 640dp；会话与聊天最大宽 840dp。
- 可点击图标和主操作遵循至少 48dp 的触达目标；状态副标题也有独立点击区域。设置开关整行可操作，只提供一个开关语义节点，避免正文与开关重复聚焦。
- 小屏、大字页面优先滚动和重排。隐藏图案层继续使用原交互，不增加可被读屏发现的隐藏点位描述。

## 组件约定

| 组件 | 用法与约束 |
| --- | --- |
| `TouchHeader`、`TouchPage` | 共用标题、返回、工作指示和受限内容宽度；子页采用相同返回路径。标题使用 heading 语义。 |
| `BusyIndicator` | 预留 4dp 高度；操作开始和结束时不推动主体布局。按实际操作状态启用，加载会话不阻止返回。 |
| `SettingsGroup`、`SettingItem`、`ToggleSetting` | 设置归并为个人资料、密码、隐私与安全、本机数据、通知与后台、关于与更新。说明写清范围；开关即刻反馈。 |
| `EmptyState` | 区分首次加载、空内容和同步失败。可恢复失败提供明确重试；已有缓存继续可读。 |
| `ConversationListContent` | 会话行统一头像、标题、预览、时间和未读层级；行内菜单处理会话动作。 |
| `ChatHeader`、`ChatBubbleSurface`、`ChatComposer` | 输入区稳定留在底部，消息宽度限制在可读范围；发送中、失败、附件传输在对应区域反馈。 |
| `PendingMessageActions` | 失败消息就地重试或本机删除，不用全局阻塞框覆盖聊天。 |
| `Confirm` | 写出具体动作，如“清空本地记录”“撤回消息”，并说明范围；破坏性确认使用错误色。 |
| 表单 | 就地校验、密码显隐、输入法动作、提交工作态；编辑页返回时处理未保存修改，不把密码写入保存状态。 |

生产页与样板复用上述组件；样板不构建 ViewModel、仓库、账号或网络请求。关键实现入口为 [Components.kt](../android/app/src/main/java/com/arcxya09/touch/ui/Components.kt)、[ChatComponents.kt](../android/app/src/main/java/com/arcxya09/touch/ui/ChatComponents.kt)、[ConversationListContent.kt](../android/app/src/main/java/com/arcxya09/touch/ui/ConversationListContent.kt)、[TimerScreen.kt](../android/app/src/main/java/com/arcxya09/touch/ui/TimerScreen.kt)。

## 离线样板与截图生成

Android Studio 可直接打开 [ComponentPreviews.kt](../android/app/src/main/java/com/arcxya09/touch/ui/ComponentPreviews.kt) 与 [DesignFixtures.kt](../android/app/src/main/java/com/arcxya09/touch/ui/DesignFixtures.kt) 的 Compose Preview，查看组件、计时页、消息列表和聊天页。预览数据完全合成。三页的正常/恢复状态均支持浅深色；计时器另有横屏与小屏大字预览。

[DesignSystemInstrumentedTest](../android/app/src/androidTest/java/com/arcxya09/touch/DesignSystemInstrumentedTest.kt) 生成 12 张三页样板截图与 2 张小屏截图，并验证消息自定义无障碍动作、设置单开关语义、小屏开始按钮可达。只运行该类不需要测试后端。仓库根目录执行：

```powershell
.\android\gradlew.bat -p android :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.arcxya09.touch.DesignSystemInstrumentedTest --stacktrace
```

使用已安装的调试包和测试包时，可指定隔离设备运行：

```powershell
adb -s emulator-5562 shell am instrument -w -e class com.arcxya09.touch.DesignSystemInstrumentedTest com.arcxya09.touch.debug.test/androidx.test.runner.AndroidJUnitRunner
```

截图保存在目标调试包私有目录 `files/design-snapshots/`。导出 PNG 时使用二进制输出，避免旧版 PowerShell 的文本重定向改写文件；例如用 Python `subprocess.run(["adb", "-s", serial, "exec-out", "run-as", "com.arcxya09.touch.debug", "cat", "files/design-snapshots/" + name], stdout=output_file, check=True)`，其中 `output_file` 以 `wb` 模式打开。

供性能采样的 `DesignBenchmarkActivity` 仅存在于 benchmark / nonMinifiedRelease 变体，共用生产列表和聊天组件，提供“消息样板”“聊天样板”按钮。消息列表外层描述为“样板消息列表”，内部使用实际可滚动列表；聊天列表描述为“样板聊天列表”。此入口不进入正式发布包，不绕过真实应用门控。运行性能模块不等于已经证明性能改善，结果见 [品质升级验收](quality-upgrade.md)。

## 已保存的视觉基线

基线生成于 2026-10-01，来自隔离 `Touch_Quality` AVD：`android-36.1` Google Play x86_64 系统镜像、Android Emulator 36.2.12.0、1080×2400、420dpi（density 2.625），Compose BOM `2026.09.00`，模拟器系统默认字体。12 张关键页以默认字体比例生成；小屏用 Compose `LocalDensity` 明确设为 100% / 200%，固定内容视口 320×640dp，输出 840×1680px。浅深色由样板参数固定，不依赖运行时主题切换。

截图捕获 Compose 根节点，未包含系统状态栏、导航栏和键盘。小屏截图在测试滚动到“开始”并确认可见之后、实际点击之前采集；随后点击并断言回调执行。大字视口中底部辅助文案需继续滚动，这是滚动布局的预期表现。计时固定 25:00；会话名、消息与附件均为合成内容。恢复状态分别展示未开启通知、首次同步失败、单条发送失败。

样板时间不会逐分钟变化：`SampleInbox` 以运行当天的本地日期固定在 12:08，再分别减 1、2 小时，因此正常列表显示 12:08、11:08、10:08；`SampleConversation` 的“今天”与 12:08–12:11 均为固定文字。当前样板没有注入冻结的 `Clock`。列表仍复用生产日期格式函数，该函数以 `LocalDate.now()` 判断是否同日；整批采集应保持相同系统时区且不要跨当地午夜，否则可能由 `HH:mm` 切换成 `M/d`。

生产聊天的日期分隔同样根据当前日期显示“今天”“昨天”或具体年月日；合成聊天样板使用固定“今天”，没有覆盖该相对日期逻辑。若扩展日期分隔的逐像素基线，应先在隔离测试环境冻结参考日期、时区与时钟（或向日期格式逻辑注入固定参考日），再分别构造今天、昨天和更早日期。当前 14 张基线不能视为这些分支已经验收。

| 页面 | 浅色正常 | 深色正常 | 浅色恢复 | 深色恢复 |
| --- | --- | --- | --- | --- |
| 番茄钟 | [PNG](quality/screenshots/timer-light-ready.png) | [PNG](quality/screenshots/timer-dark-ready.png) | [PNG](quality/screenshots/timer-light-recovery.png) | [PNG](quality/screenshots/timer-dark-recovery.png) |
| 消息列表 | [PNG](quality/screenshots/inbox-light-ready.png) | [PNG](quality/screenshots/inbox-dark-ready.png) | [PNG](quality/screenshots/inbox-light-recovery.png) | [PNG](quality/screenshots/inbox-dark-recovery.png) |
| 聊天 | [PNG](quality/screenshots/chat-light-ready.png) | [PNG](quality/screenshots/chat-dark-ready.png) | [PNG](quality/screenshots/chat-light-recovery.png) | [PNG](quality/screenshots/chat-dark-recovery.png) |

另有 [320dp 浅色计时页](quality/screenshots/timer-light-320.png) 和 [320dp 深色 200% 字体计时页](quality/screenshots/timer-dark-font200-320.png)。14 张基线均已人工查看，没有发现关键文本重叠或主操作被裁切。小屏测试已实际点击开始；像素截图本身不能证明其他交互均可用。

### 小屏基线修正记录

2026-10-01 的 v2 首次严格比较中，12 张关键页完全一致，两张小屏图出现差异：浅色 38,281 像素（最大通道差 1），深色大字 56,301 像素（最大通道差 24）。[原失败报告](quality/evidence/design-v2-comparison/comparison.json)及其差异图继续保留。逐像素检查发现差异全部位于“开始”按钮；原测试在点击后立即截图，记录了 Android RenderThread 的点击涟漪，Compose 空闲等待无法固定该原生动画的帧。

修正仅涉及测试采集顺序：先滚动并截图，再点击验证功能；用 `key(dark, large)` 为下一主题重新创建样板，避免沿用上次点击状态。未改变生产主题、布局或按钮行为。[第一轮](quality/evidence/device-v2-notification-design.txt)中的设计 4 项通过（含通知相关用例共 7 项通过），[第二轮](quality/evidence/device-v2-static-design-repeat.txt)设计 4 项通过。两轮各生成 14 张截图，[稳定性报告](quality/evidence/design-v2-static-stability/comparison.json)显示全部逐像素一致：`pixel_threshold=0`、`max_diff_ratio=0`，每张变化像素均为 0。

复核静态小屏图确认没有涟漪、文字重叠或按钮裁切；按钮背景分别为主题主色 `#38634A`、`#A6D2AE`，与原图的变化仍只在按钮内。经审查，仅以第二轮 `.local/quality-screenshots-v2-static2` 的 `timer-light-320.png`、`timer-dark-font200-320.png` 更新基线，其余 12 张不变；比较容差保持 0。此更新修正采集的不确定性，不掩盖布局回归。

比较截图时保持系统镜像、密度、字体比例、Compose 依赖及数据一致。修改共享组件后重新生成全组截图并审查差异，确认后再更新基线。不同厂商字体、横屏窗口、真实键盘、TalkBack 连续操作及安全窗口切换仍按 [品质升级验收](quality-upgrade.md) 的真机项目记录，不以这些静态样板替代。
