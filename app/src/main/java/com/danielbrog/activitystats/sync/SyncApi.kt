package com.danielbrog.activitystats.sync

import com.danielbrog.activitystats.account.AccountHttp.HttpException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

/** A short-lived link to upload one track file to, with the headers the upload must send. */
data class UploadLink(val url: String, val headers: Map<String, String>)

/**
 * The server refused a request for one ride in a way retrying won't fix. Only that ride is
 * skipped. Network trouble is an [IOException] instead, which means "try again later".
 */
class RejectedException(val code: Int) : Exception("Rejected with HTTP $code")

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
 *
 * [fromS3] is for the track upload itself. Its link is only seconds old (it lasts five
 * minutes), so S3's 403 means S3 refused this file, not that a sign-in expired.
 */
internal fun outcomeOf(code: Int, fromS3: Boolean = false): Outcome = when {
    code in 200..299 -> Outcome.Ok
    code == 403 && fromS3 -> Outcome.Skip
    // Sign-in trouble (a new token is fetched next run), timeouts, rate limits, server errors.
    code == 401 || code == 403 || code == 408 || code == 429 || code >= 500 -> Outcome.Retry
    else -> Outcome.Skip
}

/** The exception for a reply worth retrying: a new sign-in for a 401, a plain HTTP error otherwise. */
internal fun retryFor(code: Int): IOException = if (code == 401) SignInRejectedException() else HttpException(code)

/**
 * What a reply to `PUT /segments/{id}` means. Plain Kotlin, unit-tested. A 409 carrying the
 * stored copy means the cloud's is newer; a 400 carrying it means its line or sport differs.
 * Either without a copy, like any other 4xx, is a refusal. Throws an [IOException] for a reply
 * worth retrying.
 */
internal fun segmentPutResultOf(code: Int, body: String): SegmentPutResult {
    if (code ==
        409
    ) {
        return parseSegmentConflict(body)?.let { SegmentPutResult.Newer(it) } ?: SegmentPutResult.Rejected(code)
    }
    if (code == 400) parseSegmentConflict(body)?.let { return SegmentPutResult.LineChanged(it) }
    return when (outcomeOf(code)) {
        Outcome.Ok -> SegmentPutResult.Applied
        Outcome.Retry -> throw retryFor(code)
        Outcome.Skip -> SegmentPutResult.Rejected(code)
    }
}

/** The sync API calls the upload and download need. An interface, so tests can use a fake server. */
interface SyncApi {
    suspend fun putActivity(id: String, summary: RideSummary): PutResult

    suspend fun trackUploadLink(id: String): UploadLink

    suspend fun uploadTrack(link: UploadLink, bytes: ByteArray)

    /**
     * One page of the rides saved after [since] (server time), oldest first. [page] is the
     * previous page's `nextCursor`, or null for the first page; [since] stays the same across pages.
     */
    suspend fun listActivities(since: Long, page: String?): CloudPage

    /** A short-lived link to download the ride's track file, or null if it has none yet (404). */
    suspend fun trackDownloadLink(id: String): String?

    suspend fun downloadTrack(url: String): ByteArray
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
        if (code == 409) return parseConflict(body)?.let { PutResult.Newer(it) } ?: PutResult.Rejected(code)
        return when (outcomeOf(code)) {
            Outcome.Ok -> PutResult.Applied
            Outcome.Retry -> throw retryFor(code)
            Outcome.Skip -> PutResult.Rejected(code)
        }
    }

    override suspend fun trackUploadLink(id: String): UploadLink {
        val (code, body) = request("POST", "$apiUrl/activities/$id/track-upload", ByteArray(0), emptyMap())
        throwUnlessOk(code)
        val reply = json.decodeFromString(LinkJson.serializer(), body.decodeToString())
        return UploadLink(reply.url, reply.headers)
    }

    override suspend fun uploadTrack(link: UploadLink, bytes: ByteArray) {
        // The link is signed for S3: no sign-in token goes with it.
        val (code, _) = request("PUT", link.url, bytes, link.headers, withToken = false)
        throwUnlessOk(code, fromS3 = true)
    }

    override suspend fun listActivities(since: Long, page: String?): CloudPage {
        val cursor = page?.let { "&cursor=" + URLEncoder.encode(it, Charsets.UTF_8.name()) } ?: ""
        val (code, body) = request("GET", "$apiUrl/activities?since=$since$cursor", null, emptyMap())
        throwUnlessOk(code)
        return parsePage(body.decodeToString())
    }

    override suspend fun trackDownloadLink(id: String): String? {
        val (code, body) = request("GET", "$apiUrl/activities/$id/track", null, emptyMap())
        if (code == 404) return null
        throwUnlessOk(code)
        return json.decodeFromString(LinkJson.serializer(), body.decodeToString()).url
    }

    override suspend fun downloadTrack(url: String): ByteArray {
        // Signed for S3, like the upload link: no sign-in token goes with it.
        val (code, body) = request("GET", url, null, emptyMap(), withToken = false)
        throwUnlessOk(code, fromS3 = true)
        return body
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
        withToken: Boolean = true,
    ): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = method
            if (withToken) connection.setRequestProperty("Authorization", "Bearer $accessToken")
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
    private fun throwUnlessOk(code: Int, fromS3: Boolean = false) {
        when (outcomeOf(code, fromS3)) {
            Outcome.Ok -> Unit
            Outcome.Retry -> throw retryFor(code)
            Outcome.Skip -> throw RejectedException(code)
        }
    }

    @Serializable
    private data class LinkJson(val url: String, val headers: Map<String, String> = emptyMap())

    private companion object {
        const val TIMEOUT_MS = 30_000
        val JSON_HEADERS = mapOf("Content-Type" to "application/json")
        val json = Json { ignoreUnknownKeys = true }
    }
}
