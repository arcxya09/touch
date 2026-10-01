package com.arcxya09.touch.benchmark

import android.content.Intent

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.arcxya09.touch.benchmark.tests.BuildConfig
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// Macrobenchmark instrumentation runs in its own APK; targetContext points at the runner.
private const val PACKAGE = BuildConfig.TARGET_PACKAGE

/** Only the benchmark variant is targeted; no production account or network is required. */
class BaselineProfiles {
    @get:Rule val profile = BaselineProfileRule()
    @Test fun timer() = profile.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        if (android.os.Build.VERSION.SDK_INT >= 33) device.executeShellCommand("pm grant $PACKAGE android.permission.POST_NOTIFICATIONS")
        pressHome()
        startActivityAndWait(Intent().setClassName(PACKAGE, "com.arcxya09.touch.MainActivity")
            .putExtra("timer", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("开始")), 15000))
        device.findObject(By.text("开始")).click()
        assertTrue(device.wait(Until.hasObject(By.text("暂停")), 5000))
        device.findObject(By.text("暂停")).click()
        assertTrue(device.wait(Until.hasObject(By.text("继续")), 5000))
        device.findObject(By.text("重置")).click()
    }
    @Test fun conversationAndChat() = profile.collect(packageName = PACKAGE, includeInStartupProfile = false) {
        startActivityAndWait(Intent().setClassName(PACKAGE, "com.arcxya09.touch.DesignBenchmarkActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.desc("样板消息列表")), 10000))
        device.findObject(By.scrollable(true)).apply { setGestureMargin(device.displayWidth / 5); fling(androidx.test.uiautomator.Direction.DOWN) }
        device.findObject(By.text("聊天样板")).click()
        assertTrue(device.wait(Until.hasObject(By.desc("样板聊天列表")), 5000))
        device.findObject(By.desc("样板聊天列表")).apply { setGestureMargin(device.displayWidth / 5); fling(androidx.test.uiautomator.Direction.UP) }
    }
}

class StartupBenchmarks {
    @get:Rule val benchmark = MacrobenchmarkRule()
    @Test fun coldTimer() = benchmark.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        iterations = 20,
        setupBlock = {
            if (android.os.Build.VERSION.SDK_INT >= 33) device.executeShellCommand("pm grant $PACKAGE android.permission.POST_NOTIFICATIONS")
            pressHome()
        },
    ) {
        startActivityAndWait(Intent().setClassName(PACKAGE, "com.arcxya09.touch.MainActivity")
            .putExtra("timer", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("开始")), 15000))
    }
}

/** Synthetic rows exercise the real shared list/bubble components without an account. */
class ScrollBenchmarks {
    @get:Rule val benchmark = MacrobenchmarkRule()
    @Test fun conversationAndChatFrames() = benchmark.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 10,
        setupBlock = {
            startActivityAndWait(Intent().setClassName(PACKAGE, "com.arcxya09.touch.DesignBenchmarkActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue(device.wait(Until.hasObject(By.desc("样板消息列表")), 10000))
        },
    ) {
        device.findObject(By.scrollable(true)).apply {
            setGestureMargin(device.displayWidth / 5)
            repeat(3) { fling(androidx.test.uiautomator.Direction.DOWN); fling(androidx.test.uiautomator.Direction.UP) }
        }
        device.findObject(By.text("聊天样板")).click()
        assertTrue(device.wait(Until.hasObject(By.desc("样板聊天列表")), 5000))
        device.findObject(By.desc("样板聊天列表")).apply {
            setGestureMargin(device.displayWidth / 5)
            repeat(3) { fling(androidx.test.uiautomator.Direction.UP); fling(androidx.test.uiautomator.Direction.DOWN) }
        }
    }
}
