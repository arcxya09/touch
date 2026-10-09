package com.arcxya09.touch

import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.arcxya09.touch.data.TouchDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class PrivacyInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<TouchApp>()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var previousRotationLock: Boolean? = null
    private var previousImageRotation: Boolean? = null
    @Before fun prepare() = runBlocking<Unit> {
        val preferences = app.getSharedPreferences("display_preferences", Context.MODE_PRIVATE)
        previousRotationLock = if (preferences.contains("lock_chat_rotation")) preferences.getBoolean("lock_chat_rotation", false) else null
        previousImageRotation = if (preferences.contains("rotate_image_preview")) preferences.getBoolean("rotate_image_preview", false) else null
        preferences.edit().remove("lock_chat_rotation").remove("rotate_image_preview").commit()
        app.repository.logout(false)
        app.secureStore.write("privacy", JSONObject().put("enabled", true).put("pattern", "0125").toString())
        File(app.filesDir, "privacy.enabled").writeText("1")
        app.getSharedPreferences("gesture_attempts", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun cleanup() {
        try { scenario?.close() } finally {
            val edit = app.getSharedPreferences("display_preferences", Context.MODE_PRIVATE).edit()
            previousRotationLock?.let { edit.putBoolean("lock_chat_rotation", it) } ?: edit.remove("lock_chat_rotation")
            previousImageRotation?.let { edit.putBoolean("rotate_image_preview", it) } ?: edit.remove("rotate_image_preview")
            edit.commit()
        }
    }
    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(10000) { compose.onAllNodesWithTag("hidden-pattern").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun unlock() {
        compose.onNodeWithTag("hidden-pattern").performTouchInput {
            down(Offset(width / 6f, height / 6f))
            moveTo(Offset(width / 2f, height / 6f), 100)
            moveTo(Offset(width * 5f / 6f, height / 6f), 100)
            moveTo(Offset(width * 5f / 6f, height / 2f), 100)
            up()
        }
    }
    private fun activeModel(): AppViewModel {
        lateinit var model: AppViewModel
        scenario!!.onActivity {
            model = ViewModelProvider(it)[AppViewModel::class.java]
            // These navigation tests use only the disposable local API, never the update service.
            model.updater = null
        }
        return model
    }
    private fun assertRequestedOrientation(expected: Int, reason: String) {
        compose.waitForIdle()
        compose.waitUntil(10000) {
            var current = Int.MIN_VALUE
            scenario!!.onActivity { current = it.requestedOrientation }
            current == expected
        }
        scenario!!.onActivity { assertEquals(reason, expected, it.requestedOrientation) }
    }
    private fun launchConversation(): Pair<AppViewModel, String> = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val apiUri = URI(BuildConfig.API_BASE)
        require(apiUri.scheme == "http" && apiUri.host == "127.0.0.1") { "Selection tests require the disposable loopback fixture" }
        val username = requireNotNull(args.getString("touchTestUser"))
        require(username.startsWith("verify_local_"))
        app.repository.initialize()
        app.repository.login(username, requireNotNull(args.getString("touchTestPassword")))
        app.repository.sync()
        val cid = app.repository.conversations().single().id
        launch()
        val model = activeModel()
        unlock()
        compose.waitUntil(15000) { model.mayShowChat && model.user != null }
        scenario!!.onActivity { model.openConversation(cid) }
        compose.waitUntil(15000) { model.destination == Screen.Chat && !model.isWorking(Operation.Conversation) }
        model to cid
    }
    @Test fun systemPickersCancelBackToTheirUnlockedPage() {
        val (model, _) = launchConversation()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        for (kind in SelectionKind.entries) {
            val page = if (kind == SelectionKind.Avatar) Screen.Profile else Screen.Chat
            scenario!!.onActivity { activity ->
                model.navigate(page)
                when (kind) {
                    SelectionKind.Image -> activity.chooseImage()
                    SelectionKind.Document -> activity.chooseDocument()
                    SelectionKind.Avatar -> activity.chooseAvatar()
                }
            }
            assertTrue("The system selector must be visible for $kind", device.wait(Until.gone(By.pkg(app.packageName).depth(0)), 10000))
            compose.waitUntil(10000) { model.locked && !model.mayShowChat }
            assertTrue("Picker pause must retain only its bounded continuation", model.hasExternalSelection)
            device.pressBack()
            compose.waitUntil(15000) { model.mayShowChat }
            scenario!!.onActivity {
                assertFalse(model.locked)
                assertEquals(page, model.destination)
                assertNull(model.pendingSelection)
                assertNull(model.pendingAvatar)
                assertFalse(model.hasExternalSelection)
            }
        }
    }
    @Test fun selectedSystemPhotoReturnsToPrivateChatAndSendsExactlyOnce() = runBlocking<Unit> {
        val (model, cid) = launchConversation()
        val owner = requireNotNull(model.user).id
        val before = app.repository.messages(cid).map { it.id }.toSet()
        val name = "touch-picker-${UUID.randomUUID()}.png"
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val png = try {
            bitmap.eraseColor(0xff000000.toInt() or (name.hashCode() and 0x00ffffff))
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
        val checksum = MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }
        val resolver = app.contentResolver
        val media = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/TouchPickerTests")
            put(MediaStore.Images.ImageColumns.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        var stage = "publish-fixture"
        try {
            requireNotNull(resolver.openOutputStream(media)).use { it.write(png) }
            assertEquals(1, resolver.update(media, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            val mediaColumns = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.DATE_MODIFIED)
            requireNotNull(resolver.query(media, mediaColumns, null, null, null)).use { row ->
                assertTrue("The published fixture must be indexed in MediaStore", row.moveToFirst())
                Log.i("TouchPickerTest", "published=" + mediaColumns.mapIndexed { index, column -> "$column=${row.getString(index)}" }.joinToString())
                assertEquals(name, row.getString(0))
                assertEquals("image/png", row.getString(1))
                assertEquals(0, row.getInt(2))
                assertEquals(png.size.toLong(), row.getLong(3))
                assertEquals(64, row.getInt(4))
                assertEquals(64, row.getInt(5))
                assertTrue("The image scan must populate its modification time", row.getLong(6) > 0)
            }
            compose.onNodeWithContentDescription("添加图片或文件").performClick()
            compose.onNodeWithText("图片").performClick()
            stage = "open-system-picker"
            assertTrue(device.wait(Until.gone(By.pkg(app.packageName).depth(0)), 10000))
            compose.waitUntil(10000) { model.locked && !model.mayShowChat }
            stage = "select-system-photo"
            selectSystemPhoto(device, name) { stage = it }
            stage = "return-to-private-chat"
            compose.waitUntil(15000) { model.pendingSelection != null && model.mayShowChat }
            val selected = requireNotNull(model.pendingSelection).first
            assertEquals("content", selected.scheme)
            // Never submit an unrelated gallery item if a device uses a different grid layout.
            assertArrayEquals("The real picker must return the generated image", png,
                requireNotNull(resolver.openInputStream(selected)).use { it.readBytes() })
            scenario!!.onActivity {
                assertFalse(model.locked)
                assertEquals(Screen.Chat, model.destination)
                assertEquals(cid, model.conversationId)
                assertFalse(model.hasExternalSelection)
            }
            compose.onNodeWithText("发送附件").assertIsDisplayed()
            compose.waitUntil(10000) {
                compose.onAllNodesWithContentDescription("所选图片预览").fetchSemanticsNodes().isNotEmpty()
            }
            stage = "confirm-send"
            compose.onNodeWithText("发送").assertIsEnabled().performClick()
            compose.waitUntil(20000) {
                !model.isWorking(Operation.Attachment) && model.pendingSelection == null &&
                    model.messages.any { !it.pending && it.senderId == owner && it.file?.sha256 == checksum }
            }
            val sent = model.messages.filter { it.id !in before && it.senderId == owner }
            assertEquals("Selecting and confirming once must create exactly one message", 1, sent.size)
            val message = sent.single()
            assertEquals("image", message.kind)
            assertFalse(message.pending)
            assertEquals(checksum, requireNotNull(message.file).sha256)
            assertNull(model.error)
            stage = "reconcile-history"
            repeat(2) { app.repository.sync(); app.repository.history(cid) }
            val rows = app.repository.messages(cid).filter { it.clientId == message.clientId && it.senderId == owner }
            assertEquals("Sync and history must reconcile the same image rather than duplicate it", listOf(message.id), rows.map { it.id })
            val remote = app.repository.api.json("/api/v1/conversations/$cid/messages").getJSONArray("messages")
            assertEquals(1, (0 until remote.length()).count {
                remote.getJSONObject(it).optString("client_id") == message.clientId &&
                    remote.getJSONObject(it).optString("sender_id") == owner
            })
        } catch (failure: Throwable) {
            recordPickerFailure(device, model, stage)
            throw failure
        } finally {
            assertEquals("Remove only this test's generated gallery image", 1, resolver.delete(media, null, null))
        }
    }
    private fun selectSystemPhoto(device: UiDevice, name: String, stage: (String) -> Unit) {
        // Wait for an actual picker root: Touch disappearing also occurs during the transition.
        val root = device.wait(Until.findObject(By.pkg(Pattern.compile(
            "com\\.(?:google\\.)?android\\.(?:documentsui|photopicker|providers\\.media(?:\\.module)?)"
        )).depth(0)), 10000)
        assertNotNull("The system picker must finish opening", root)
        val pickerPackage = root!!.applicationPackage
        val photo = if (pickerPackage.endsWith(".documentsui")) {
            // API 29/31 can show an empty Recent view after publication. Browse the
            // indexed image's actual album through the system provider instead.
            assertTrue("DocumentsUI must load its directory", device.wait(Until.hasObject(By.res(pickerPackage, "dir_list")), 10000))
            stage("open-documents-roots")
            val roots = device.wait(Until.findObject(By.pkg(pickerPackage).desc("Show roots")), 5000)
            assertNotNull("DocumentsUI must expose its roots navigation", roots)
            roots!!.click()
            stage("open-images-root")
            val images = device.wait(Until.findObject(By.pkg(pickerPackage).text("Images")), 5000)
            assertNotNull("The system media provider must expose its Images root", images)
            clickPickerAction(images!!)
            stage("open-fixture-album")
            val album = device.wait(Until.findObject(By.pkg(pickerPackage).text("TouchPickerTests")), 5000)
            assertNotNull("The published fixture album must be visible in Images", album)
            clickPickerAction(album!!)
            stage("select-fixture-photo")
            device.wait(Until.findObject(By.pkg(pickerPackage).text(name)), 5000)
        } else {
            // The only fixture image is also the newest local photo. Its bytes are checked
            // after return and before sending, so another image can never pass this test.
            device.wait(Until.findObject(By.res(pickerPackage, "icon_thumbnail")), 5000)
                ?: device.wait(Until.findObject(By.pkg(pickerPackage).desc(Pattern.compile(
                    "(?i).*photo taken.*|.*拍摄.*照片.*|.*照片.*拍摄.*"
                ))), 5000)
        }
        assertNotNull("The generated MediaStore photo must be selectable in $pickerPackage", photo)
        photo!!.click()
        if (pickerPackage.endsWith(".photopicker")) {
            // The standalone picker now confirms single selections via SelectionBar too.
            // CI uses English. Locate the visible action without requesting visibility of
            // the system package, whose APK resources are intentionally hidden from Touch.
            stage("confirm-system-photo")
            val confirmation = device.wait(Until.findObject(By.pkg(pickerPackage).text("Done").enabled(true)), 5000)
            assertNotNull("Selecting a photo must expose the standalone picker's Done action", confirmation)
            clickPickerAction(confirmation!!)
        }
    }
    private fun clickPickerAction(label: UiObject2) {
        var target = label
        while (!target.isClickable) {
            val parent = target.parent ?: break
            if (parent.applicationPackage != label.applicationPackage) break
            target = parent
        }
        assertTrue("The picker action must have a clickable target", target.isClickable)
        target.click()
    }
    private fun recordPickerFailure(device: UiDevice, model: AppViewModel, stage: String) {
        // Only the loopback fixture reaches this test. Keep diagnostics in logcat as well as
        // on-device files so CI's existing per-test log artifact preserves the failing UI.
        runCatching {
            val state = "stage=$stage package=${device.currentPackageName} locked=${model.locked} " +
                "mayShowChat=${model.mayShowChat} page=${model.destination} " +
                "hasRequest=${model.hasExternalSelection} hasResult=${model.pendingSelection != null}"
            val xml = ByteArrayOutputStream().use { output -> device.dumpWindowHierarchy(output); output.toString("UTF-8") }
            val folder = File(app.getExternalFilesDir(null) ?: app.cacheDir, "picker-test-diagnostics").apply { mkdirs() }
            File(folder, "selection-state.txt").writeText(state)
            File(folder, "selection-window.xml").writeText(xml)
            Log.e("TouchPickerTest", state)
            xml.chunked(3000).forEachIndexed { index, part -> Log.e("TouchPickerTest", "hierarchy[$index]=$part") }
        }.onFailure { Log.e("TouchPickerTest", "Unable to collect picker diagnostics at $stage", it) }
    }
    @Test fun directPickerResultsKeepTheirOriginalPageAndCannotBeReplayed() {
        val (model, _) = launchConversation()
        val selected = Uri.parse("content://local-selection-fixture/item")
        for (kind in SelectionKind.entries) {
            val page = if (kind == SelectionKind.Avatar) Screen.Profile else Screen.Chat
            scenario!!.onActivity {
                model.navigate(page)
                assertTrue(model.beginExternalSelection(kind))
                model.pauseForExternalSelection()
                assertTrue(model.locked)
                assertFalse(model.mayShowChat)
                assertTrue(model.completeExternalSelection(kind, selected, deviceUnlocked = true))
                assertFalse(model.completeExternalSelection(kind, selected, deviceUnlocked = true))
                assertEquals(page, model.destination)
                if (kind == SelectionKind.Avatar) assertEquals(selected, model.pendingAvatar)
                else assertEquals(selected to kind.attachmentKind, model.pendingSelection)
                model.pendingAvatar = null; model.pendingSelection = null
                model.resume()
            }
            compose.waitUntil(10000) { model.mayShowChat }
        }
    }
    @Test fun interruptedOrOutdatedPickerResultCannotUnlockOrChangeSelection() {
        val (model, _) = launchConversation()
        val selected = Uri.parse("content://local-selection-fixture/item")
        for (interruption in listOf("background", "navigation", "screen-lock", "wrong-picker")) {
            scenario!!.onActivity {
                assertTrue(model.beginExternalSelection(SelectionKind.Image))
                model.pauseForExternalSelection()
                when (interruption) {
                    "background" -> model.background()
                    "navigation" -> { model.navigate(Screen.Home); model.navigate(Screen.Chat) }
                }
                assertFalse(model.completeExternalSelection(
                    if (interruption == "wrong-picker") SelectionKind.Document else SelectionKind.Image,
                    selected, deviceUnlocked = interruption != "screen-lock"))
                assertTrue(model.locked)
                assertFalse(model.mayShowChat)
                assertNull(model.pendingSelection)
                assertNull(model.pendingAvatar)
                model.resume()
            }
            compose.waitUntil(10000) { model.initialized && !model.storageError }
            scenario!!.onActivity { model.unlock(listOf(0, 1, 2, 5)) }
            compose.waitUntil(10000) { model.mayShowChat }
        }
    }
    @Test fun homeWhilePickerIsOpenInvalidatesAutomaticUnlock() {
        val (model, _) = launchConversation()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        scenario!!.onActivity { it.chooseDocument() }
        assertTrue(device.wait(Until.gone(By.pkg(app.packageName).depth(0)), 10000))
        assertTrue(model.hasExternalSelection)
        device.pressHome()
        compose.waitUntil(10000) { !model.hasExternalSelection }
        // Reopen the existing task. Some pickers remain on top; others return directly to Touch.
        app.startActivity(app.packageManager.getLaunchIntentForPackage(app.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (!device.wait(Until.hasObject(By.pkg(app.packageName).depth(0)), 2000)) device.pressBack()
        compose.waitUntil(15000) { compose.onAllNodesWithTag("timer-screen").fetchSemanticsNodes().isNotEmpty() }
        scenario!!.onActivity {
            assertTrue(model.locked)
            assertNull(model.pendingSelection)
        }
    }
    @Test fun pickerRequestDoesNotSurviveActivityRecreationOrConversationChange() {
        val (model, cid) = launchConversation()
        scenario!!.onActivity { assertTrue(model.beginExternalSelection(SelectionKind.Image)) }
        scenario!!.recreate()
        compose.onNodeWithTag("timer-screen").assertIsDisplayed()
        val recreated = activeModel()
        scenario!!.onActivity {
            assertFalse(recreated.completeExternalSelection(SelectionKind.Image, Uri.parse("content://local-selection-fixture/item"), true))
            assertTrue(recreated.locked)
        }
        unlock()
        compose.waitUntil(10000) { recreated.mayShowChat }
        scenario!!.onActivity {
            recreated.pendingSelection = Uri.parse("content://local-selection-fixture/item") to "image"
            recreated.openConversation(cid)
            assertNull(recreated.pendingSelection)
            recreated.pendingSelection = Uri.parse("content://local-selection-fixture/item") to "image"
            recreated.navigate(Screen.Home)
            assertNull(recreated.pendingSelection)
        }
    }
    @Test fun lockingScreenWhilePickerIsOpenInvalidatesAutomaticUnlock() {
        val (model, _) = launchConversation()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        scenario!!.onActivity { it.chooseDocument() }
        assertTrue(device.wait(Until.gone(By.pkg(app.packageName).depth(0)), 10000))
        val pickerPackage = requireNotNull(device.currentPackageName)
        assertTrue(model.hasExternalSelection)
        try {
            device.sleep()
            compose.waitUntil(10000) { !model.hasExternalSelection }
        } finally {
            device.wakeUp()
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("wm dismiss-keyguard")
            ).use { it.readBytes() }
        }
        assertTrue("Wake must return to the existing system picker", device.wait(Until.hasObject(By.pkg(pickerPackage).depth(0)), 10000))
        device.pressBack()
        assertTrue("Back must return to Touch before querying Compose", device.wait(Until.hasObject(By.pkg(app.packageName).depth(0)), 10000))
        compose.waitUntil(15000) {
            runCatching { compose.onAllNodesWithTag("timer-screen").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
        scenario!!.onActivity {
            assertTrue(model.locked)
            assertNull(model.pendingSelection)
        }
    }
    @Test fun rotationLockAlsoCoversLockedTimerLoginAndRecreation() {
        app.getSharedPreferences("display_preferences", Context.MODE_PRIVATE).edit()
            .putBoolean("lock_chat_rotation", true).putBoolean("rotate_image_preview", true).commit()
        launch()
        val model = activeModel()
        compose.onNodeWithTag("timer-screen").assertIsDisplayed()
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "The locked timer must keep the global portrait lock")
        unlock()
        compose.onNodeWithText("欢迎回来").assertIsDisplayed()
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "The signed-out login page must keep the global portrait lock")
        scenario!!.onActivity { model.navigate(Screen.Timer) }
        compose.onNodeWithTag("timer-screen").assertIsDisplayed()
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "The explicitly opened timer must also be locked")
        scenario!!.recreate()
        val recreated = activeModel()
        compose.onNodeWithTag("timer-screen").assertIsDisplayed()
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "Recreation must retain the preference while relocking privacy")
        scenario!!.onActivity { recreated.setRotationLocked(false) }
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, "Disabling the lock must release even the locked timer")
        unlock()
        compose.onNodeWithText("欢迎回来").assertIsDisplayed()
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, "Disabling the lock must release login as well")
    }
    @Test fun rotationLockCoversEveryPageAndOnlyVisibleImagesCanOptOut() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val apiUri = URI(BuildConfig.API_BASE)
        require(apiUri.scheme == "http" && apiUri.host == "127.0.0.1") { "Use the disposable loopback device fixture for the orientation navigation test" }
        val username = requireNotNull(args.getString("touchTestUser"))
        require(username.startsWith("verify_local_")) { "Only disposable fixture accounts are allowed" }
        app.repository.initialize()
        app.repository.login(username, requireNotNull(args.getString("touchTestPassword")))
        app.repository.sync()
        val cid = app.repository.conversations().single().id
        app.repository.history(cid)
        val rows = app.repository.messages(cid)
        val image = rows.first { it.file?.kind == "image" }
        val document = rows.first { it.file?.mime == "application/pdf" }
        app.repository.download(image) { }
        app.repository.download(document) { }
        app.getSharedPreferences("display_preferences", Context.MODE_PRIVATE).edit()
            .putBoolean("lock_chat_rotation", true).commit()
        launch()
        val model = activeModel()
        unlock()
        compose.waitUntil(15000) { model.mayShowChat && model.user != null }
        scenario!!.onActivity { model.openConversation(cid) }
        compose.waitUntil(15000) { model.destination == Screen.Chat && !model.isWorking(Operation.Conversation) }
        for (screen in Screen.entries.filter { it != Screen.Preview }) {
            scenario!!.onActivity { model.navigate(screen) }
            assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "Global lock must cover ${screen.route}")
            scenario!!.onActivity { assertEquals(screen, model.destination) }
        }
        fun showAttachment(message: com.arcxya09.touch.data.ChatMessage) {
            scenario!!.onActivity { model.navigate(Screen.Chat); model.openFile(message) }
            compose.waitUntil(15000) {
                model.destination == Screen.Preview && model.preview?.first?.id == message.file!!.id && !model.isWorking(Operation.Attachment)
            }
        }
        showAttachment(image)
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "Images stay locked until their exception is enabled")
        scenario!!.onActivity { model.setImagePreviewRotation(true) }
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, "A visible image is the only allowed rotation exception")
        scenario!!.onActivity { model.setImagePreviewRotation(false) }
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "Turning off the image exception restores portrait immediately")
        scenario!!.onActivity { model.setImagePreviewRotation(true); model.back() }
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "Leaving the image viewer must restore the global lock")
        scenario!!.onActivity { assertEquals(Screen.Chat, model.destination); assertNull(model.preview) }
        showAttachment(document)
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "A document cannot use the image rotation exception")
        scenario!!.onActivity { model.setRotationLocked(false) }
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, "Disabling the global lock releases a document viewer")
        scenario!!.onActivity { model.setRotationLocked(true) }
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "Re-enabling the lock restores the document viewer to portrait")
        showAttachment(image)
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, "The image exception remains available for the next image visit")
        scenario!!.onActivity { model.hide() }
        compose.onNodeWithTag("timer-screen").assertIsDisplayed()
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "Privacy hiding must immediately end the image rotation exception")
        scenario!!.onActivity { assertTrue(model.locked); assertNull(model.preview) }
    }
    @Test fun coldStartHidesLoginUntilGesture() {
        launch()
        compose.onNodeWithTag("timer-screen").assertIsDisplayed()
        compose.onNodeWithText("欢迎回来").assertDoesNotExist()
        unlock()
        compose.onNodeWithText("欢迎回来").assertIsDisplayed()
        scenario!!.onActivity { assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
    }
    @Test fun backgroundAndRecreationAlwaysRelock() {
        launch(); unlock()
        compose.onNodeWithText("欢迎回来").assertIsDisplayed()
        scenario!!.moveToState(Lifecycle.State.CREATED)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("timer-screen").assertIsDisplayed()
        compose.onNodeWithText("欢迎回来").assertDoesNotExist()
        unlock()
        scenario!!.recreate()
        compose.onNodeWithTag("timer-screen").assertIsDisplayed()
    }
    @Test fun markerWithMissingConfigurationFailsClosed() = runBlocking<Unit> {
        app.secureStore.write("privacy", null)
        launch()
        compose.onNodeWithTag("timer-screen").assertIsDisplayed()
        unlock()
        compose.onNodeWithText("欢迎回来").assertDoesNotExist()
    }
    @Test fun rotationReflowsWithoutRecreationAndBackgroundStillLocks() {
        launch(); unlock()
        var original: MainActivity? = null
        scenario!!.onActivity {
            original = it
        }
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            automation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90)
            compose.waitUntil(10000) {
                original!!.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
            scenario!!.onActivity { assertSame(original, it) }
            compose.onNodeWithText("欢迎回来").assertIsDisplayed()
            scenario!!.moveToState(Lifecycle.State.CREATED)
            scenario!!.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithTag("timer-screen").assertIsDisplayed()
        } finally {
            automation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
        }
    }
    @Test fun syncRowsAndCursorRollbackTogether() {
        val cache = app.database.cache()
        val previousCursor = cache.get("meta", "cursor")?.json
        val id = "rollback-" + java.util.UUID.randomUUID()
        try {
            app.database.runInTransaction {
                cache.put(TouchDatabase.Item("message", id, "{}"))
                cache.put(TouchDatabase.Item("meta", "cursor", "100"))
                throw IllegalStateException("simulated crash before commit")
            }
        } catch (_: IllegalStateException) { }
        assertNull(cache.get("message", id))
        assertEquals(previousCursor, cache.get("meta", "cursor")?.json)
    }
    @Test fun expiredTimerDoesNotRingOnColdStart() {
        app.getSharedPreferences("pomodoro", Context.MODE_PRIVATE).edit()
            .putBoolean("running", true).putLong("wallEnd", 1L).putInt("boot", -100).commit()
        launch()
        val state = com.arcxya09.touch.timer.Pomodoro(app).state()
        assertFalse(state.running)
        assertTrue(state.complete)
        val manager = app.getSystemService(android.app.NotificationManager::class.java)
        assertTrue(manager.activeNotifications.none { it.id == 25 })
    }
}
