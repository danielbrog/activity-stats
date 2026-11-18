package com.danielbrog.activitystats.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import com.danielbrog.activitystats.stats.ActivityStats
import kotlin.math.cos
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Segments against a real database: creating one finds efforts on the rides that cover it. */
@RunWith(AndroidJUnit4::class)
class SegmentRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var rides: ActivityRepository
    private lateinit var segments: SegmentRepository

    private val lat0 = 43.65
    private val lon0 = -79.38
    private val perDegLat = 111_195.0
    private val perDegLon = perDegLat * cos(Math.toRadians(lat0))

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        rides = ActivityRepository(db)
        segments = SegmentRepository(db)
    }

    @After
    fun tearDown() = db.close()

    /** A finished ride 2 km north along a road [eastMeters] east of the origin, 10 m a second. */
    private suspend fun rideAlong(id: String, startMillis: Long, eastMeters: Double = 0.0) {
        rides.startActivity(id, startMillis)
        val fixes = (0..200).map { i ->
            GpsData(i + 1, startMillis + i * 1_000L, lat0 + i * 10.0 / perDegLat, lon0 + eastMeters / perDegLon,
                5f, 10f, FixStatus.Kept)
        }
        rides.savePoints(id, fixes, ActivityStats(), startMillis + 200_000L)
        rides.finishActivity(id, startMillis + 200_000L, ActivityStats(distanceMeters = 2_000.0))
    }

    @Test
    fun finishingARideStoresItsBox() = runBlocking {
        rideAlong("a", 1_000_000L)
        val ride = rides.getActivity("a")!!
        assertEquals(lat0, ride.minLat!!, 1e-9)
        assertEquals(lat0 + 2_000.0 / perDegLat, ride.maxLat!!, 1e-6)
    }

    @Test
    fun creatingASegmentFindsEffortsOnRidesThatCoverIt() = runBlocking {
        rideAlong("first", 1_000_000L)
        rideAlong("second", 5_000_000L)
        rideAlong("elsewhere", 9_000_000L, eastMeters = 30_000.0)

        // Fixes 50 to 150 of the first ride: 1 km of the road.
        val id = segments.create("Road", "first", startIndex = 50, endIndex = 150, nowMillis = 10_000_000L)
        assertNotNull(id)

        val efforts = segments.effortsFor(id!!).first()
        assertEquals(setOf("first", "second"), efforts.map { it.activityId }.toSet())
        efforts.forEach { assertEquals(100_000.0, it.elapsedMs.toDouble(), 1_500.0) }
        assertEquals(listOf("Road"), segments.allSegments.first().map { it.name })
    }

    @Test
    fun aRideAddedLaterGetsItsEffortWhenRecomputed() = runBlocking {
        rideAlong("first", 1_000_000L)
        val id = segments.create("Road", "first", 50, 150)!!
        rideAlong("later", 5_000_000L)
        assertEquals(1, segments.effortsFor(id).first().size) // not hooked in until slice 3

        segments.recomputeForRide("later")
        assertEquals(setOf("first", "later"), segments.effortsFor(id).first().map { it.activityId }.toSet())
    }

    @Test
    fun deletedRidesDropOutOfASegmentsEfforts() = runBlocking {
        rideAlong("first", 1_000_000L)
        rideAlong("second", 5_000_000L)
        val id = segments.create("Road", "first", 50, 150)!!
        rides.delete("second")
        assertEquals(listOf("first"), segments.effortsFor(id).first().map { it.activityId })
    }

    @Test
    fun aPartThatCantBeASegmentIsRefused() = runBlocking {
        rideAlong("first", 1_000_000L)
        assertNull(segments.create("Nothing", "first", 80, 80))
        assertTrue(segments.allSegments.first().isEmpty())
    }

    @Test
    fun olderRidesGetTheirBoxFilledIn() = runBlocking {
        rideAlong("first", 1_000_000L)
        db.openHelper.writableDatabase.execSQL("UPDATE activities SET minLat = NULL, maxLat = NULL, minLon = NULL, maxLon = NULL")
        assertEquals(1, rides.fillMissingBounds())
        assertNotNull(rides.getActivity("first")!!.minLat)
        assertEquals(0, rides.fillMissingBounds())
    }
}
