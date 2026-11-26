package com.danielbrog.activitystats.sync

import com.danielbrog.activitystats.account.AccountHttp.HttpException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** What happened to a summary sent with `PUT /activities/{id}`. */
sealed interface PutResult {
    /** Saved: the cloud now has this copy. */
    data object Applied : PutResult

    /** The cloud's copy is newer (a 409), so it was kept; here it is. */
    data class Newer(val cloud: CloudCopy) : PutResult

    /** The server refused this ride; retrying won't help. */
    data class Rejected(val code: Int) : PutResult
}

/** What happened to a segment sent with `PUT /segments/{id}`. */
sealed interface SegmentPutResult {
    /** Saved: the cloud now has this copy. */
    data object Applied : SegmentPutResult

    /** The cloud's copy is newer (a 409), so it was kept; here it is. */
    data class Newer(val cloud: CloudSegment) : SegmentPutResult

    /**
     * The cloud's copy has a different line or sport (a 400 with the copy). A line never changes
     * on a phone, so this means the two disagree; the cloud's copy is taken whole.
     */
    data class LineChanged(val cloud: CloudSegment) : SegmentPutResult

    /** The server refused this segment; retrying won't help. */
    data class Rejected(val code: Int) : SegmentPutResult
}

/**
 * The server refused a request for one ride in a way retrying won't fix. Only that ride is
 * skipped. Network trouble is an [IOException] instead, which means "try again later".
 */
class RejectedException(val code: Int) : Exception("Rejected with HTTP $code")

/**
 * This account has uploaded its new rides for the week; the server counts again from
 * [resetsAt] (Monday 00:00 UTC). Not a failure: the ride waits, and goes up after that.
 */
class RideLimitReachedException(val resetsAt: Long) : Exception("Weekly ride limit reached")

/**
 * This account has downloaded its tracks for the day; the server counts again from
 * [resetsAt] (the next 00:00 UTC). Not a failure: the download stops and carries on after that.
 */
class DownloadLimitReachedException(val resetsAt: Long) : Exception("Daily download limit reached")

/**
 * This account has saved its edits for the day (renames and deletes of rides, segment saves);
 * the server counts again from [resetsAt] (the next 00:00 UTC). Not a failure: the edit waits,
 * and goes up after that.
 */
class EditLimitReachedException(val resetsAt: Long) : Exception("Daily edit limit reached")

/**
 * A usage limit's reply: which limit ([kind]: `rides`, `downloads` or `edits`; null from a
 * backend from before kinds, which only had the ride limit), and when it resets.
 */
internal class UsageLimit(val kind: String?, val resetsAt: Long)

/**
 * The usage limit [body] reports, if it is a limit's reply: a 429 with `limit` and `resetsAt`.
 * Null for anything else, like API Gateway's own rate-limit 429, which only means "slow down"
 * and is retried. Plain Kotlin, unit-tested.
 */
internal fun usageLimitOf(code: Int, body: String): UsageLimit? {
    if (code != 429) return null
    // Neither call suspends, so runCatching can't swallow a coroutine cancellation.
    val reply = runCatching { RideSummary.json.decodeFromString(LimitJson.serializer(), body) }.getOrNull()
    val resetsAt = reply?.resetsAt ?: return null
    return if (reply.limit != null) UsageLimit(reply.kind, resetsAt) else null
}

/**
 * The exception for a limit's reply to a ride summary, or null for any other reply. A summary
 * can reach either limit: a new ride the weekly ride limit, a rename or delete the daily edit
 * limit. A reply without a kind is the ride limit, the only one an older backend had. Any
 * other kind holds back just this ride, as an edit does, rather than every new ride.
 */
internal fun summaryLimitOf(code: Int, body: String): Exception? = usageLimitOf(code, body)?.let {
    when (it.kind) {
        null, RIDES_KIND -> RideLimitReachedException(it.resetsAt)
        else -> EditLimitReachedException(it.resetsAt)
    }
}

private const val RIDES_KIND = "rides"

