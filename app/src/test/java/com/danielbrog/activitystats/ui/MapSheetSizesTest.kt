package com.danielbrog.activitystats.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the map window and the details sheet share the screen below the title. */
class MapSheetSizesTest {

    private fun assertDp(expected: Dp, actual: Dp) = assertEquals(expected.value, actual.value, 0.01f)

    @Test
    fun theMapGetsHalfAndTheLoweredSheetTheRest() {
        val sizes = MapSheetSizes.of(800.dp)
        assertDp(400.dp, sizes.map)
        // Lowered, the sheet's top sits at the map's bottom.
        assertDp(400.dp, sizes.peek)
        assertDp(800.dp, sizes.map + sizes.peek)
    }

    @Test
    fun theRaisedSheetCoversHalfTheMapAndNoMore() {
        val sizes = MapSheetSizes.of(800.dp)
        assertDp(200.dp, sizes.coveredWhenUp)
        assertDp(600.dp, sizes.sheet)
        // Raised, the map still shows above the sheet.
        assertDp(200.dp, 800.dp - sizes.sheet)
    }

    @Test
    fun theDetailsGetTheSheetBelowItsHandle() {
        val sizes = MapSheetSizes.of(800.dp)
        assertDp(600.dp - MapSheetSizes.DRAG_HANDLE, sizes.details)
        // Never negative, however short the screen.
        assertDp(0.dp, MapSheetSizes.of(40.dp).details)
    }

    @Test
    fun theSharesHoldForAnyHeight() {
        for (height in listOf(320.dp, 560.dp, 1_000.dp)) {
            val sizes = MapSheetSizes.of(height)
            assertDp(height * MapSheetSizes.MAP_SHARE, sizes.map)
            assertDp(sizes.map * MapSheetSizes.COVER_SHARE, sizes.coveredWhenUp)
            assertTrue("the raised sheet must be taller than the lowered one", sizes.sheet > sizes.peek)
            assertTrue("the raised sheet must leave some of the map showing", sizes.sheet < height)
        }
    }
}
