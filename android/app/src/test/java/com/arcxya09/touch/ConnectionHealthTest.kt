package com.arcxya09.touch
import com.arcxya09.touch.data.ConnectionHealth
import com.arcxya09.touch.data.ConnectionStatus
import org.junit.Assert.*
import org.junit.Test

class ConnectionHealthTest {
    @Test fun httpSuccessAloneCannotClaimLiveConnection() {
        val health = ConnectionHealth { 1000 }
        health.success(health.begin(), 10)
        assertEquals(ConnectionStatus.CONNECTING, health.status())
        health.opened()
        assertEquals(ConnectionStatus.LIVE, health.status())
        health.invalidate()
        assertEquals(ConnectionStatus.RETRYING, health.status())
    }
    @Test fun lateSuccessCannotUndoNetworkLossOrForegroundRecheck() {
        val health = ConnectionHealth { 1000 }
        health.opened(); val old = health.begin()
        health.invalidate(); health.success(old, 10)
        assertEquals(ConnectionStatus.RETRYING, health.status())
        health.opening(); health.opened()
        assertEquals(ConnectionStatus.SYNCING, health.status())
        health.success(health.begin(), 10)
        assertEquals(ConnectionStatus.LIVE, health.status())
    }
    @Test fun newServerCursorDuringSyncRequiresCatchup() {
        val health = ConnectionHealth { 1000 }
        health.opened(); health.event(12)
        health.success(health.begin(), 11)
        assertEquals(ConnectionStatus.SYNCING, health.status())
        health.success(health.begin(), 12)
        assertEquals(ConnectionStatus.LIVE, health.status())
        health.event(13)
        assertEquals(ConnectionStatus.SYNCING, health.status())
    }
    @Test fun idleSocketStaysLiveUntilTransportOrSyncFails() {
        var time = 1000L
        val health = ConnectionHealth { time }
        health.opened(); health.success(health.begin(), 10)
        time += ConnectionHealth.SYNC_FALLBACK_MS * 2
        assertEquals(ConnectionStatus.LIVE, health.status())
        health.failure(health.begin())
        assertEquals(ConnectionStatus.RETRYING, health.status())
    }
}
