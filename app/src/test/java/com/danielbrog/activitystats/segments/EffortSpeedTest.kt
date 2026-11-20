package com.danielbrog.activitystats.segments

import org.junit.Assert.assertEquals
import org.junit.Test

class EffortSpeedTest {

    @Test
    fun aKilometreInTwoMinutesIs30KmH() {
        assertEquals(30.0, EffortSpeed.averageMps(1_000.0, 120_000L) * 3.6, 1e-9)
    }

    @Test
    fun noTimeMeansNoSpeed() {
        assertEquals(0.0, EffortSpeed.averageMps(1_000.0, 0L), 0.0)
    }
}