@Serializable
private class LimitJson(val limit: Int? = null, val kind: String? = null, val resetsAt: Long? = null)

/**
 * The API didn't accept the sign-in token (a 401). The worker asks for a new token before
 * the next try, rather than retrying with the same one.
 */
class SignInRejectedException : IOException("Sign-in token not accepted")

/** How to treat a reply's status code. */
internal enum class Outcome { Ok, Retry, Skip }

/**
 * Sorts a status code into success, try again later, or give up on this ride. Plain Kotlin,
 * unit-tested, since getting it wrong either drops rides or gets them stuck.
 */
internal fun outcomeOf(code: Int): Outcome = when {
    code in 200..299 -> Outcome.Ok
    // Sign-in trouble (a new token is fetched next run), timeouts, rate limits, server errors.
    code == 401 || code == 403 || code == 408 || code == 429 || code >= 500 -> Outcome.Retry
    else -> Outcome.Skip
}

/** The exception for a reply worth retrying: a new sign-in for a 401, a plain HTTP error otherwise. */
internal fun retryFor(code: Int): IOException = if (code == 401) SignInRejectedException() else HttpException(code)

/**
 * What a reply to `PUT /segments/{id}` means. Plain Kotlin, unit-tested. A 409 carrying the
 * stored copy means the cloud's is newer; a 400 carrying it means its line or sport differs.
 * Either without a copy, like any other 4xx, is a refusal. Throws [EditLimitReachedException]
 * past the day's edits (every segment save is one), and an [IOException] for a reply worth
 * retrying.
 */
internal fun segmentPutResultOf(code: Int, body: String): SegmentPutResult {
    usageLimitOf(code, body)?.let { throw EditLimitReachedException(it.resetsAt) }
    if (code == 409) {
        return parseSegmentConflict(body)?.let { SegmentPutResult.Newer(it) } ?: SegmentPutResult.Rejected(code)
    }
    if (code == 400) parseSegmentConflict(body)?.let { return SegmentPutResult.LineChanged(it) }
    return when (outcomeOf(code)) {
        Outcome.Ok -> SegmentPutResult.Applied
        Outcome.Retry -> throw retryFor(code)
        Outcome.Skip -> SegmentPutResult.Rejected(code)
    }
}

/**
 * What a reply to `GET /activities/{id}/track` means. Plain Kotlin, unit-tested: the file's
 * bytes, or null if it has none yet (404). Throws [DownloadLimitReachedException] past the
 * day's limit, an [IOException] for a reply worth retrying (API Gateway's own 429 among them),
 * and [RejectedException] for a file the server won't send, like one too large (413).
 */
internal fun trackReplyOf(code: Int, body: ByteArray): ByteArray? {
    if (code == 404) return null
    // Only a 429 is read as text: a track file can be megabytes.
    if (code == 429) usageLimitOf(code, body.decodeToString())?.let { throw DownloadLimitReachedException(it.resetsAt) }
    return when (outcomeOf(code)) {
        Outcome.Ok -> body
        Outcome.Retry -> throw retryFor(code)
        Outcome.Skip -> throw RejectedException(code)
    }
}

/** The sync API calls the upload and download need. An interface, so tests can use a fake server. */
interface SyncApi {
    suspend fun putActivity(id: String, summary: RideSummary): PutResult

    /**
     * Sends ride [id]'s gzipped track file. The server checks it (size, format, sane values)
     * before storing it, and refuses a file it won't store with a 4xx.
     */
    suspend fun uploadTrack(id: String, bytes: ByteArray)

    /**
     * One page of the rides saved after [since] (server time), oldest first. [page] is the
     * previous page's `nextCursor`, or null for the first page; [since] stays the same across pages.
     */
    suspend fun listActivities(since: Long, page: String?): CloudPage

    /**
     * Ride [id]'s gzipped track file, or null if it has none yet (404). Each download counts
     * towards the day's limit; past it, throws [DownloadLimitReachedException].
     */
    suspend fun downloadTrack(id: String): ByteArray?
}

