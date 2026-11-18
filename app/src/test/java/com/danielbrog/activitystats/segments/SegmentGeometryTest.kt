package com.danielbrog.activitystats.segments

import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import com.danielbrog.activitystats.location.RouteBounds
import com.danielbrog.activitystats.location.RoutePoint
import com.danielbrog.activitystats.stats.Geo
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pieces segment matching is built on: Geo's line helpers, polylines, simplifying, paths. */
class SegmentGeometryTest {

    private val lat0 = 43.65
    private val lon0 = -79.38
    private val perDegLat = 111_195.0
    private val perDegLon = perDegLat * cos(Math.toRadians(lat0))

    private fun at(east: Double, north: Double) = RoutePoint(lat0 + north / perDegLat, lon0 + east / perDegLon)

    // --- Geo

    @Test
    fun aPointBesideALegIsMeasuredStraightAcross() {
        val a = at(0.0, 0.0)
        val b = at(0.0, 200.0)
        val p = at(100.0, 100.0)
        val on = Geo.onLeg(p.latitude, p.longitude, a.latitude, a.longitude, b.latitude, b.longitude)
        assertEquals(100.0, on.distanceMeters, 0.5)
        assertEquals(0.5, on.fraction, 0.01)
    }

    @Test
    fun aPointPastTheEndIsMeasuredToTheEnd() {
        val a = at(0.0, 0.0)
        val b = at(0.0, 200.0)
        val p = at(0.0, 300.0)
        val on = Geo.onLeg(p.latitude, p.longitude, a.latitude, a.longitude, b.latitude, b.longitude)
        assertEquals(100.0, on.distanceMeters, 0.5)
        assertEquals(1.0, on.fraction, 0.0)
    }

    @Test
    fun distanceToAPathIsToItsClosestLeg() {
        val path = listOf(at(0.0, 0.0), at(0.0, 500.0), at(500.0, 500.0))
        val p = at(250.0, 530.0) // 30 m north of the second leg
        val d = Geo.distanceToPathMeters(p.latitude, p.longitude,
            path.map { it.latitude }.toDoubleArray(), path.map { it.longitude }.toDoubleArray())
        assertEquals(30.0, d, 0.5)
    }

    // --- Bounds

    @Test
    fun boxesOverlapAndGrow() {
        val a = RouteBounds.of(listOf(at(0.0, 0.0), at(100.0, 100.0)))!!
        val b = RouteBounds.of(listOf(at(120.0, 0.0), at(200.0, 100.0)))!!
        assertFalse(a.overlaps(b))
        assertTrue(a.expandedBy(25.0).overlaps(b))
    }

    // --- Polyline

    @Test
    fun googlesExampleEncodesAndDecodes() {
        // The worked example from Google's polyline documentation.
        val points = listOf(RoutePoint(38.5, -120.2), RoutePoint(40.7, -120.95), RoutePoint(43.252, -126.453))
        val encoded = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
        assertEquals(encoded, Polyline.encode(points))
        assertEquals(points, Polyline.decode(encoded))
    }

    @Test
    fun aRealPathRoundTripsToAboutAMetre() {
        val path = (0..200).map { at(it * 3.7, it * 7.1) }
        Polyline.decode(Polyline.encode(path)).zip(path).forEach { (back, original) ->
            assertEquals(0.0, Geo.distanceMeters(back.latitude, back.longitude, original.latitude, original.longitude), 1.0)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun aBrokenPolylineIsRefused() {
        Polyline.decode("_p~iF~ps|U_") // cut off in the middle of a number
    }

    // --- Simplify

    @Test
    fun aStraightLineKeepsOnlyItsEnds() {
        val straight = (0..100).map { at(0.0, it * 10.0) }
        assertEquals(listOf(straight.first(), straight.last()), Simplify.path(straight, 5.0))
    }

    @Test
    fun aCornerIsKept() {
        val corner = (0..50).map { at(0.0, it * 10.0) } + (1..50).map { at(it * 10.0, 500.0) }
        assertEquals(listOf(at(0.0, 0.0), at(0.0, 500.0), at(500.0, 500.0)), Simplify.path(corner, 5.0))
    }

    @Test
    fun wobblesSmallerThanTheToleranceAreSmoothedAway() {
        val wobbly = (0..100).map { at(if (it % 2 == 0) 2.0 else -2.0, it * 10.0) }
        assertEquals(2, Simplify.path(wobbly, 5.0).size)
    }

    // --- SegmentPath.fromRide

    private fun fix(seq: Int, north: Double, status: FixStatus = FixStatus.Kept, stretch: Int = 0): GpsData {
        val p = at(0.0, north)
        return GpsData(seq, seq * 1_000L, p.latitude, p.longitude, 5f, 10f, status, stretch = stretch)
    }

    @Test
    fun aPartOfARideBecomesAPathOfItsKeptFixes() {
        val fixes = (0..100).map { fix(it, it * 10.0) } + fix(101, 5_000.0, FixStatus.DroppedAccuracy)
        val path = SegmentPath.fromRide(fixes, startIndex = 20, endIndex = 70)!!
        assertEquals(500.0, path.distanceMeters, 1.0)
        assertEquals(fixes[20].latitude, path.start.latitude, 1e-9)
        assertEquals(fixes[70].latitude, path.end.latitude, 1e-9)
        // A straight road simplifies to its two ends.
        assertEquals(2, path.points.size)
    }

    @Test
    fun aPartAcrossAPauseIsRefused() {
        val fixes = (0..50).map { fix(it, it * 10.0) } + (51..100).map { fix(it, it * 10.0, stretch = 1) }
        assertNull(SegmentPath.fromRide(fixes, 20, 70))
        assertTrue(SegmentPath.fromRide(fixes, 55, 90) != null)
    }

    @Test
    fun aPartTooShortOrOutOfRangeIsRefused() {
        val fixes = (0..10).map { fix(it, it * 10.0) }
        assertNull(SegmentPath.fromRide(fixes, 5, 5))
        assertNull(SegmentPath.fromRide(fixes, 6, 3))
        assertNull(SegmentPath.fromRide(fixes, 0, 11))
        // Two fixes in the same place: no length.
        assertNull(SegmentPath.fromRide(listOf(fix(1, 0.0), fix(2, 0.0)), 0, 1))
    }
}
