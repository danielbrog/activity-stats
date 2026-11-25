package com.danielbrog.activitystats.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * How [MapWithDetailsSheet] shares its height between the map and the details sheet. Plain
 * Kotlin, unit-tested.
 *
 * The map is its own window at the top, [MAP_SHARE] of the height. The sheet starts just below
 * it ([peek] tall, its top at the map's bottom) and can be raised to cover [COVER_SHARE] of the
 * map, never more, so the route stays in view; its details then scroll inside it.
 */
internal data class MapSheetSizes(
    /** The map window's height. */
    val map: Dp,
    /** The sheet's height when lowered, drag handle included: everything below the map. */
    val peek: Dp,
    /** The sheet's height when raised, drag handle included. */
    val sheet: Dp,
    /** How much of the map the raised sheet covers. */
    val coveredWhenUp: Dp,
) {
    /** The sheet's height below its drag handle, where the details go. */
    val details: Dp get() = (sheet - DRAG_HANDLE).coerceAtLeast(0.dp)

    companion object {
        /** The map window's share of the height. */
        const val MAP_SHARE = 0.5f

        /** How much of the map the raised sheet covers at most. */
        const val COVER_SHARE = 0.5f

        /** The drag handle's height: Material's handle, 4 dp, with 22 dp above and below. */
        val DRAG_HANDLE = 48.dp

        fun of(available: Dp): MapSheetSizes {
            val map = available * MAP_SHARE
            val covered = map * COVER_SHARE
            return MapSheetSizes(
                map = map,
                peek = available - map,
                sheet = available - map + covered,
                coveredWhenUp = covered,
            )
        }
    }
}

/**
 * A map in its own window with details in a sheet below it, as on the ride screen. The map
 * takes every gesture it gets, so panning or zooming never scrolls the page. Dragging the
 * details up raises the sheet over part of the map (see [MapSheetSizes]), then scrolls them
 * inside it; touching the map lowers it again, so the map is fully open.
 *
 * [map] draws the map with the padding the raised sheet covers, so its logo and centring use
 * the part still visible, and calls the given lambda when the map is touched. [content] draws
 * the details into the modifier it is given, the space for them in the sheet, and scrolls them
 * itself: a scrolling column, or a lazy list for a long one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapWithDetailsSheet(
    map: @Composable (contentPadding: PaddingValues, onMapTouch: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val sizes = MapSheetSizes.of(maxHeight)
        val sheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
            // The details can be lowered, never swiped away.
            skipHiddenState = true,
        )
        val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
        val scope = rememberCoroutineScope()
        // Only once the sheet has come to rest at the top (currentValue, not targetValue, which
        // flips part-way through a drag), so the map's padding never changes mid-drag.
        val covered = if (sheetState.currentValue == SheetValue.Expanded) sizes.coveredWhenUp else 0.dp
        // Lowered and at rest: added only once it has settled, so the bottom of a sheet on its
        // way down isn't blanked; removed as soon as it heads up, so raising fills it at once.
        val lowered = sheetState.currentValue == SheetValue.PartiallyExpanded &&
            sheetState.targetValue == SheetValue.PartiallyExpanded

        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = sizes.peek,
            // The sheet spans the width, so no bare strips beside it below the map (landscape).
            sheetMaxWidth = Dp.Unspecified,
            sheetDragHandle = {
                // A fixed height, so the raised sheet covers exactly what MapSheetSizes says.
                Box(Modifier.height(MapSheetSizes.DRAG_HANDLE), contentAlignment = Alignment.Center) {
                    BottomSheetDefaults.DragHandle()
                }
            },
            sheetContent = {
                Column(Modifier.fillMaxWidth().height(sizes.details)) {
                    content(Modifier.fillMaxWidth().weight(1f))
                    // Lowered, only the top of the sheet is on screen: keep the details' scroll
                    // area to that part, so TalkBack's scrolling and focus stay where you can see.
                    if (lowered) Spacer(Modifier.height(sizes.coveredWhenUp))
                }
            },
        ) {
            Box(Modifier.fillMaxWidth().height(sizes.map)) {
                map(PaddingValues(bottom = covered)) {
                    // Also while it is still rising, so grabbing the map at once stops it.
                    if (sheetState.targetValue != SheetValue.PartiallyExpanded) {
                        scope.launch { sheetState.partialExpand() }
                    }
                }
            }
        }
    }
}
