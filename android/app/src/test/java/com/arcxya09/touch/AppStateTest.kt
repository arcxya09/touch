package com.arcxya09.touch

import org.junit.Assert.*
import org.junit.Test

class AppStateTest {
    @Test fun progressPublishesAtMostTenUpdatesPerSecondAndAlwaysPublishesCompletion() {
        var now = 0L
        val updates = ProgressUpdates { now }
        assertTrue(updates.accept(0f))
        for (time in 1L..99L) { now = time; assertFalse(updates.accept(0.5f)) }
        now = 100
        assertTrue(updates.accept(0.6f))
        now = 101
        assertTrue(updates.accept(1f))
    }
    @Test fun settingsChildrenAndPreviewHaveOneConsistentBackDestination() {
        listOf(Screen.Profile, Screen.Password, Screen.Notifications, Screen.PrivacySecurity, Screen.LocalData, Screen.About)
            .forEach { assertEquals(Screen.Settings, it.parent()) }
        assertEquals(Screen.Chat, Screen.Preview.parent())
        assertEquals(Screen.Home, Screen.Chat.parent())
        Screen.entries.forEach { assertEquals(it, Screen.fromRoute(it.route)) }
    }

    @Test fun returningToSameConversationDoesNotAcceptResultsFromPreviousVisit() {
        val requests = ConversationRequests()
        val firstVisit = requests.ticket("alice", "a")
        assertTrue(requests.accepts(firstVisit, "alice", "a"))
        requests.invalidate()
        val secondVisit = requests.ticket("alice", "b")
        assertFalse(requests.accepts(firstVisit, "alice", "b"))
        requests.invalidate()
        assertFalse(requests.accepts(firstVisit, "alice", "a"))
        assertFalse(requests.accepts(secondVisit, "alice", "a"))
        assertTrue(requests.accepts(requests.ticket("alice", "a"), "alice", "a"))
    }

    @Test fun accountChangeAndSignOutRejectLateResultsEvenBeforeNavigationChanges() {
        val requests = ConversationRequests()
        val ticket = requests.ticket("alice", "shared-id")
        assertFalse(requests.accepts(ticket, "bob", "shared-id"))
        assertFalse(requests.accepts(ticket, null, "shared-id"))
        requests.invalidate()
        assertFalse(requests.accepts(ticket, "alice", "shared-id"))
    }

    @Test fun exportAuthorizationIsBoundToAccountAndExpiresAtBoundary() {
        val request = PendingExport("alice", "conversation", "message", 5000)
        assertTrue(request.validFor("alice", 4999))
        assertFalse(request.validFor("alice", 5000))
        assertFalse(request.validFor("bob", 1000))
        assertFalse(request.validFor(null, 1000))
    }
}
