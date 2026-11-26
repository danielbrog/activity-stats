package com.danielbrog.activitystats.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which replies are retried and which skip the ride: wrong either way and rides get stuck or dropped. */
class OutcomeTest {

    @Test
    fun successIsOk() {
        listOf(200, 201, 204).forEach { assertEquals("$it", Outcome.Ok, outcomeOf(it)) }
    }

    @Test
    fun temporaryTroubleIsRetried() {
        // Sign-in, timeout, rate limit, server errors.
        listOf(401, 403, 408, 429, 500, 502, 503).forEach { assertEquals("$it", Outcome.Retry, outcomeOf(it)) }
    }

    @Test
    fun aRefusalOfThisRideIsSkipped() {
        listOf(400, 404, 413, 422).forEach { assertEquals("$it", Outcome.Skip, outcomeOf(it)) }
    }

    @Test
    fun s3RefusingAFreshDownloadLinkSkipsTheRide() {
        // The link is seconds old, so a 403 is about this file and won't change on retry.
        assertEquals(Outcome.Skip, outcomeOf(403, fromS3 = true))
        assertEquals(Outcome.Retry, outcomeOf(503, fromS3 = true))
        assertEquals(Outcome.Ok, outcomeOf(200, fromS3 = true))
    }

    @Test
    fun aConflictReplyThatIsNotJsonGivesNoCopy() {
        assertNull(parseConflict("<html>Bad gateway</html>"))
    }
}
