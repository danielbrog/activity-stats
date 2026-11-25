package com.danielbrog.activitystats.history

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danielbrog.activitystats.stats.DistanceUnit
import com.danielbrog.activitystats.stats.SpeedSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The ride charts sit in a scrolling page (the ride screen's details sheet): an up or down drag
 * that starts on a chart must scroll the page, and only a sideways one belongs to the chart.
 */
@RunWith(AndroidJUnit4::class)
class ChartScrollTest {

    @get:Rule
    val compose = createComposeRule()

    private val samples = (0..100).map { SpeedSample(distanceMeters = it * 100.0, speedMps = 5.0 + it % 10) }

    /** A short scrolling page with a speed chart at the top and room to scroll below it. */
    private fun showChartIn(scroll: ScrollState) {
        compose.setContent {
            Column(Modifier.height(300.dp).verticalScroll(scroll)) {
                SpeedChart(samples, avgSpeedMps = 7.0, unit = DistanceUnit.Km, modifier = Modifier.testTag("chart"))
                Spacer(Modifier.height(1_000.dp))
            }
        }
    }

    @Test
    fun draggingUpOnAChartScrollsThePage() {
        val scroll = ScrollState(0)
        showChartIn(scroll)
        compose.onNodeWithTag("chart").performTouchInput { swipeUp() }
        compose.runOnIdle { assertTrue("the page should have scrolled", scroll.value > 0) }
    }

    @Test
    fun slidingAlongAChartLeavesThePageWhereItIs() {
        val scroll = ScrollState(0)
        showChartIn(scroll)
        compose.onNodeWithTag("chart").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(0, scroll.value) }
    }
}
