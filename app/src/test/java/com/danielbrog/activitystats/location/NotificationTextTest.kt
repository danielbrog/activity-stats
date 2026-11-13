package com.danielbrog.activitystats.location

import com.danielbrog.activitystats.stats.DistanceUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationTextTest {

    @Test
    fun theDistanceFollowsTheChosenUnit() {
        assertEquals("12.43 km · 412 kept of 430 fixes", notificationText(12_430.0, DistanceUnit.Km, 412, 430))
        assertEquals("7.72 mi · 412 kept of 430 fixes", notificationText(12_430.0, DistanceUnit.Mi, 412, 430))
    }

    @Test
    fun oneFixIsSingular() {
        assertEquals("0.00 km · 1 kept of 1 fix", notificationText(0.0, DistanceUnit.Km, 1, 1))
        assertEquals("0.00 mi · 0 kept of 0 fixes", notificationText(0.0, DistanceUnit.Mi, 0, 0))
    }
}
