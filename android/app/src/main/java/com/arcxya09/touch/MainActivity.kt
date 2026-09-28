package com.arcxya09.touch

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.graphics.Color
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

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()
    private lateinit var cover: TextView
    private var exportFile: EncryptedAttachment? = null
    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> selection(uri, "image") }
    private val avatarPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> model.pendingAvatar = uri }
    private val documentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> selection(uri, "file") }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val source = exportFile
        if (uri != null && source != null) {
            // The user explicitly authorized this export before entering the picker.
            Thread {
                runCatching {
                    source.checkAccess()
                    contentResolver.openOutputStream(uri)?.use { output -> source.input().use { it.copyTo(output) } }
                        ?: error("无法保存文件")
                }
                    .onFailure { runOnUiThread { model.error = "文件保存失败" } }
            }.start()
        }
        exportFile = null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        model.updater = Updater(this, model.repository.api)
        if (intent.getBooleanExtra("timer", false)) model.screen = "timer"
        setContent { TouchRoot(model, this) }
        cover = TextView(this).apply {
            text = "Touch番茄钟"; textSize = 28f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(57, 107, 75)); setBackgroundColor(Color.rgb(247, 245, 239))
        }
        addContentView(cover, android.view.ViewGroup.LayoutParams(-1, -1))
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("timer", false)) { model.hide(); model.screen = "timer" }
    }
    override fun onPause() {
        if (::cover.isInitialized && (model.privacy || model.needsPrivacySetup)) cover.visibility = View.VISIBLE
        model.background()
        super.onPause()
    }
    override fun onResume() { super.onResume(); model.resume() }
    fun renderedGate() {
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
    fun exactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= 31 && !getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
    }
    fun openExternal(file: EncryptedAttachment, mime: String) {
        try {
            val uri = AttachmentProvider.share("$packageName.attachments", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .apply { clipData = android.content.ClipData.newRawUri("附件", uri) }
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "选择查看应用"))
        } catch (_: Exception) { model.error = "未找到可打开此文件的应用，可选择保存文件" }
    }
    fun export(file: EncryptedAttachment, name: String) { exportFile = file; exporter.launch(name) }
}
