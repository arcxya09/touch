# Touch番茄钟

一款适用于 Android 10 及以上系统的极简番茄钟，让专注与休息更有节奏。

## 功能

- 默认专注 25 分钟、休息 5 分钟，可按习惯调整时长。
- 支持开始、暂停、继续和重置，每段结束后手动开始下一段。
- 极简数字与进度环，适配系统深色模式。
- 保存计时状态，重新打开应用后可继续查看进度。
- 到时提醒，并支持在应用内检查和安装新版本。

## 下载

[下载最新版 APK](https://github.com/arcxya09/touch/releases/latest/download/touch.apk) · [查看版本说明](https://github.com/arcxya09/touch/releases)

安装后即可使用番茄钟。需要到时提醒时，请按系统提示允许通知及相关闹钟权限；权限未开启时仍可正常计时。

## 使用

1. 选择专注或休息，设置适合自己的时长。
2. 点击开始，需要时可暂停或重置。
3. 一段计时结束后，手动开始下一段。

## 开发

原生 Kotlin 与 Jetpack Compose 开发，最低支持 Android 10（API 29）。

构建需要 JDK 21、Android SDK 37.0 和 Build Tools 36.0.0：

```sh
cd android
./gradlew assembleDebug testDebugUnitTest lintDebug
```

正式版本通过 GitHub Releases 分发。
