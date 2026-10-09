package com.arcxya09.touch.notifications

/** Serializes notification publication with concealment, including work still reading local data. */
internal class NotificationPublication {
    private var generation = 0L

    @Synchronized fun ticket(): Long = generation

    @Synchronized fun invalidate(clear: () -> Unit) {
        generation++
        clear()
    }

    @Synchronized fun publish(ticket: Long, notify: () -> Unit): Boolean {
        if (ticket != generation) return false
        notify()
        return true
    }
}
