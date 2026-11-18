package com.danielbrog.activitystats.sync

import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A ride's track as stored in the cloud: gzipped JSON holding every fix, dropped ones
 * included, with the noise filter's decision and the pause stretch, so a phone that
 * downloads it has exactly what the recording phone saved. Plain Kotlin, unit-tested.
 *
 * GPX stays an export format: it only carries the kept points, without accuracy or status.
 */
object TrackFile {

    /**
     * The format written now. A change that old readers can't handle needs a new number.
     * 1: the first format, with each fix's pause stretch under the key "segment".
     * 2: that key is "stretch". A version 1 file still reads, but its pauses are lost (every
     *    fix lands in stretch 0); only test rides were ever uploaded in it.
     */
    const val VERSION = 2

    /** The type the file is uploaded with; the backend's upload link requires it. */
    const val CONTENT_TYPE = "application/gzip"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class FileJson(val version: Int, val fixes: List<FixJson>)

    /** One fix, with short names: a long ride has tens of thousands of them. */
    @Serializable
    private data class FixJson(
        val seq: Int,
        val t: Long,
        val lat: Double,
        val lon: Double,
        val alt: Double? = null,
        val acc: Float? = null,
        val spd: Float? = null,
        val status: String,
        val stretch: Int = 0,
    )

    fun encode(fixes: List<GpsData>): ByteArray {
        val file = FileJson(
            version = VERSION,
            fixes = fixes.sortedBy { it.seq }.map {
                FixJson(
                    seq = it.seq,
                    t = it.timeMillis,
                    lat = it.latitude,
                    lon = it.longitude,
                    alt = it.altitudeMeters,
                    acc = it.accuracyMeters,
                    spd = it.speedMps,
                    status = it.status.name,
                    stretch = it.stretch,
                )
            },
        )
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(json.encodeToString(FileJson.serializer(), file).toByteArray()) }
        return out.toByteArray()
    }

    /** Reads a track file. Throws [IllegalArgumentException] for a newer format than this app knows. */
    fun decode(bytes: ByteArray): List<GpsData> {
        val text = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }.decodeToString()
        val file = json.decodeFromString(FileJson.serializer(), text)
        require(file.version <= VERSION) { "Track file version ${file.version} is newer than this app" }
        return file.fixes.map {
            GpsData(
                seq = it.seq,
                timeMillis = it.t,
                latitude = it.lat,
                longitude = it.lon,
                accuracyMeters = it.acc,
                speedMps = it.spd,
                // An unknown name can only come from a newer app; don't trust that fix.
                status = FixStatus.entries.firstOrNull { s -> s.name == it.status } ?: FixStatus.DroppedAccuracy,
                altitudeMeters = it.alt,
                stretch = it.stretch,
            )
        }
    }
}
