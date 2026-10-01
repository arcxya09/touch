package com.arcxya09.touch

import com.arcxya09.touch.timer.*
import org.junit.Assert.*
import org.junit.Test

class TimerEngineTest {
    private class Clock(var elapsed: Long = 10_000, var wall: Long = 1_000_000, var boot: Int = 4) : TimerClock {
        override fun elapsedMs() = elapsed
        override fun wallMs() = wall
        override fun bootCount() = boot
        fun advance(ms: Long) { elapsed += ms; wall += ms }
    }
    private class Store(var record: TimerRecord = TimerRecord()) : TimerStorage {
        override fun read() = record
        override fun write(record: TimerRecord) { this.record = record }
    }
    private class Alarm : TimerAlarm {
        var deadline: Long? = null
        override fun schedule(elapsedDeadlineMs: Long) { deadline = elapsedDeadlineMs }
        override fun cancel() { deadline = null }
    }
    @Test fun wallClockChangesDoNotAffectSameBootCountdown() {
        val clock = Clock(); val store = Store(); val alarm = Alarm(); val timer = TimerEngine(store, clock, alarm)
        timer.start(); clock.advance(60_000); clock.wall += 6 * 60 * 60_000
        assertEquals(24 * 60_000L, timer.state().remainingMs)
        timer.schedule()
        assertEquals(clock.elapsed + 24 * 60_000L, alarm.deadline)
    }
    @Test fun pauseAndResumePreserveOnlyRemainingTime() {
        val clock = Clock(); val store = Store(); val alarm = Alarm(); val timer = TimerEngine(store, clock, alarm)
        timer.start(); clock.advance(120_000); timer.pause(); clock.advance(400_000)
        assertEquals(23 * 60_000L, timer.state().remainingMs); assertNull(alarm.deadline)
        timer.start(); assertEquals(clock.elapsed + 23 * 60_000L, alarm.deadline)
    }
    @Test fun rebootRebasesFromWallDeadlineAndExpiredScheduleIsSilent() {
        val clock = Clock(); val store = Store(); val alarm = Alarm(); val timer = TimerEngine(store, clock, alarm)
        timer.start(); clock.boot++; clock.elapsed = 100; clock.wall += 60_000
        timer.schedule(); assertEquals(clock.elapsed + 24 * 60_000L, alarm.deadline)
        clock.advance(24 * 60_000); timer.schedule()
        assertTrue(timer.state().complete); assertFalse(timer.state().running); assertNull(alarm.deadline)
        assertFalse(timer.finish())
    }
    @Test fun oldAndDuplicateAlarmCannotEndNewSession() {
        val clock = Clock(); val store = Store(); val alarm = Alarm(); val timer = TimerEngine(store, clock, alarm)
        timer.start(); clock.advance(25 * 60_000); assertTrue(timer.finish()); assertFalse(timer.finish())
        timer.start(); assertFalse(timer.finish()); assertTrue(timer.state().running)
        assertEquals(clock.elapsed + 25 * 60_000L, alarm.deadline)
    }
    @Test fun changingPhaseOrDurationResetsSessionAndCancelsAlarm() {
        val clock = Clock(); val store = Store(); val alarm = Alarm(); val timer = TimerEngine(store, clock, alarm)
        timer.start(); timer.choose(true)
        assertEquals(5 * 60_000L, timer.state().remainingMs); assertNull(alarm.deadline)
        timer.configure(50, 10); assertEquals(10 * 60_000L, timer.state().remainingMs)
        assertThrows(IllegalArgumentException::class.java) { timer.configure(0, 10) }
    }
}
