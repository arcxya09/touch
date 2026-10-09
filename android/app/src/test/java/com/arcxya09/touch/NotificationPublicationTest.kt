package com.arcxya09.touch

import com.arcxya09.touch.notifications.NotificationPublication
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NotificationPublicationTest {
    @Test fun delayedContentCannotReturnAfterClearOrConcealment() {
        val publication = NotificationPublication()
        val oldMessage = publication.ticket()
        var visible: String? = "original content"
        publication.invalidate { visible = null }
        assertFalse(publication.publish(oldMessage) { visible = "recalled content" })
        assertNull(visible)

        // A screen-off event also invalidates a read that has not posted anything yet.
        val inFlight = publication.ticket()
        publication.invalidate { assertNull(visible) }
        assertFalse(publication.publish(inFlight) { visible = "late content" })
        assertNull(visible)

        assertTrue(publication.publish(publication.ticket()) { visible = "new message" })
        assertEquals("new message", visible)
    }

    @Test fun clearingCannotSlipBetweenTheLastCheckAndPublication() {
        val publication = NotificationPublication()
        val ticket = publication.ticket()
        val publishing = CountDownLatch(1)
        val finishPublishing = CountDownLatch(1)
        val clearRequested = CountDownLatch(1)
        val cleared = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        var visible: String? = null
        try {
            val writer = workers.submit<Boolean> {
                publication.publish(ticket) {
                    publishing.countDown()
                    check(finishPublishing.await(5, TimeUnit.SECONDS))
                    visible = "message content"
                }
            }
            assertTrue(publishing.await(5, TimeUnit.SECONDS))
            val clear = workers.submit {
                clearRequested.countDown()
                publication.invalidate { visible = null; cleared.countDown() }
            }
            assertTrue(clearRequested.await(5, TimeUnit.SECONDS))
            assertFalse("Clearing must wait until the atomic publication finishes", cleared.await(100, TimeUnit.MILLISECONDS))
            finishPublishing.countDown()
            assertTrue(writer.get(5, TimeUnit.SECONDS))
            clear.get(5, TimeUnit.SECONDS)
            assertNull("The completed clear must remain the final visible state", visible)
            assertFalse(publication.publish(ticket) { visible = "stale content" })
            assertNull(visible)
        } finally {
            finishPublishing.countDown()
            workers.shutdownNow()
        }
    }
}