/** The segment sync calls. Separate from [SyncApi], so the ride tests' fakes don't need them. */
interface SegmentSyncApi {
    suspend fun putSegment(id: String, segment: SegmentUpload): SegmentPutResult

    /**
     * One page of the segment and review items saved after [since] (server time), oldest first.
     * [page] is the previous page's `nextCursor`, or null for the first; [since] stays the same.
     */
    suspend fun listSegments(since: Long, page: String?): CloudSegmentPage
}

/**
 * [SyncApi] and [SegmentSyncApi] over HTTPS with [HttpURLConnection], like
 * [com.danielbrog.activitystats.account.AccountHttp]. [accessToken] must be fresh: it lasts an
 * hour, longer than one sync takes.
 */
class HttpSyncApi(private val apiUrl: String, private val accessToken: String) :
    SyncApi,
    SegmentSyncApi {

    override suspend fun putActivity(id: String, summary: RideSummary): PutResult {
        val (code, reply) = request("PUT", "$apiUrl/activities/$id", summary.toJson().toByteArray(), JSON_HEADERS)
        val body = reply.decodeToString()
        summaryLimitOf(code, body)?.let { throw it }
        if (code == 409) return parseConflict(body)?.let { PutResult.Newer(it) } ?: PutResult.Rejected(code)
        return when (outcomeOf(code)) {
            Outcome.Ok -> PutResult.Applied
            Outcome.Retry -> throw retryFor(code)
            Outcome.Skip -> PutResult.Rejected(code)
        }
    }

    override suspend fun uploadTrack(id: String, bytes: ByteArray) {
        val headers = mapOf("Content-Type" to TrackFile.CONTENT_TYPE)
        val (code, reply) = request("PUT", "$apiUrl/activities/$id/track", bytes, headers)
        usageLimitOf(code, reply.decodeToString())?.let { throw RideLimitReachedException(it.resetsAt) }
        throwUnlessOk(code)
    }

    override suspend fun listActivities(since: Long, page: String?): CloudPage {
        val cursor = page?.let { "&cursor=" + URLEncoder.encode(it, Charsets.UTF_8.name()) } ?: ""
        val (code, body) = request("GET", "$apiUrl/activities?since=$since$cursor", null, emptyMap())
        throwUnlessOk(code)
        return parsePage(body.decodeToString())
    }

    override suspend fun downloadTrack(id: String): ByteArray? {
        val (code, body) = request("GET", "$apiUrl/activities/$id/track", null, emptyMap())
        return trackReplyOf(code, body)
    }

    override suspend fun putSegment(id: String, segment: SegmentUpload): SegmentPutResult {
        val (code, reply) = request("PUT", "$apiUrl/segments/$id", segment.toJson().toByteArray(), JSON_HEADERS)
        return segmentPutResultOf(code, reply.decodeToString())
    }

    override suspend fun listSegments(since: Long, page: String?): CloudSegmentPage {
        val cursor = page?.let { "&cursor=" + URLEncoder.encode(it, Charsets.UTF_8.name()) } ?: ""
        val (code, body) = request("GET", "$apiUrl/segments?since=$since$cursor", null, emptyMap())
        throwUnlessOk(code)
        return parseSegmentPage(body.decodeToString())
    }

    /**
     * Sends one request and returns the status code and the reply's bytes. Only sends a body
     * when there is one: opening one at all turns a GET into a POST in [HttpURLConnection].
     */
    private suspend fun request(
        method: String,
        url: String,
        body: ByteArray?,
        headers: Map<String, String>,
    ): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            code to (stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }

    /** Returns on success; otherwise throws the exception that says whether to retry or skip. */
    private fun throwUnlessOk(code: Int) {
        when (outcomeOf(code)) {
            Outcome.Ok -> Unit
            Outcome.Retry -> throw retryFor(code)
            Outcome.Skip -> throw RejectedException(code)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000
        val JSON_HEADERS = mapOf("Content-Type" to "application/json")
    }
}
