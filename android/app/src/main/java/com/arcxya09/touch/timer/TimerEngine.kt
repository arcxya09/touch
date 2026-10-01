package com.arcxya09.touch.timer

/** All deadlines are resolved from an injected clock; no Android APIs enter the timer rules. */
internal interface TimerClock {
    fun elapsedMs(): Long
    fun wallMs(): Long
    fun bootCount(): Int
}
internal interface TimerStorage {
    fun read(): TimerRecord
    fun write(record: TimerRecord)
}
internal interface TimerAlarm {
    fun schedule(elapsedDeadlineMs: Long)
    fun cancel()
}
internal data class TimerRecord(
    val focusMinutes: Int = 25,
    val restMinutes: Int = 5,
    val resting: Boolean = false,
    val running: Boolean = false,
    val remainingMs: Long = 25 * 60000L,
    val complete: Boolean = false,
    val elapsedEndMs: Long = 0,
    val wallEndMs: Long = 0,
    val bootCount: Int = -2,
) {
    val durationMs: Long get() = (if (resting) restMinutes else focusMinutes) * 60000L
}

internal class TimerEngine(private val storage: TimerStorage, private val clock: TimerClock, private val alarm: TimerAlarm) {
    private fun remaining(record: TimerRecord): Long = (if (record.running) {
        if (record.bootCount == clock.bootCount()) record.elapsedEndMs - clock.elapsedMs()
        else record.wallEndMs - clock.wallMs()
    } else record.remainingMs).coerceIn(0, record.durationMs)

    fun state(): TimerState = storage.read().let { record ->
        TimerState(record.focusMinutes, record.restMinutes, record.resting, record.running, remaining(record), record.complete)
    }
    fun configure(focus: Int, rest: Int) {
        require(focus in 1..180 && rest in 1..60)
        reset(storage.read().copy(focusMinutes = focus, restMinutes = rest))
    }
    fun choose(resting: Boolean) = reset(storage.read().copy(resting = resting))
    fun reset() = reset(storage.read())
    private fun reset(record: TimerRecord) {
        storage.write(record.copy(running = false, remainingMs = record.durationMs, complete = false))
        alarm.cancel()
    }
    fun start() {
        val record = storage.read()
        if (record.running) return
        val duration = remaining(record).takeIf { it > 0 } ?: record.durationMs
        val end = clock.elapsedMs() + duration
        storage.write(record.copy(running = true, complete = false, elapsedEndMs = end, wallEndMs = clock.wallMs() + duration, bootCount = clock.bootCount()))
        alarm.schedule(end)
    }
    fun pause() {
        val record = storage.read()
        storage.write(record.copy(running = false, remainingMs = remaining(record)))
        alarm.cancel()
    }
    /** Rebase after reboot/clock changes; expired timers are completed silently. */
    fun schedule() {
        val record = storage.read()
        if (!record.running) return
        val duration = remaining(record)
        if (duration == 0L) { finish(); return }
        val end = clock.elapsedMs() + duration
        storage.write(record.copy(elapsedEndMs = end, wallEndMs = clock.wallMs() + duration, bootCount = clock.bootCount()))
        alarm.schedule(end)
    }
    /** Returns true only for the first valid completion, never a stale alarm from a previous run. */
    fun finish(): Boolean {
        val record = storage.read()
        if (!record.running) return false
        if (remaining(record) > 1000) { schedule(); return false }
        storage.write(record.copy(running = false, complete = true, remainingMs = 0))
        alarm.cancel()
        return true
    }
}
