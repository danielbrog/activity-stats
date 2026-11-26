package com.danielbrog.activitystats.sync

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
        val rides = """{"error":"Weekly ride limit reached","limit":10,"resetsAt":1795996800000}"""
        assertEquals(1_795_996_800_000L, limitResetsAt(429, rides))
        val downloads = """{"error":"Daily download limit reached","limit":500,"resetsAt":1795651200000}"""
        assertEquals(1_795_651_200_000L, limitResetsAt(429, downloads))
        // API Gateway's own rate limit: also a 429, but only "slow down", so it is retried.
        assertNull(limitResetsAt(429, """{"message":"Too Many Requests"}"""))
        assertNull(limitResetsAt(429, "<html>busy</html>"))
        assertNull(limitResetsAt(400, rides))
        assertEquals(Outcome.Retry, outcomeOf(429))
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
