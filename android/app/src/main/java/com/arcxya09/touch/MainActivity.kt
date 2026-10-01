package com.arcxya09.touch

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.arcxya09.touch.data.AttachmentProvider
import com.arcxya09.touch.data.EncryptedAttachment
import androidx.core.view.WindowCompat
import com.arcxya09.touch.ui.TouchRoot
import com.arcxya09.touch.update.Updater
import java.io.File

class MainActivity : ComponentActivity(), android.hardware.SensorEventListener {
    private val sensors by lazy { getSystemService(android.hardware.SensorManager::class.java) }
    private var motionOptions: com.arcxya09.touch.security.SafetyOptions? = null
    private var detector = com.arcxya09.touch.security.MotionExitDetector()
    private var resumed = false
    private var exiting = false
    private var recentsApplied: Boolean? = null
    fun hasMotionSensor() = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) != null
    internal fun applySafety() {
        if (!model.initialized || exiting) return
        val options = model.safety
        if (recentsApplied != options.hideRecents) {
            runCatching {
                getSystemService(android.app.ActivityManager::class.java).appTasks
                    .firstOrNull { it.taskInfo?.taskId == taskId }?.setExcludeFromRecents(options.hideRecents)
            }.onSuccess { recentsApplied = options.hideRecents }
        }
        val active = options.takeIf { resumed && (it.flipExit || it.shakeExit) }
        if (motionOptions == active) return
        sensors.unregisterListener(this); detector = com.arcxya09.touch.security.MotionExitDetector()
        motionOptions = active
        if (active != null) sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)?.let {
            sensors.registerListener(this, it, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        }
    }
    override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
    override fun onSensorChanged(event: android.hardware.SensorEvent) {
        val options = motionOptions ?: return
        if (resumed && !exiting && detector.sample(event.timestamp / 1000000, event.values[0], event.values[1], event.values[2], options.flipExit, options.shakeExit)) {
            exiting = true
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (::cover.isInitialized) cover.visibility = View.VISIBLE
            model.background()
            finishAndRemoveTask()
        }
    }

    private val model: AppViewModel by viewModels()
    private lateinit var cover: TextView
    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> selection(uri, "image") }
    private val avatarPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> model.pendingAvatar = uri }
    private val documentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> selection(uri, "file") }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val messageNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.pendingNotificationEnable = true
        else model.error = "通知权限未开启，消息通知仍保持关闭。可在系统通知设置中开启权限。"
    }
    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        model.completeExport(uri)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        model.updater = Updater(this, model.repository.api)
        if (savedInstanceState == null) routeNotification(intent)
        model.pendingNotificationSettings = intent.getBooleanExtra("notification_settings", false)
        setContent { TouchRoot(model, this) }
        cover = TextView(this).apply {
            text = "Touch番茄钟"; textSize = 28f; gravity = Gravity.CENTER
            setTextColor(getColor(R.color.touch_accent)); setBackgroundColor(getColor(R.color.touch_window_background))
        }
        addContentView(cover, android.view.ViewGroup.LayoutParams(-1, -1))
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("notification_settings", false)) model.pendingNotificationSettings = true
        routeNotification(intent)
    }
    private fun routeNotification(intent: Intent) {
        when {
            intent.getBooleanExtra("message_alert", false) -> model.openMessageNotification(intent.getStringExtra("conversation_id"))
            intent.getBooleanExtra("timer", false) -> { model.hide(); model.navigate(Screen.Timer) }
            intent.action == Intent.ACTION_MAIN && !intent.getBooleanExtra("notification_settings", false) &&
                (application as TouchApp).alerts.hasDiscreetMessage() -> model.openMessageNotification(null)
        }
    }
    override fun onPause() {
        resumed = false; sensors.unregisterListener(this); motionOptions = null
        if (::cover.isInitialized && (model.privacy || model.needsPrivacySetup)) cover.visibility = View.VISIBLE
        model.background()
        super.onPause()
    }
    override fun onResume() { super.onResume(); resumed = true; model.resume(); applySafety() }
    fun renderedGate() {
        if (exiting) return
        applySafety()
        model.renderedChat()
        if (model.privacy || model.needsPrivacySetup || !model.initialized) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!(model.privacy || model.needsPrivacySetup))
        if (::cover.isInitialized) cover.visibility = View.GONE
    }
    private fun selection(uri: Uri?, kind: String) {
        if (uri == null) return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        model.pendingSelection = uri to kind
    }
    fun chooseAvatar() = avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    fun chooseImage() = imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    fun chooseDocument() = documentPicker.launch(arrayOf("*/*"))
    fun notificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    fun enableMessageNotifications() {
        if (!model.mayShowChat) return
        if ((application as TouchApp).alerts.allowed()) model.enableAlerts(true)
        else if (Build.VERSION.SDK_INT >= 33) messageNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else model.error = "请先打开系统通知权限，再开启消息通知"
    }
    fun openNotificationSettings() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }
    fun openBatterySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            .onFailure { model.error = "请在系统设置中允许 Touch番茄钟后台运行与自启动" }
    }
    fun addReminderTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(android.app.StatusBarManager::class.java).requestAddTileService(
                android.content.ComponentName(this, com.arcxya09.touch.notifications.ReminderTileService::class.java),
                "Touch番茄钟", android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_touch), mainExecutor) { result ->
                    if (result < 0) model.error = "请下拉快捷设置，点击编辑，手动添加 Touch番茄钟"
                }
        } else model.error = "请下拉控制中心或快捷设置，点击编辑，将 Touch番茄钟拖入已启用区域"
    }
    fun exactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= 31 && !getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
    }
    fun openWebLink(url: String) {
        if (!model.mayShowChat) return
        val uri = Uri.parse(url)
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return
        try {
            val view = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            // Resolve against a host-free web selector: default browser, never an app deep link.
            // The launched browser still receives the original URL in the outer intent.
            view.selector = Intent(Intent.ACTION_VIEW, Uri.parse("https://"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            startActivity(view)
        } catch (_: android.content.ActivityNotFoundException) { model.error = "未找到可用浏览器，请安装或启用浏览器后重试" }
          catch (_: SecurityException) { model.error = "系统暂不允许打开浏览器" }
    }
    fun openExternal(file: EncryptedAttachment, mime: String) {
        try {
            val uri = AttachmentProvider.share("$packageName.attachments", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .apply { clipData = android.content.ClipData.newRawUri("附件", uri) }
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "选择查看应用"))
        } catch (_: Exception) { model.error = "未找到可打开此文件的应用，可选择保存文件" }
    }
    fun export(file: EncryptedAttachment, name: String) { model.prepareExport(file, name) { exporter.launch(it) } }
}
