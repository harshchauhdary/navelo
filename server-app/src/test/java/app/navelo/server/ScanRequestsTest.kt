package app.navelo.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanRequestsTest {
    @Test fun duplicatePendingRequestsShareTicketAndUnchangedScanStillCompletes() {
        val tracker = ScanRequests()
        val first = tracker.reserve()
        assertTrue(first.start)
        assertEquals(0L, tracker.status().completed)
        val duplicate = tracker.reserve()
        assertFalse(duplicate.start)
        assertEquals(first.generation, duplicate.generation)
        tracker.complete(first.generation, null)
        assertEquals(first.generation, tracker.status().completed)
        assertNull(tracker.status().error)
        assertTrue(tracker.reserve().start)
    }

    @Test fun staleCompletionCannotOverwriteNewScanStatus() {
        val tracker = ScanRequests()
        val first = tracker.reserve()
        tracker.complete(first.generation, "Scan failed")
        assertEquals("Scan failed", tracker.status().error)
        val second = tracker.reserve()
        assertNull(tracker.status().error)
        tracker.complete(first.generation, "Old failure")
        assertEquals(first.generation, tracker.status().completed)
        assertNull(tracker.status().error)
        tracker.complete(second.generation, null)
        assertEquals(second.generation, tracker.status().completed)
    }
}
