package com.danielbrog.activitystats.sync

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
    fun aUsageLimitReplyIsToldApartFromARateLimit() {
        val rides = """{"error":"Weekly ride limit reached","kind":"rides","limit":10,"resetsAt":1795996800000}"""
        val ridesLimit = usageLimitOf(429, rides)!!
        assertEquals("rides", ridesLimit.kind)
        assertEquals(1_795_996_800_000L, ridesLimit.resetsAt)
        val downloads = """{"kind":"downloads","limit":500,"resetsAt":1795651200000}"""
        assertEquals(1_795_651_200_000L, usageLimitOf(429, downloads)!!.resetsAt)
        // From the backend before kinds: still a limit, of no named kind.
        assertNull(usageLimitOf(429, """{"limit":10,"resetsAt":1795996800000}""")!!.kind)
        // API Gateway's own rate limit: also a 429, but only "slow down", so it is retried.
        assertNull(usageLimitOf(429, """{"message":"Too Many Requests"}"""))
        assertNull(usageLimitOf(429, "<html>busy</html>"))
        assertNull(usageLimitOf(400, rides))
        assertEquals(Outcome.Retry, outcomeOf(429))
    }

    @Test
    fun aSummaryLimitIsTheEditLimitOrTheRideLimit() {
        val edits = """{"error":"Daily edit limit reached","kind":"edits","limit":200,"resetsAt":1795651200000}"""
        assertEquals(1_795_651_200_000L, (summaryLimitOf(429, edits) as EditLimitReachedException).resetsAt)
        val rides = """{"error":"Weekly ride limit reached","kind":"rides","limit":10,"resetsAt":1795996800000}"""
        assertEquals(1_795_996_800_000L, (summaryLimitOf(429, rides) as RideLimitReachedException).resetsAt)
        // No kind: the ride limit, the only one an older backend had.
        assertTrue(summaryLimitOf(429, """{"limit":10,"resetsAt":1}""") is RideLimitReachedException)
        // A kind from a newer backend holds back just this ride, not every new one.
        assertTrue(summaryLimitOf(429, """{"kind":"something","limit":5,"resetsAt":1}""") is EditLimitReachedException)
        assertNull(summaryLimitOf(429, """{"message":"Too Many Requests"}"""))
    }

    @Test
    fun aSegmentSavePastTheEditLimitWaitsForTheReset() {
        val edits = """{"error":"Daily edit limit reached","kind":"edits","limit":200,"resetsAt":1795651200000}"""
        val limited = assertThrows(EditLimitReachedException::class.java) { segmentPutResultOf(429, edits) }
        assertEquals(1_795_651_200_000L, limited.resetsAt)
        // API Gateway's own rate limit is still just retried.
        assertThrows(IOException::class.java) { segmentPutResultOf(429, """{"message":"Too Many Requests"}""") }
    }

    @Test
    fun aTrackReplyIsTheFileOrSaysWhyNot() {
        val file = byteArrayOf(0x1f, 0x8b.toByte(), 8)
        assertArrayEquals(file, trackReplyOf(200, file))
        // Not uploaded yet.
        assertNull(trackReplyOf(404, ByteArray(0)))
        // The day's downloads used up: wait for the reset.
        val limit = """{"error":"Daily download limit reached","limit":500,"resetsAt":1795651200000}"""
        val limited = assertThrows(DownloadLimitReachedException::class.java) { trackReplyOf(429, limit.toByteArray()) }
        assertEquals(1_795_651_200_000L, limited.resetsAt)
        // API Gateway's own rate limit and server trouble: retried.
        assertThrows(IOException::class.java) { trackReplyOf(429, """{"message":"Too Many Requests"}""".toByteArray()) }
        assertThrows(IOException::class.java) { trackReplyOf(503, ByteArray(0)) }
        assertThrows(SignInRejectedException::class.java) { trackReplyOf(401, ByteArray(0)) }
        // Too large to send: that ride is skipped.
        assertEquals(413, assertThrows(RejectedException::class.java) { trackReplyOf(413, ByteArray(0)) }.code)
    }

    @Test
    fun aConflictReplyThatIsNotJsonGivesNoCopy() {
        assertNull(parseConflict("<html>Bad gateway</html>"))
    }
}
