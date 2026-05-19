package com.qtone.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qtone.app.model.MediaItem
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Geometry of the focused card at the moment the user pressed Menu.
 *
 * Retained from earlier card-anchored designs even though the current
 * sidebar-overlay popup doesn't use it directly. The bounds-capture
 * plumbing in MovieMediaGrid still updates focusedCardBounds in
 * MainActivity for potential future use; removing it would touch many
 * files for no functional gain.
 */
data class FocusedCardBounds(
    val itemId: String,
    val xDp: Dp,
    val yDp: Dp,
    val widthDp: Dp,
    val heightDp: Dp
)

/**
 * Quick-info popup — SIDEBAR-OVERLAY design.
 *
 * The popup appears in the left-side zone normally occupied by the
 * category list, sliding in from the left edge. It doesn't overlap any
 * cards. It doesn't dim the cards section. The card the user pressed
 * Menu on remains fully visible on the right, retaining its normal focus
 * indicator (white border + 1.08x scale) — that's how the user knows
 * which card the popup is describing.
 *
 * Why this layout (after multiple iterations of card-anchored and
 * right-side variants):
 *
 *   - The sidebar area is dead space while the user is browsing cards;
 *     the category list isn't being interacted with. Overlaying it
 *     costs the user nothing.
 *   - No poster needed. The original card is visible on the right;
 *     duplicating its poster inside the popup was redundant.
 *   - No scrim needed. The focused card's own border + scale make it
 *     obvious which card is "selected"; dimming the rest would obscure
 *     content the user can usefully glance at while reading the popup.
 *   - Predictable fixed location → user develops muscle memory.
 *   - Visual: looks like a natural extension of the existing sidebar
 *     slot rather than a foreign UI element dropped on top.
 *
 * GEOMETRY
 *
 * Sidebar zone in PosterLayout:
 *   24dp left gutter + 220dp category column + 22dp right gap = 266dp
 *
 * The popup is sized to fit entirely WITHIN this zone — width 250dp +
 * 16dp left inset gives a right edge at x=266, exactly where the first
 * card column begins. Zero overlap with cards.
 *
 * Vertical extent: from 78dp below the top edge (clears the top bar)
 * to 24dp above the bottom edge.
 *
 * CONTENT LAYOUT
 *
 *   - Title (up to 3 lines for long titles)
 *   - Year · ★ rating · genre meta row
 *   - Plot (up to 12 lines, ellipsis on overflow — generous since this
 *     is the main content; most TMDB plots fit completely)
 *   - Cast (up to 3 lines)
 *   - Dismiss hint pinned to bottom
 *
 * No poster. The card on the right serves that role.
 *
 * ENTRANCE
 *
 *   - Alpha fade-in over 180ms
 *   - Slide from translationX = -48 (off-screen left) to 0 over 220ms
 *     with FastOutSlowInEasing
 *   - Re-keyed on item.id so each new card produces a fresh entrance
 *
 * DISMISSAL is handled by the parent (PosterLayout's keyhandler in
 * MainActivity); this composable just renders.
 *
 * @param item the MediaItem to display. Reads name/year/rating/genre/plot/cast.
 *   Caller passes the live (latest) version via the liveItem pattern so
 *   TMDB data appears reactively.
 * @param viewportWidthDp unused (kept for API stability — earlier card-
 *   anchored designs needed it).
 * @param viewportHeightDp unused (same reasoning).
 */
@Composable
fun QuickInfoPopup(
    item: MediaItem,
    @Suppress("UNUSED_PARAMETER") viewportWidthDp: Dp,
    @Suppress("UNUSED_PARAMETER") viewportHeightDp: Dp
) {
    // ── Geometry constants ────────────────────────────────────────────
    //
    // panelWidth = 250dp + sideMargin = 16dp →  right edge at 266dp.
    // Sidebar zone is exactly 266dp (24+220+22), so the popup fully
    // occupies the sidebar zone and ends precisely where the first card
    // column starts. No overlap.
    val panelWidth = 250.dp
    val topMargin = 78.dp
    val sideMargin = 16.dp
    val bottomMargin = 24.dp

    // ── Entrance animation ────────────────────────────────────────────
    //
    // Slide-in from off-screen left. Translation starts negative (-48 in
    // dp-equivalent units) so the panel begins 48 px to the left of its
    // resting position, off the screen entirely given the 16dp inset.
    // Animates to 0 over 220ms.
    //
    // Re-keyed on item.id so when the user moves to a different card
    // and presses Menu again, the popup re-animates rather than silently
    // swapping content.
    val enterAlpha = remember(item.id) { Animatable(0f) }
    val enterTranslate = remember(item.id) { Animatable(-48f) }
    LaunchedEffect(item.id) {
        coroutineScope {
            launch {
                enterAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
                )
            }
            launch {
                enterTranslate.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
                )
            }
        }
    }

    // ── Popup container + content ─────────────────────────────────────
    //
    // No full-screen scrim. The cards section is untouched. The popup
    // overlays the sidebar slot (where the category list normally sits)
    // and that's the only thing that visually changes.
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(top = topMargin, start = sideMargin, bottom = bottomMargin)
                .width(panelWidth)
                .fillMaxHeight()
                .graphicsLayer {
                    alpha = enterAlpha.value
                    translationX = enterTranslate.value * density
                }
                .clip(RoundedCornerShape(14.dp))
                // Solid dark fill (not translucent) so the category list
                // behind it is fully hidden. Slightly lighter than the
                // page's black background so the popup reads as a distinct
                // surface, not a hole.
                .background(Color(0xFF15151B))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(14.dp))
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 22.dp)
            ) {
                // TITLE
                //
                // Up to 3 lines for long titles like
                // "The Lord of the Rings: The Fellowship of the Ring".
                Text(
                    text = item.name,
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 23.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )

                // META ROW
                val metaPieces = buildList {
                    item.year?.takeIf { it.isNotBlank() }?.let { add(it) }
                    item.rating?.takeIf { it.isNotBlank() }?.let { add("★ $it") }
                    item.genre?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
                if (metaPieces.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = metaPieces.joinToString("  ·  "),
                        color = Color(0xCCFFFFFF),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // PLOT
                //
                // 14 lines max. The plot is the main content of the
                // popup. At 250dp width with ~210dp text area, this
                // accommodates most TMDB plots completely. Longer plots
                // ellipsis at ~14 lines, which still gives the user a
                // strong sense of the movie's premise.
                Spacer(Modifier.height(16.dp))
                Text(
                    text = item.plot?.takeIf { it.isNotBlank() } ?: "No plot available yet.",
                    color = Color(0xEEFFFFFF),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    maxLines = 14,
                    overflow = TextOverflow.Ellipsis
                )

                // CAST
                item.cast?.takeIf { it.isNotBlank() }?.let { castStr ->
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Starring  ·  $castStr",
                        color = Color(0x99FFFFFF),
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // DISMISS HINT — pinned to bottom via a flexible spacer.
                // Important to surface dismiss controls since this is a
                // new interaction pattern.
                Spacer(Modifier.weight(1f))
                Text(
                    text = "OK to open  ·  Menu, Back, or arrow to dismiss",
                    color = Color(0x66FFFFFF),
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
