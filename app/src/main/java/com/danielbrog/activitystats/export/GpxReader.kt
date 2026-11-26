package com.danielbrog.activitystats.export

import com.danielbrog.activitystats.data.ActivityType
import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import java.io.InputStream
import java.io.StringReader
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/**
 * One track point from a GPX file. [stretch] numbers the file's track segments (`<trkseg>`)
 * from 0: each is one stretch between pauses.
 */
data class GpxPoint(
    val latitude: Double,
    val longitude: Double,
    val elevation: Double?,
    val timeMillis: Long,
    val stretch: Int = 0,
)

/** A recorded track read from a GPX file, points in time order. [type] is an [ActivityType]. */
data class GpxTrack(val name: String?, val type: String, val points: List<GpxPoint>) {

    /**
     * The points as saved fixes. All are kept: the recording app already cleaned its track.
     * Each track segment becomes a [GpsData.stretch], so a pause in the file stays a pause.
     */
    fun toFixes(): List<GpsData> = points.mapIndexed { i, p ->
        GpsData(
            seq = i + 1,
            timeMillis = p.timeMillis,
            latitude = p.latitude,
            longitude = p.longitude,
            accuracyMeters = null,
            speedMps = null,
            status = FixStatus.Kept,
            altitudeMeters = p.elevation,
            stretch = p.stretch,
        )
    }
}

/** Why a file couldn't be imported. */
class GpxImportException(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        /** Not XML, or XML that isn't GPX. */
        NotGpx,

        /** Points but no times: a planned route, not a recorded ride. */
        NoTimes,

        /** A GPX file with no track points at all. */
        NoPoints,
    }
}

/**
 * Reads a recorded track from a GPX 1.0 or 1.1 file, as written by Strava, Garmin, Komoot,
 * Wahoo or this app's own export. Plain Kotlin on Java's built-in streaming XML parser,
 * so it is unit-tested and handles long rides without holding the whole file.
 *
 * Every track segment is joined into one track, each kept as its own stretch, so the gap
 * between two stretches (a pause) isn't counted as ridden. Points without a valid time are skipped,
 * the rest are put in time order, and a second point at the same time is dropped.
 * Elements from other namespaces (heart rate, power and other extensions) are ignored.
 */
object GpxReader {

    fun read(input: InputStream): GpxTrack {
        val handler = Handler()
        try {
            newParser().parse(InputSource(input), handler)
        } catch (e: SAXException) {
            // Not well-formed XML, or not GPX. A read failure stays an IOException.
            throw GpxImportException(GpxImportException.Reason.NotGpx)
        }
        if (!handler.sawGpx) throw GpxImportException(GpxImportException.Reason.NotGpx)

        val timed = handler.points
            .sortedBy { it.timeMillis } // stable: points at the same time keep file order
            .distinctBy { it.timeMillis }
        if (timed.size < 2) {
            val anyPoints = handler.untimedPoints > 0 || handler.points.isNotEmpty() || handler.routePoints > 0
            throw GpxImportException(
                if (anyPoints) GpxImportException.Reason.NoTimes else GpxImportException.Reason.NoPoints,
            )
        }
        return GpxTrack(
            name = (handler.metadataName ?: handler.trackName)?.trim()?.takeIf { it.isNotEmpty() },
            type = activityType(handler.trackType),
            points = timed,
        )
    }

    /** A namespace-aware parser that never fetches anything outside the file. */
    private fun newParser(): javax.xml.parsers.SAXParser {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        // Not every platform parser knows every feature; each is best effort.
        listOf(
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
        ).forEach { (feature, value) ->
            try {
                factory.setFeature(feature, value)
            } catch (_: Exception) {
            }
        }
        return factory.newSAXParser()
    }

    /** Maps the file's activity type to ours. Strava's numeric types are included. */
    internal fun activityType(type: String?): String {
        val t = type?.trim()?.lowercase() ?: return ActivityType.RIDE
        return when {
            t == "9" || "run" in t -> ActivityType.RUN
            t == "10" || "walk" in t || "hik" in t -> ActivityType.WALK
            else -> ActivityType.RIDE
        }
    }

    /** "2026-11-07T14:03:05Z", with optional fractions and offsets; no zone is taken as UTC. */
    internal fun parseTime(text: String): Long? {
        val s = text.trim()
        return try {
            OffsetDateTime.parse(s).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    private class Handler : DefaultHandler() {
        var sawGpx = false
        val points = mutableListOf<GpxPoint>()
        var untimedPoints = 0
        var routePoints = 0
        var metadataName: String? = null
        var trackName: String? = null
        var trackType: String? = null

        /** Local names of the open GPX elements; foreign-namespace elements aren't pushed. */
        private val path = ArrayDeque<String>()
        private val text = StringBuilder()
        private var lat: Double? = null
        private var lon: Double? = null
        private var ele: Double? = null
        private var time: Long? = null

        /** The stretch of the current track segment; -1 before the first. */
        private var stretch = -1
        private var depthInForeign = 0

        private fun isGpxNamespace(uri: String) = uri.isEmpty() || uri.startsWith("http://www.topografix.com/GPX/")

        override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
            InputSource(StringReader("")) // never load external entities or DTDs

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            if (depthInForeign > 0 || !isGpxNamespace(uri)) {
                depthInForeign++
                return
            }
            if (path.isEmpty()) {
                if (localName != "gpx") throw SAXException("Not a GPX file")
                sawGpx = true
            }
            path.addLast(localName)
            text.setLength(0)
            when (localName) {
                "trkpt" -> {
                    lat = attributes.getValue("lat")?.toDoubleOrNull()
                    lon = attributes.getValue("lon")?.toDoubleOrNull()
                    ele = null
                    time = null
                }
                "trkseg" -> stretch++
                "rtept" -> routePoints++
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (depthInForeign == 0) text.appendRange(ch, start, start + length)
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            if (depthInForeign > 0) {
                depthInForeign--
                return
            }
            val parent = path.elementAtOrNull(path.size - 2)
            val value = text.toString()
            when (localName) {
                // toDoubleOrNull reads "NaN" and "Infinity" as numbers; a track can't hold them.
                "ele" -> if (parent == "trkpt") ele = value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
                "time" -> if (parent == "trkpt") time = parseTime(value)
                "name" -> when (parent) {
                    "metadata", "gpx" -> if (metadataName == null) metadataName = value
                    "trk" -> if (trackName == null) trackName = value
                }
                "type" -> if (parent == "trk" && trackType == null) trackType = value
                "trkpt" -> {
                    val la = lat
                    val lo = lon
                    val t = time
                    if (la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0) {
                        if (t != null) points += GpxPoint(la, lo, ele, t, stretch.coerceAtLeast(0)) else untimedPoints++
                    }
                }
            }
            path.removeLastOrNull()
            text.setLength(0)
        }
    }
}
