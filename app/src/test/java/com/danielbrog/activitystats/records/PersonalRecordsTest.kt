package com.danielbrog.activitystats.records

import com.danielbrog.activitystats.data.ActivityEntity
import com.danielbrog.activitystats.data.BestEffortEntity
import com.danielbrog.activitystats.stats.Effort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonalRecordsTest {

    private fun ride(id: String, startedAt: Long, meters: Double = 0.0, movingMs: Long = 0L) = ActivityEntity(
        id = id, startedAt = startedAt, endedAt = startedAt + 1, title = "Ride $id",
        distanceMeters = meters, movingTimeMs = movingMs, updatedAt = startedAt,
    )

    private fun effort(id: String, effort: Effort, ms: Long?) = BestEffortEntity(id, effort.key, ms)

    @Test
    fun picksLongestRideAndMovingTimeSeparately() {
        val records = PersonalRecords.from(
            listOf(ride("a", 1, meters = 40_000.0, movingMs = 5_000_000), ride("b", 2, meters = 30_000.0, movingMs = 6_000_000)),
            emptyList(),
        )
        assertEquals("a", records.longestRide?.holder?.rideId)
        assertEquals(40_000.0, records.longestRide!!.meters, 0.0)
        assertEquals("b", records.longestMovingTime?.holder?.rideId)
        assertEquals(6_000_000L, records.longestMovingTime!!.ms)
    }

    @Test
    fun fastestEffortWinsAndEffortsWithoutATimeAreSkipped() {
        val records = PersonalRecords.from(
            listOf(ride("a", 1), ride("b", 2), ride("c", 3)),
            listOf(
                effort("a", Effort.Km10, 1_200_000),
                effort("b", Effort.Km10, 1_100_000),
                effort("c", Effort.Km10, null),
                effort("a", Effort.Km5, 600_000),
            ),
        )
        assertEquals("b", records.bestEfforts[Effort.Km10]?.holder?.rideId)
        assertEquals(1_100_000L, records.bestEfforts[Effort.Km10]?.ms)
        assertEquals("a", records.bestEfforts[Effort.Km5]?.holder?.rideId)
        assertNull(records.bestEfforts[Effort.Km40])
        assertEquals(Effort.entries.toSet(), records.bestEfforts.keys)
    }

    @Test
    fun deletedRidesDontHoldRecords() {
        // "b" was the fastest but isn't in the list of current rides any more.
        val records = PersonalRecords.from(
            listOf(ride("a", 1, meters = 10_000.0)),
            listOf(effort("a", Effort.Km5, 700_000), effort("b", Effort.Km5, 500_000)),
        )
        assertEquals("a", records.bestEfforts[Effort.Km5]?.holder?.rideId)
    }

    @Test
    fun theEarlierRideKeepsATiedRecord() {
        // Listed newest first, as the history query returns them.
        val records = PersonalRecords.from(
            listOf(ride("new", 2, meters = 20_000.0), ride("old", 1, meters = 20_000.0)),
            listOf(effort("new", Effort.Km5, 600_000), effort("old", Effort.Km5, 600_000)),
        )
        assertEquals("old", records.longestRide?.holder?.rideId)
        assertEquals("old", records.bestEfforts[Effort.Km5]?.holder?.rideId)
    }

    @Test
    fun noRidesMeansNoRecords() {
        val records = PersonalRecords.from(emptyList(), emptyList())
        assertNull(records.longestRide)
        assertNull(records.longestMovingTime)
        assertEquals(true, records.bestEfforts.values.all { it == null })
    }
}
