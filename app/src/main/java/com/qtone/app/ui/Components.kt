package com.qtone.app.ui

import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

// TV Compose imports — used for TV-optimized focus animations on poster tiles.
// We import them with aliases to avoid colliding with the Material3 names that
// are still used elsewhere in this file (e.g. Surface for the settings screen,
// non-focusable surfaces, etc).
import androidx.tv.material3.Surface as TvSurface
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Border as TvBorder
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import com.qtone.app.model.Category
import com.qtone.app.model.MediaItem
import com.qtone.app.model.SeriesEpisode
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.ui.graphics.graphicsLayer

@Composable
fun AppBackground() {
    // Pure black background, full stop.
    //
    // Previously this had two stacked gradient layers — one horizontal
    // (containing a slight bluish-purple tint at #09070D) and one vertical
    // (darkening top/bottom). The horizontal gradient's tinted color was
    // visible as a faint purple cast in the middle of the screen, especially
    // on app startup before grid cards filled in.
    //
    // Removed entirely — just a solid black fill now. Cheaper to render
    // too: one rectangle fill vs three stacked composables with gradient
    // shader allocations.
    Box(Modifier.fillMaxSize().background(QtoneColors.Black))
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EdgeFollowBringIntoView(content: @Composable () -> Unit) {
    // Custom BringIntoViewSpec for the poster grid.
    //
    // Behavior: CENTER-ANCHORED vertical scroll. The focused row's vertical
    // center is aligned with the viewport's vertical center. Rows above and
    // below get partially clipped at the top and bottom of the viewport.
    //
    // This is technically different from Compose's default (edge-only) and
    // from what Nextv's APK extraction showed. The decision is based on
    // direct user testing feedback: with edge-only scrolling, cards at the
    // bottom of the viewport were getting slightly clipped because the
    // BringIntoViewSpec scrolled the focused tile only just enough to be
    // visible — the visible "just enough" overlaps the viewport's bottom
    // edge where the scaled-up focused tile spills slightly outside.
    // Center-anchoring guarantees the focused tile has equal padding above
    // and below within the viewport, eliminating the bottom-clip issue.
    //
    // Edge cases handled by Compose automatically:
    //   - First items: scroll position is clamped to the list start, so the
    //     first row sits naturally at the top with rows below partially
    //     visible (or fully if there's room).
    //   - Last items: scroll position is clamped to the list end, so the
    //     last row sits at the bottom edge.
    //
    // scrollAnimationSpec: spring(400f, 1f) — Compose default, verified
    // against Nextv APK 3.5.0-rev9 (no class overrides scrollAnimationSpec
    // there either). The spring physics is what gives the "buttery"
    // settling feel rather than a tween's abrupt start/stop.
    val edgeFollowSpec = remember {
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: AnimationSpec<Float> = spring(
                stiffness = Spring.StiffnessMediumLow,
                dampingRatio = Spring.DampingRatioNoBouncy,
                visibilityThreshold = 0.5f
            )

            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float
            ): Float {
                // Center-anchored: scroll so the focused item's center aligns
                // with the viewport's center.
                //   offset      = item's leading edge distance from container start
                //   size        = item's height (on the scroll axis)
                //   containerSize = viewport height (on the scroll axis)
                //
                // itemCenter      = offset + size / 2
                // containerCenter = containerSize / 2
                // scrollDelta     = itemCenter - containerCenter
                //                 = offset + size / 2 - containerSize / 2
                //
                // Positive return → scroll forward by N px (shift content up).
                // Negative return → scroll backward by N px (shift content down).
                // LazyList clamps this against its actual scroll range, so the
                // first/last rows snap to the edges automatically.
                val delta = offset + size / 2f - containerSize / 2f

                // Suppress micro-scrolls (< 4 px). Without this clamp, every
                // horizontal D-pad press (moving focus to the next card in the
                // SAME row) triggered a tiny vertical scroll of a few subpixels
                // because the calculation accumulated rounding error. The
                // viewport would shudder up and down by a couple of pixels with
                // each press — the "horizontal-nav jitter" the user reported.
                //
                // 4 px is well below the perceptual threshold for vertical
                // movement on a TV at typical viewing distance, but well above
                // the typical 0-3 px subpixel residual. Cards already on the
                // correct row stay completely still; genuine row changes still
                // animate smoothly because their delta is much larger (one row
                // pitch ≈ 290 px including spacing).
                return if (kotlin.math.abs(delta) < 4f) 0f else delta
            }
        }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides edgeFollowSpec) {
        content()
    }
}



@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NavButton(label: String, selected: Boolean, icon: String, onClick: () -> Unit) {
    // The selected color stays a strong purple regardless of focus state, so
    // the user can see which section is active even when focus moves away.
    // The focus animation (scale + glow) provides additional emphasis on the
    // currently-focused button.
    TvSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(58.dp),
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(8.dp),
            focusedShape = RoundedCornerShape(8.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color(0xFF1A1A1F) else Color.Transparent,
            focusedContainerColor = if (selected) Color(0xFF1A1A1F) else Color(0xFF1F1A28),
            contentColor = QtoneColors.Text,
            focusedContentColor = QtoneColors.Text
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        // No glow on nav buttons. The focused container color (pill background)
        // is the focus cue, matching Nextv's nav bar pattern.
        glow = ClickableSurfaceDefaults.glow(
            glow = Glow(elevationColor = Color.Transparent, elevation = 0.dp),
            focusedGlow = Glow(elevationColor = Color.Transparent, elevation = 0.dp)
        ),
        border = ClickableSurfaceDefaults.border(
            border = TvBorder(border = BorderStroke(0.dp, Color.Transparent)),
            focusedBorder = TvBorder(border = BorderStroke(0.dp, Color.Transparent))
        )
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 21.sp, modifier = Modifier.width(32.dp))
            Text(label, fontSize = 18.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CategoryRow(category: Category, selected: Boolean, onClick: () -> Unit) {
    // Nextv-style sidebar with three visible states:
    //
    //   1. FOCUSED (user is on this category) → WHITE PILL FILL + DARK TEXT
    //      Pill is white-90% rounded, text turns near-black so it reads on
    //      the white pill.
    //
    //   2. SELECTED but not focused (focus moved into the grid; this is the
    //      active filter) → no pill, white text, checkmark (✓) on the right.
    //
    //   3. Neither → muted white text (~80% alpha).
    //
    // The pill background animates in/out over a short duration so focus
    // transitions feel like Nextv's text-fade-with-pill effect rather than
    // a hard switch.
    var focused by remember { mutableStateOf(false) }

    // Animated pill background color. White when focused, transparent
    // otherwise. tween(180ms) is short enough to feel snappy but long enough
    // Pill color states (D-pad focus takes precedence over selection):
    //   focused          → gray pill (0x33FFFFFF) — the "scroll selector"
    //   selected, !focused → white pill — the "this category is active" mark
    //   neither          → transparent (no pill, just text)
    //
    // The checkmark indicator that used to appear for selected-but-not-focused
    // rows is gone; the white pill replaces it.
    val pillColor by animateColorAsState(
        targetValue = when {
            focused -> Color(0x33FFFFFF)   // gray pill (matches top-bar pill tone)
            selected -> Color.White         // white pill (was the focus indicator)
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 90),
        label = "categoryPillColor"
    )
    // Text color must invert per pill color so it stays readable:
    //   white pill   → near-black text
    //   gray pill    → bright white text
    //   no pill      → muted white text
    val textColor by animateColorAsState(
        targetValue = when {
            focused -> Color.White                  // white text on gray pill
            selected -> Color(0xFF0A0A0E)           // near-black text on white pill
            else -> Color(0xCCFFFFFF)
        },
        animationSpec = tween(durationMillis = 90),
        label = "categoryTextColor"
    )
    val countColor by animateColorAsState(
        targetValue = when {
            focused -> Color(0xCCFFFFFF)        // muted white on gray pill
            selected -> Color(0x99000000)        // muted black on white pill
            else -> Color(0x77FFFFFF)
        },
        animationSpec = tween(durationMillis = 90),
        label = "categoryCountColor"
    )
    // Subtle text-translation effect on focus enter/exit. The text content
    // shifts right a few dp when the pill appears, giving the impression that
    // the label is "settling into" the pill rather than just having a
    // color blink. Matches Nextv's focus-enter feel (frames f_038..f_042
    // of the reference video).
    val textStartPad by animateDpAsState(
        targetValue = if (focused) 12.dp else 6.dp,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "categoryTextStartPad"
    )

    TvSurface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(24.dp),
            focusedShape = RoundedCornerShape(24.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            // We supply the pill color via the animated containerColor of an
            // inner Box rather than via the Surface's colors() API. Reason:
            // the Surface's color() picks at compose-time based on a snapshot
            // of the focus state, but we want a smoothly-animated color.
            //
            // Setting both to Transparent makes the Surface itself transparent;
            // the animated pill is painted by the inner Box.
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            contentColor = QtoneColors.Text,
            focusedContentColor = QtoneColors.Text
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        glow = ClickableSurfaceDefaults.glow(
            glow = Glow(elevationColor = Color.Transparent, elevation = 0.dp),
            focusedGlow = Glow(elevationColor = Color.Transparent, elevation = 0.dp)
        ),
        border = ClickableSurfaceDefaults.border(
            border = TvBorder(border = BorderStroke(0.dp, Color.Transparent)),
            focusedBorder = TvBorder(border = BorderStroke(0.dp, Color.Transparent))
        )
    ) {
        // Inner Box paints the animated pill background so the color change
        // is smoothly tweened. The Surface above provides the click + focus
        // surface area; the visual pill lives here.
        Box(
            Modifier
                .fillMaxSize()
                .background(pillColor, RoundedCornerShape(24.dp))
                .padding(end = 14.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                // Start padding is animated — text content slides slightly right
                // when the row becomes focused, slightly left when it loses focus.
                // Combined with the pill fade-in this gives a "text settling in"
                // feel that matches Nextv's category row enter/exit animation.
                Modifier.fillMaxWidth().padding(start = textStartPad),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    category.name,
                    color = textColor,
                    fontSize = 13.sp,
                    lineHeight = 15.sp,
                    fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // No more checkmark — the white pill now indicates selected state.

                Text(
                    category.count.toString(),
                    fontSize = 12.sp,
                    color = countColor
                )
            }
        }
    }
}

@Composable
fun MediaGrid(
    items: List<MediaItem>,
    columns: Int,
    onFocused: (MediaItem) -> Unit,
    onClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    EdgeFollowBringIntoView {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = modifier,
            contentPadding = PaddingValues(2.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            items(
                items,
                key = { it.streamType + it.id },
                contentType = { "poster_tile" }
            ) { item ->
                PosterTile(item, onFocused = { onFocused(item) }, onClick = { onClick(item) })
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PosterTile(item: MediaItem, onFocused: () -> Unit, onClick: () -> Unit) {
    // Used by the search-results grid (5-column MediaGrid). Migrated to TV
    // Surface for the same focus-animation benefit as MoviePosterTile.
    TvSurface(
        onClick = onClick,
        modifier = Modifier
            .width(132.dp)
            .height(184.dp)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(8.dp),
            focusedShape = RoundedCornerShape(8.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Black,
            focusedContainerColor = Color.Black,
            contentColor = QtoneColors.Text,
            focusedContentColor = QtoneColors.Text
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        glow = ClickableSurfaceDefaults.glow(
            glow = Glow(elevationColor = Color.Transparent, elevation = 0.dp),
            focusedGlow = Glow(elevationColor = Color.Transparent, elevation = 0.dp)
        ),
        border = ClickableSurfaceDefaults.border(
            border = TvBorder(border = BorderStroke(0.dp, Color.Transparent)),
            focusedBorder = TvBorder(border = BorderStroke(2.dp, Color.White))
        )
    ) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = item.poster,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xC0000000)))))
            Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                Text(item.name, color = QtoneColors.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.year?.let { Text(it.take(4), color = QtoneColors.Muted, fontSize = 10.sp) }
            }
        }
    }
}


@Composable
fun MovieMediaGrid(
    items: List<MediaItem>,
    movieFavorites: Set<String>,
    focusedItemId: String? = null,
    restoreFocusRequest: Int = 0,
    savedScroll: androidx.compose.runtime.MutableState<Pair<Int, Int>?>? = null,
    selectedCategoryId: String = "",
    // Column count is fixed at 5 for the sidebar-always-visible layout.
    // Kept as a parameter so the internal column-lock modulo logic stays
    // generic (and so non-poster layouts that share the same grid can use
    // different column counts if needed).
    columns: Int = 5,
    onFocused: (MediaItem) -> Unit,
    onClick: (MediaItem) -> Unit,
    onLongPress: (MediaItem) -> Unit,
    // Called whenever a tile gains focus, with its window-space bounds in
    // dp. Used by the quick-info popup to anchor itself next to the focused
    // card. The callback receives null when no card is focused (e.g. when
    // focus moves to the sidebar). Optional — default no-op makes existing
    // call sites unaffected.
    onCardBoundsCaptured: (com.qtone.app.FocusedCardBounds?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val gridState = rememberLazyGridState()
    val restoreFocusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    // Scroll to the first card when the category changes.
    LaunchedEffect(selectedCategoryId) {
        if (selectedCategoryId.isNotEmpty()) {
            gridState.scrollToItem(0)
        }
    }

    // ── Column-lock state (same pattern as Live TV grid) ─────────────────
    // Prevents the user from being "jumped" to a different column when
    // pressing Down/Up causes Compose's geometric focus search to land on
    // the leftmost attached focusable because the true target row isn't
    // yet composed. Self-contained: state stays inside the grid composable.
    var preferredColumn by remember { mutableStateOf<Int?>(null) }
    var verticalNavigationPending by remember { mutableStateOf(false) }
    var redirectingFocus by remember { mutableStateOf(false) }
    val itemFocusRequesters = remember(items) { items.associate { it.id to FocusRequester() } }

    LaunchedEffect(restoreFocusRequest) {
        if (restoreFocusRequest > 0) {
            val index = items.indexOfFirst { it.id == focusedItemId }
            if (index >= 0) {
                try {
                    // Prefer restoring the EXACT viewport (first-visible item +
                    // its scroll offset) that was captured when the user clicked
                    // a tile. This keeps the previously-focused row in the same
                    // visual position as when the user left it, rather than
                    // jumping the target tile to the top of the viewport.
                    // Fallback: if there's no saved scroll, scrolling to the
                    // target item index keeps the original behavior intact.
                    val saved = savedScroll?.value
                    if (saved != null && saved.first in items.indices) {
                        gridState.scrollToItem(saved.first, saved.second)
                    } else {
                        gridState.scrollToItem(index)
                    }
                    delay(80)
                    restoreFocusRequester.requestFocus()
                } catch (_: Throwable) {
                }
            }
        }
    }

    EdgeFollowBringIntoView {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = modifier,
            // Generous top/bottom padding so the focused (scaled-up) tile has
            // room to grow on the top row without being clipped by the grid's
            // bounding box. Side padding stays tight; verticalArrangement spacing
            // handles inter-row room for scale.
            // Horizontal padding gives the leftmost and rightmost cards room to
            // scale up (1.08x ≈ +11dp each side) without clipping into the
            // sidebar or off the right screen edge. The grid still clips its
            // bounding box, so without this padding the scaled edges would be
            // chopped off.
            contentPadding = PaddingValues(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            itemsIndexed(
                items,
                key = { _, item -> item.streamType + item.id },
                contentType = { _, _ -> "movie_poster_tile" }
            ) { index, item ->
                val itemRequester = itemFocusRequesters[item.id] ?: FocusRequester()

                // Modifier merges: restore-focus (rare, one-shot) + column
                // tracking key listener + per-item focus requester. Order is
                // important — restoreFocusRequester takes precedence when set.
                // Two-way modifier choice:
                //   - Item is the back-from-detail restore target → use the
                //     dedicated restore requester so the LaunchedEffect can
                //     pull focus to it after returning from detail.
                //   - Otherwise → the per-item requester (used by column-lock).
                val baseModifier = if (item.id == focusedItemId) {
                    Modifier.focusRequester(restoreFocusRequester)
                } else {
                    Modifier.focusRequester(itemRequester)
                }

                // Per-tile bounds tracking for the quick-info popup.
                //
                // There are two callbacks involved on every focus event:
                //   - onGloballyPositioned: fires after layout completes,
                //     reports the tile's window-space coordinates.
                //   - onFocusChanged: fires when focus enters/exits the tile.
                //
                // On FIRST mount of the grid (e.g. app launch, or returning
                // from another top-bar section), the order these two callbacks
                // fire isn't guaranteed. If onFocusChanged fires first — which
                // can happen on the very first navigation into a card — then
                // tileBounds.value is still null and the parent never gets
                // the bounds, so the quick-info popup silently does nothing.
                //
                // Fix: track focus state ourselves and have BOTH callbacks try
                // to report bounds. Whichever fires last with both pieces of
                // information (focused = true AND bounds available) wins.
                // This eliminates the race entirely.
                //
                // Density is needed to convert px → dp. The coordinates
                // reported by positionInWindow are in pixels, but the popup
                // operates in dp.
                val tileBounds = remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
                val tileIsFocused = remember { mutableStateOf(false) }
                val density = androidx.compose.ui.platform.LocalDensity.current

                // Common reporter — invoked from either callback once both
                // focus AND positioning are known.
                val reportBoundsIfReady: () -> Unit = report@{
                    if (!tileIsFocused.value) return@report
                    val coords = tileBounds.value ?: return@report
                    val pos = coords.positionInWindow()
                    val widthPx = coords.size.width.toFloat()
                    val heightPx = coords.size.height.toFloat()
                    onCardBoundsCaptured(
                        com.qtone.app.FocusedCardBounds(
                            itemId = item.id,
                            xDp = with(density) { pos.x.toDp() },
                            yDp = with(density) { pos.y.toDp() },
                            widthDp = with(density) { widthPx.toDp() },
                            heightDp = with(density) { heightPx.toDp() }
                        )
                    )
                }

                val tileModifier = baseModifier
                    .onGloballyPositioned { coords ->
                        tileBounds.value = coords
                        // Try reporting in case focus is already on this tile.
                        // Covers the case where onFocusChanged fired BEFORE
                        // onGloballyPositioned on initial mount.
                        reportBoundsIfReady()
                    }
                    .onFocusChanged { focusState ->
                        tileIsFocused.value = focusState.isFocused
                        if (focusState.isFocused) {
                            // Try reporting in case positioning has already
                            // happened. Covers the normal case where
                            // onGloballyPositioned fired during layout before
                            // focus arrived.
                            reportBoundsIfReady()
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            when (event.key) {
                                Key.DirectionDown, Key.DirectionUp -> {
                                    if (preferredColumn == null) {
                                        preferredColumn = index % columns
                                    }
                                    verticalNavigationPending = true
                                }
                                Key.DirectionLeft, Key.DirectionRight -> {
                                    // Reset column-lock on any horizontal navigation.
                                    // For LEFT on column 0, Compose's default focus
                                    // traversal moves focus into the sidebar — no
                                    // custom handler needed since the sidebar is
                                    // always present.
                                    preferredColumn = null
                                    verticalNavigationPending = false
                                }
                            }
                        }
                        false
                    }

                MoviePosterTile(
                    item = item,
                    isFavorite = movieFavorites.contains(item.id),
                    onFocused = {
                        val preferred = preferredColumn
                        val currentColumn = index % columns

                        if (verticalNavigationPending &&
                            !redirectingFocus &&
                            preferred != null &&
                            currentColumn != preferred
                        ) {
                            val rowStart = index - currentColumn
                            val targetIndex = rowStart + preferred

                            if (targetIndex in items.indices) {
                                redirectingFocus = true
                                verticalNavigationPending = false
                                coroutineScope.launch {
                                    try {
                                        val attachedNow = gridState.layoutInfo
                                            .visibleItemsInfo.any { it.index == targetIndex }
                                        val targetId = items.getOrNull(targetIndex)?.id
                                        val requester = targetId?.let { itemFocusRequesters[it] }

                                        if (attachedNow && requester != null) {
                                            requester.requestFocus()
                                        } else if (requester != null) {
                                            withTimeoutOrNull(200) {
                                                snapshotFlow {
                                                    gridState.layoutInfo
                                                        .visibleItemsInfo.any { it.index == targetIndex }
                                                }.first { it }
                                            }
                                            try { requester.requestFocus() } catch (_: Throwable) {}
                                        }
                                    } catch (_: Throwable) {}
                                    delay(40)
                                    redirectingFocus = false
                                }
                                return@MoviePosterTile
                            }
                            // Target row genuinely lacks the preferred column —
                            // accept current placement but preserve preferredColumn
                            // for the next press.
                            verticalNavigationPending = false
                            onFocused(item)
                            return@MoviePosterTile
                        }

                        if (!verticalNavigationPending) {
                            preferredColumn = currentColumn
                        }
                        verticalNavigationPending = false
                        onFocused(item)
                    },
                    onClick = {
                        // Capture the exact viewport position so we can return
                        // to it when the user presses Back from the detail
                        // screen. Without this, the LaunchedEffect above only
                        // knows the focused item index and would place it at
                        // the top of the viewport.
                        savedScroll?.value = Pair(
                            gridState.firstVisibleItemIndex,
                            gridState.firstVisibleItemScrollOffset
                        )
                        onClick(item)
                    },
                    onLongPress = { onLongPress(item) },
                    modifier = tileModifier
                )
            }
        }
    }
}

// ── Poster dominant color extraction for focus glow ────────────────────
// Extracts the dominant color from a poster image using Android's Palette API.
// Results are cached in a static map so each poster URL is processed only once.
// Extraction only runs when a card is focused (one at a time), preventing
// the IO burst that caused scroll jitter on initial load.

private val dominantColorCache = java.util.concurrent.ConcurrentHashMap<String, Color>()
private val DEFAULT_GLOW_COLOR = Color(0xFF6C5CE7) // subtle purple fallback

@Composable
private fun rememberDominantColor(posterUrl: String?, isFocused: Boolean): Color {
    if (posterUrl.isNullOrBlank()) return DEFAULT_GLOW_COLOR

    // Return cached color immediately if available
    val cached = dominantColorCache[posterUrl]
    if (cached != null) return cached

    // Only extract when focused — prevents 12+ simultaneous extractions
    // during initial scroll. The glow only shows on the focused card anyway.
    if (!isFocused) return DEFAULT_GLOW_COLOR

    var color by remember(posterUrl) { mutableStateOf(DEFAULT_GLOW_COLOR) }
    val context = LocalContext.current

    LaunchedEffect(posterUrl) {
        dominantColorCache[posterUrl]?.let { color = it; return@LaunchedEffect }

        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val loader = ImageLoader(context)
                val request = ImageRequest.Builder(context)
                    .data(posterUrl)
                    .allowHardware(false)
                    .size(80, 120)
                    .build()
                val result = loader.execute(request)
                if (result is SuccessResult) {
                    val bitmap = result.drawable.toBitmap()
                    val palette = Palette.from(bitmap).generate()
                    val dominant = palette.getVibrantColor(
                        palette.getMutedColor(
                            palette.getDominantColor(0xFF6C5CE7.toInt())
                        )
                    )
                    val extracted = Color(dominant)
                    dominantColorCache[posterUrl] = extracted
                    color = extracted
                }
            } catch (_: Exception) {
                // Extraction failed — keep default color
            }
        }
    }

    return color
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MoviePosterTile(
    item: MediaItem,
    isFavorite: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    focusedScale: Float = 1.08f,
    showGlow: Boolean = true,
    animationStiffness: Float = 2500f
) {
    // Migrated to androidx.tv.material3.Surface (the TV-optimized variant).
    //
    // The TV Surface comes with built-in focus animations that run on the render
    // thread — scale, glow (shadow), and border transitions are all handled by
    // the library and tuned by Google specifically for TV remote navigation. We
    // no longer manage manual animateFloatAsState / graphicsLayer / Spring specs
    // for those properties.
    //
    // Layout: size determined by the LazyVerticalGrid cell allocation
    // (6 columns → ~270dp wide on 1080p Fire TV) plus 0.66 aspect ratio
    // (true 2:3 poster). No title strip — the poster fills the entire card.
    //
    // Long-press for favorite: handled by the TV Surface's onLongClick parameter
    // (replaces the manual onPreviewKeyEvent + timer approach).
    //
    // Focus tracking: we still notify the parent via onFocused() so the
    // ViewModel can keep state.focusedItem updated (used by Menu-long-press to
    // clear Continue Watching, and by the column-lock fix). This is the only
    // remaining manual focus-related code on the tile.
    // Manual focus state + manual scale animation. We override the tv-material
    // library's default scale (which feels stiff because we can't tune its
    // internal spring spec through the public API) with our own animateFloat
    // backed by a stiffer Compose spring.
    //
    // The scale is applied via graphicsLayer — a render-thread GPU transform
    // that does not trigger relayout. So even though the animation value lives
    // in Compose state, the visible motion is hardware-accelerated.
    var focused by remember { mutableStateOf(false) }
    val glowColor = if (showGlow) rememberDominantColor(item.poster, focused) else DEFAULT_GLOW_COLOR
    val animatedScale by animateFloatAsState(
        targetValue = if (focused) focusedScale else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = animationStiffness
        ),
        label = "tileScale"
    )

    // Animated glow opacity — only draws when focused and showGlow is true.
    val glowAlpha by animateFloatAsState(
        targetValue = if (focused && showGlow) 0.55f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = animationStiffness
        ),
        label = "glowAlpha"
    )

    TvSurface(
        onClick = onClick,
        onLongClick = onLongPress,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.66f)
            .drawBehind {
                // Smooth poster-colored glow using 12 layered rounded rects
                // with exponential alpha falloff. More layers = smoother
                // gradient that closely mimics a real gaussian blur.
                // Works identically on Fire OS, Google TV, Shield — all devices.
                if (glowAlpha > 0f) {
                    val layers = 12
                    for (i in layers downTo 1) {
                        val fraction = i.toFloat() / layers
                        val spread = (fraction * 26).dp.toPx()
                        // Exponential falloff: outer layers fade much faster
                        val layerAlpha = glowAlpha * (1f - fraction) * (1f - fraction)
                        drawRoundRect(
                            color = glowColor.copy(alpha = layerAlpha),
                            topLeft = androidx.compose.ui.geometry.Offset(-spread, -spread),
                            size = androidx.compose.ui.geometry.Size(
                                size.width + spread * 2,
                                size.height + spread * 2
                            ),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                (10.dp.toPx() + spread)
                            )
                        )
                    }
                }
            }
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            },
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(10.dp),
            focusedShape = RoundedCornerShape(10.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF07070A),
            focusedContainerColor = Color(0xFF07070A),
            contentColor = QtoneColors.Text,
            focusedContentColor = QtoneColors.Text
        ),
        // Library scale disabled (1.0) — we drive scale manually above. The
        // library's border path is still used though, which is why
        // we keep the TvSurface.
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = 1.0f
        ),
        // Glow disabled — handled by custom drawBehind above.
        glow = ClickableSurfaceDefaults.glow(
            glow = Glow(elevationColor = Color.Transparent, elevation = 0.dp),
            focusedGlow = Glow(elevationColor = Color.Transparent, elevation = 0.dp)
        ),
        // Focus indication = thin white outline. No border at rest (the poster
        // image edge defines the card on a black background). On focus a
        // 2dp bright white outline appears — Nextv's exact pattern.
        border = ClickableSurfaceDefaults.border(
            border = TvBorder(border = BorderStroke(0.dp, Color.Transparent)),
            focusedBorder = TvBorder(border = BorderStroke(2.dp, Color.White))
        )
    ) {
        // Nextv-style: poster fills the entire card. No title strip, no year.
        // The poster IS the identification. Favorite star overlays the top-right.
        // The bottom gradient is also removed since there's no text below it
        // to darken anymore — the poster sits clean on the black background.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AsyncImage(
                model = item.poster,
                contentDescription = item.name,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )

            if (isFavorite) {
                Text(
                    "★",
                    color = Color(0xFFFFC857),
                    fontSize = 17.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                )
            }
        }
    }
}

@Composable
fun CompactMovieInfoPanel(item: MediaItem?, modifier: Modifier = Modifier.fillMaxHeight().width(205.dp)) {
    Box(
        modifier
            .background(Color(0x66000000))
    ) {
        if (item == null) {
            Box(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 18.dp)) {
                Text("Info", color = QtoneColors.Muted, fontSize = 13.sp)
            }
        } else {
            // Background poster image removed for speed and visual consistency —
            // the panel now uses a flat dark background and shows text only.
            Box(Modifier.fillMaxSize().background(Color(0x66000000)))

            Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 18.dp)) {
                Text(
                    item.name,
                    color = QtoneColors.Text,
                    fontSize = 16.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    listOfNotNull(item.year?.take(4), item.genre).joinToString(" · "),
                    color = Color(0xFFD0CED8),
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                item.rating?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("Rating ${it.take(3)}", color = Color(0xCCFFFFFF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    item.plot ?: "No summary available.",
                    color = QtoneColors.Text,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 15,
                    overflow = TextOverflow.Ellipsis
                )

                item.director?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(10.dp))
                    Text("Director", color = Color(0xCCFFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(it, color = QtoneColors.Text, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }

                item.cast?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("Cast", color = Color(0xCCFFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(it, color = QtoneColors.Text, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ChannelTile(
    item: MediaItem,
    isFavorite: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Migrated to androidx.tv.material3.Surface. The Surface IS the focusable
    // logo container; the channel name is a non-focusable Text below it (sits
    // outside the Surface so it doesn't scale with the focus animation).
    //
    // Same snappy manual scale as MoviePosterTile (stiffness 1800).
    // graphicsLayer keeps the transform GPU-accelerated.
    var focused by remember { mutableStateOf(false) }
    val animatedScale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = 2500f
        ),
        label = "channelTileScale"
    )

    Column(modifier = modifier.width(126.dp)) {
        TvSurface(
            onClick = onClick,
            onLongClick = onLongPress,
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp)
                .graphicsLayer {
                    scaleX = animatedScale
                    scaleY = animatedScale
                }
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                },
            shape = ClickableSurfaceDefaults.shape(
                shape = RoundedCornerShape(8.dp),
                focusedShape = RoundedCornerShape(8.dp)
            ),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color(0xFF131318),
                focusedContainerColor = Color(0xFF1F1A28),
                contentColor = QtoneColors.Text,
                focusedContentColor = QtoneColors.Text
            ),
            // Library scale disabled — manual scale via graphicsLayer above.
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
            glow = ClickableSurfaceDefaults.glow(
                glow = Glow(elevationColor = Color.Transparent, elevation = 0.dp),
                focusedGlow = Glow(elevationColor = Color.Transparent, elevation = 0.dp)
            ),
            // Nextv-style focus: thin white outline only. The channel logo
            // background colour (Color(0xFF131318)) already gives the card
            // its visible edge at rest.
            border = ClickableSurfaceDefaults.border(
                border = TvBorder(border = BorderStroke(0.dp, Color.Transparent)),
                focusedBorder = TvBorder(border = BorderStroke(2.dp, Color.White))
            )
        ) {
            Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = item.poster,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                if (isFavorite) {
                    Text(
                        "★",
                        color = Color(0xFFFFC857),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopEnd)
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            item.name,
            color = QtoneColors.Text,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun InfoPanel(item: MediaItem?, isLive: Boolean, onPlay: () -> Unit = {}) {
    Box(Modifier.fillMaxHeight().width(338.dp).background(Color(0x66000000)).padding(26.dp)) {
        if (item == null) {
            Text("Navigate to a title to see info", color = QtoneColors.Muted, fontSize = 17.sp)
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name, color = QtoneColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    item.rating?.takeIf { it.isNotBlank() }?.let {
                        Box(Modifier.size(62.dp).border(3.dp, Color(0xFFFFFFFF), CircleShape), contentAlignment = Alignment.Center) {
                            Text(it.take(3), color = QtoneColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(listOfNotNull(item.year?.take(4), item.genre).joinToString(" · "), color = QtoneColors.Muted, fontSize = 14.sp, maxLines = 1)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetaChip(if (isLive) "LIVE" else "HD")
                    MetaChip("16:9")
                    MetaChip("H264")
                }
                Spacer(Modifier.height(28.dp))
                Text(item.plot ?: if (isLive) "Live channel from your IPTV provider." else "No summary available.", color = QtoneColors.Text, fontSize = 16.sp, lineHeight = 23.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(24.dp))
                item.director?.takeIf { it.isNotBlank() }?.let {
                    InfoLabel("Director", it)
                    Spacer(Modifier.height(12.dp))
                }
                item.cast?.takeIf { it.isNotBlank() }?.let {
                    InfoLabel("Cast", it)
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun InfoLabel(label: String, value: String) {
    Column {
        Text(label, color = Color(0xFFFFFFFF), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(value, color = QtoneColors.Text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MetaChip(text: String) {
    Text(text, color = QtoneColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(Color(0x663C3742), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 5.dp))
}

@Composable
fun PurpleButton(
    text: String,
    onClick: () -> Unit,
    downFocusRequester: FocusRequester? = null
) {
    var focused by remember { mutableStateOf(false) }
    val buttonColor by animateColorAsState(
        targetValue = if (focused) Color(0xFFFFFFFF) else Color(0xFF2A2238),
        animationSpec = tween(durationMillis = 150),
        label = "purpleButtonColor"
    )
    val buttonBorderColor by animateColorAsState(
        targetValue = if (focused) Color(0xFFE8D7FF) else Color(0x44FFFFFF),
        animationSpec = tween(durationMillis = 150),
        label = "purpleButtonBorderColor"
    )
    val buttonBorderWidth by animateDpAsState(
        targetValue = if (focused) 2.dp else 1.dp,
        animationSpec = tween(durationMillis = 150),
        label = "purpleButtonBorderWidth"
    )
    // Text inverts to dark when the button is focused (white background).
    // Without this the button label disappeared when focused because both
    // the fill and the text were white.
    val textColor by animateColorAsState(
        targetValue = if (focused) Color(0xFF0A0A0E) else Color.White,
        animationSpec = tween(durationMillis = 150),
        label = "purpleButtonTextColor"
    )

    Surface(
        onClick = onClick,
        modifier = Modifier
            .height(46.dp)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.key == Key.DirectionDown && event.type == KeyEventType.KeyDown && downFocusRequester != null) {
                    downFocusRequester.requestFocus()
                    true
                } else {
                    false
                }
            },
        shape = RoundedCornerShape(12.dp),
        color = buttonColor,
        border = BorderStroke(buttonBorderWidth, buttonBorderColor),
        shadowElevation = if (focused) 10.dp else 2.dp
    ) {
        Box(
            Modifier
                .padding(horizontal = 22.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text,
                color = textColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}


@Composable
fun DarkButton(text: String, onClick: () -> Unit = {}) {
    var focused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(8.dp),
        color = if (focused) Color(0x33FFFFFF) else Color(0xCC17171C),
        contentColor = QtoneColors.Text,
        border = BorderStroke(
            if (focused) 2.dp else 1.dp,
            if (focused) Color(0xFFEDE7FF) else Color(0x33FFFFFF)
        )
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                color = if (focused) Color.White else QtoneColors.Text,
                fontSize = 15.sp,
                fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}


@Composable
fun MovieDetailScreen(
    item: MediaItem,
    showSimilar: Boolean = true,
    similarItems: List<MediaItem> = emptyList(),
    movieFavorites: Set<String> = emptySet(),
    seriesEpisodes: List<SeriesEpisode> = emptyList(),
    isLoadingEpisodes: Boolean = false,
    isPlotLoading: Boolean = false,
    watchedEpisodeIds: Set<String> = emptySet(),
    initialSimilarFocusId: String? = null,
    forceFirstSimilarFocusForItemId: String? = null,
    onToggleSimilarFavorite: (MediaItem) -> Unit = {},
    onSimilarFocused: (MediaItem) -> Unit = {},
    onSimilarOpen: (MediaItem) -> Unit = {},
    onEpisodeOpen: (SeriesEpisode) -> Unit = {},
    onPlay: () -> Unit,
    onBackHint: String = "Press Back to return"
) {
    // Cap to 6 cards — user requested simpler 6-card row instead of the
    // previous 12-card narrow-strip layout with side-info panel.
    val visibleSimilar = if (showSimilar) similarItems.take(6) else emptyList()
    val visibleSimilarIdsKey = remember(visibleSimilar) { visibleSimilar.joinToString("|") { it.id } }
    val firstSimilarFocusRequester = remember(item.id) { FocusRequester() }
    val initialSimilarFocusRequester = remember(item.id, initialSimilarFocusId) { FocusRequester() }

    fun preferredInitialSimilarId(): String? =
        initialSimilarFocusId?.let { id -> visibleSimilar.firstOrNull { it.id == id }?.id }
            ?: visibleSimilar.firstOrNull()?.id

    var focusedSimilarId by remember(item.id, initialSimilarFocusId) { mutableStateOf(preferredInitialSimilarId()) }
    val focusedSimilarDisplay = visibleSimilar.firstOrNull { it.id == focusedSimilarId }

    LaunchedEffect(item.id, visibleSimilarIdsKey, initialSimilarFocusId) {
        val currentStillExists = visibleSimilar.any { it.id == focusedSimilarId }

        // Only choose a new default if the current focused movie disappeared or no focus exists.
        // TMDB metadata updates may replace MediaItem objects, but the IDs stay the same,
        // so this prevents focus from jumping back to the first Similar Movies card.
        if (!currentStillExists) {
            focusedSimilarId = preferredInitialSimilarId()
        }

        if (
            showSimilar &&
            initialSimilarFocusId != null &&
            visibleSimilar.any { it.id == initialSimilarFocusId }
        ) {
            delay(80)
            initialSimilarFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(item.id, forceFirstSimilarFocusForItemId, visibleSimilarIdsKey) {
        if (
            showSimilar &&
            forceFirstSimilarFocusForItemId == item.id &&
            visibleSimilar.isNotEmpty()
        ) {
            focusedSimilarId = visibleSimilar.firstOrNull()?.id
            delay(80)
            firstSimilarFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(focusedSimilarId, visibleSimilarIdsKey) {
        visibleSimilar.firstOrNull { it.id == focusedSimilarId }?.let { onSimilarFocused(it) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black, Color(0xEE050506), Color(0x88050506)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent, Color(0xEE000000)))))

        // Top detail area unchanged.
        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = 42.dp, top = 24.dp, end = 42.dp)
                .fillMaxWidth()
                .height(230.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF07070A),
                border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                modifier = Modifier.width(140.dp).height(215.dp)
            ) {
                AsyncImage(
                    model = item.poster,
                    contentDescription = item.name,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(Modifier.width(26.dp))

            Column(Modifier.weight(1f).fillMaxHeight()) {
                Text(
                    item.name,
                    color = QtoneColors.Text,
                    fontSize = 30.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(7.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    item.rating?.takeIf { it.isNotBlank() }?.let { MetaChip("TMDB ${it.take(3)}") }
                    item.year?.takeIf { it.isNotBlank() }?.let { MetaChip(it.take(4)) }
                    item.genre?.takeIf { it.isNotBlank() }?.let { MetaChip(it.take(36)) }
                }

                Spacer(Modifier.height(10.dp))

                Text(
                    when {
                        !item.plot.isNullOrBlank() -> item.plot
                        isPlotLoading -> "Loading plot..."
                        else -> "No summary available."
                    },
                    color = QtoneColors.Text,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 900.dp)
                )

                Spacer(Modifier.height(10.dp))

                if (showSimilar) {
                    PurpleButton("▷ Play", onPlay, downFocusRequester = if (visibleSimilar.isNotEmpty()) firstSimilarFocusRequester else null)
                }
            }
        }

        if (showSimilar) {
            // Show the header only when there are cards to display.
            //
            // The TMDB recommendations fetch is async — during the brief
            // window after detail-open but before results arrive (~1-2
            // seconds typically), visibleSimilar is empty. Without this
            // guard the user would see a "Similar Movies" header floating
            // over an empty space during that window.
            //
            // Same gating cleanly handles the zero-match case (a movie for
            // which either TMDB has no recommendations or none of TMDB's
            // recommendations exist in the user's provider catalog) — no
            // header, no empty row, the screen just ends at the cast text.
            //
            // This is a pure cosmetic improvement — it does NOT touch the
            // movie-vs-series rendering decision. That decision is the
            // outer `if (showSimilar)` branch above, which we leave alone.
            if (visibleSimilar.isNotEmpty()) {
                Text(
                    "Similar Movies",
                    color = QtoneColors.Text,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.offset(x = 42.dp, y = 280.dp)
                )
            }

            // 6 standard-style poster cards laid out horizontally at the bottom
            // of the detail screen. Same MoviePosterTile component the main
            // grid uses — scale-on-focus, favorite-star overlay, long-press,
            // and animation timings are identical to the rest of the app.
            //
            // Card size 130dp × 195dp (true 2:3 portrait), 16dp horizontal
            // spacing. Total row width: 6×130 + 5×16 = 860dp. Sits within
            // the typical detail-screen content area (1280dp − 84dp margins
            // = 1196dp) with room to spare.
            //
            // Smaller than the main grid's 5-column cards (~172dp wide) so
            // they don't dominate the lower half of the detail screen.
            Row(
                Modifier
                    .offset(x = 42.dp, y = 322.dp)
                    .fillMaxWidth()
                    .padding(end = 42.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                visibleSimilar.forEachIndexed { index, similar ->
                    val tileFocusModifier = when {
                        initialSimilarFocusId == similar.id -> Modifier.focusRequester(initialSimilarFocusRequester)
                        index == 0 -> Modifier.focusRequester(firstSimilarFocusRequester)
                        else -> Modifier
                    }

                    Box(
                        Modifier
                            .width(130.dp)
                            .height(195.dp)
                    ) {
                        MoviePosterTile(
                            item = similar,
                            isFavorite = movieFavorites.contains(similar.id),
                            onFocused = { focusedSimilarId = similar.id },
                            onClick = { onSimilarOpen(similar) },
                            onLongPress = { onToggleSimilarFavorite(similar) },
                            // Same scale as the main grid (1.08f) to
                            // compensate for the smaller card size (130dp vs ~172dp)
                            // so the focus scale feels equally prominent.
                            focusedScale = 1.15f,
                            showGlow = false,
                            // Higher stiffness compensates for the larger scale
                            // distance (0.15 vs 0.08) so the animation settles
                            // just as fast as the main grid cards.
                            animationStiffness = 4500f,
                            modifier = tileFocusModifier
                        )
                    }
                }
            }
        } else {
            SeriesSeasonsEpisodesSection(
                episodes = seriesEpisodes,
                isLoading = isLoadingEpisodes,
                onEpisodeOpen = onEpisodeOpen,
                watchedEpisodeIds = watchedEpisodeIds,
                modifier = Modifier
                    .offset(x = 42.dp, y = 250.dp)
                    .fillMaxWidth()
                    .height(270.dp)
                    .padding(end = 42.dp)
            )
        }
    }
}



@Composable
private fun SeriesSeasonsEpisodesSection(
    episodes: List<SeriesEpisode>,
    isLoading: Boolean = false,
    onEpisodeOpen: (SeriesEpisode) -> Unit,
    watchedEpisodeIds: Set<String> = emptySet(),
    modifier: Modifier = Modifier
) {
    val seasons = remember(episodes) {
        episodes.map { it.seasonNumber }.distinct().sorted().ifEmpty { listOf(1) }
    }
    var selectedSeason by remember(episodes) { mutableStateOf(seasons.firstOrNull() ?: 1) }
    val visibleEpisodes = remember(episodes, selectedSeason) {
        episodes.filter { it.seasonNumber == selectedSeason }
            .sortedBy { it.episodeNumber }
    }

    Column(modifier) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(seasons, key = { it }) { season ->
                SeasonButton(
                    text = "Season $season",
                    selected = selectedSeason == season,
                    onClick = { selectedSeason = season }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (episodes.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0x66000000),
                border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                modifier = Modifier.fillMaxWidth().height(74.dp)
            ) {
                Box(
                    Modifier.fillMaxSize().padding(horizontal = 18.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        if (isLoading) "Loading episodes..." else "No episodes available",
                        color = QtoneColors.Muted,
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(visibleEpisodes, key = { it.id }) { episode ->
                    EpisodeRow(
                        episode = episode,
                        isWatched = watchedEpisodeIds.contains(episode.id),
                        onClick = { onEpisodeOpen(episode) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SeasonButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val active = focused || selected

    Surface(
        onClick = onClick,
        modifier = Modifier
            .height(42.dp)
            .onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(12.dp),
        color = if (active) Color(0xFF1F1A28) else Color(0xFF17141D),
        border = BorderStroke(
            if (active) 2.dp else 1.dp,
            if (active) Color(0xFFFFFFFF) else Color(0x22FFFFFF)
        )
    ) {
        Box(
            Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text, color = QtoneColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: SeriesEpisode,
    isWatched: Boolean = false,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val episodeRowSurfaceColor by animateColorAsState(
        targetValue = if (focused) Color(0xFF1F1A28) else Color(0xAA07070A),
        animationSpec = tween(durationMillis = 150),
        label = "episodeRowSurfaceColor"
    )
    val episodeRowBorderColor by animateColorAsState(
        targetValue = if (focused) Color(0xFFFFFFFF) else Color(0x22FFFFFF),
        animationSpec = tween(durationMillis = 150),
        label = "episodeRowBorderColor"
    )
    val episodeRowBorderWidth by animateDpAsState(
        targetValue = if (focused) 2.dp else 1.dp,
        animationSpec = tween(durationMillis = 150),
        label = "episodeRowBorderWidth"
    )

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(14.dp),
        color = episodeRowSurfaceColor,
        border = BorderStroke(episodeRowBorderWidth, episodeRowBorderColor)
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Episode ${episode.episodeNumber.takeIf { it > 0 } ?: ""}  ${episode.title}",
                        color = if (isWatched) Color(0xFF666680) else QtoneColors.Text,
                        fontSize = 15.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isWatched) {
                        Spacer(Modifier.width(8.dp))
                        Text("✓", color = Color(0xFF4CAF50), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    episode.plot ?: "No episode information available.",
                    color = if (isWatched) Color(0xFF555568) else Color(0xFFD0CED8),
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}



@Composable
private fun SimilarMovieNarrowTile(
    item: MediaItem,
    selected: Boolean,
    isFavorite: Boolean,
    onFocused: () -> Unit,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val borderWidth by animateDpAsState(
        targetValue = if (focused || selected) 3.dp else 1.dp,
        animationSpec = tween(durationMillis = 150),
        label = "similarPosterBorderWidth"
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused || selected) Color(0xFFFFFFFF) else Color(0x22FFFFFF),
        animationSpec = tween(durationMillis = 150),
        label = "similarPosterBorderColor"
    )
    val surfaceColor by animateColorAsState(
        targetValue = if (focused || selected) Color(0xFF090911) else Color(0xFF07070A),
        animationSpec = tween(durationMillis = 150),
        label = "similarPosterSurfaceColor"
    )
    var favoritePressStartMs by remember { mutableStateOf(0L) }
    var favoriteLongPressFired by remember { mutableStateOf(false) }
    val isActive = focused || selected

    fun isOkKey(key: Key): Boolean =
        key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(65.dp)
            .height(109.dp)
            .onPreviewKeyEvent { event ->
                if (!isOkKey(event.key)) return@onPreviewKeyEvent false

                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (favoritePressStartMs == 0L) {
                            favoritePressStartMs = System.currentTimeMillis()
                            favoriteLongPressFired = false
                        }

                        if (!favoriteLongPressFired &&
                            System.currentTimeMillis() - favoritePressStartMs >= 1100L
                        ) {
                            favoriteLongPressFired = true
                            onToggleFavorite()
                            true
                        } else {
                            false
                        }
                    }
                    KeyEventType.KeyUp -> {
                        val consume = favoriteLongPressFired
                        favoritePressStartMs = 0L
                        favoriteLongPressFired = false
                        consume
                    }
                    else -> false
                }
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            },
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF07070A),
        border = if (isActive) BorderStroke(3.dp, Color(0xFFFFFFFF)) else BorderStroke(1.dp, Color(0x22FFFFFF))
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(78.dp)
                    .background(Color.Black)
            ) {
                AsyncImage(
                    model = item.poster,
                    contentDescription = item.name,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )

                if (isFavorite) {
                    Text(
                        "★",
                        color = Color(0xFFFFD54F),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 3.dp, end = 3.dp)
                    )
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(31.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0xEE07070A))
                            )
                        )
                )
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xEE07070A), Color(0xFF050507))
                        )
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Column {
                    Text(
                        item.name,
                        color = QtoneColors.Text,
                        fontSize = 7.sp,
                        lineHeight = 8.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    item.year?.take(4)?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            color = QtoneColors.Muted,
                            fontSize = 6.sp,
                            lineHeight = 7.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}



@Composable
private fun SimilarMovieHorizontalInfo(item: MediaItem?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = Color(0xAA07070A),
        border = BorderStroke(1.dp, Color(0x22FFFFFF))
    ) {
        if (item == null) {
            Box(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
                Text("Movie info", color = QtoneColors.Muted, fontSize = 13.sp)
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.name,
                        color = QtoneColors.Text,
                        fontSize = 18.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(Modifier.width(10.dp))

                    item.rating?.takeIf { it.isNotBlank() }?.let {
                        Text("Rating ${it.take(3)}", color = Color(0xCCFFFFFF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    item.year?.take(4)?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.width(10.dp))
                        Text(it, color = Color(0xFFD0CED8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    item.genre?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            it,
                            color = Color(0xFFD0CED8),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    item.plot ?: "No summary available.",
                    color = QtoneColors.Text,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}



@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiveChannelListRow(
    item: MediaItem,
    selected: Boolean,
    isFavorite: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    var favoritePressStartMs by remember { mutableStateOf(0L) }
    var favoriteLongPressFired by remember { mutableStateOf(false) }

    fun isOkKey(key: Key): Boolean =
        key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter


    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                if (!isOkKey(event.key)) return@onPreviewKeyEvent false

                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (favoritePressStartMs == 0L) {
                            favoritePressStartMs = System.currentTimeMillis()
                            favoriteLongPressFired = false
                        }

                        if (!favoriteLongPressFired &&
                            System.currentTimeMillis() - favoritePressStartMs >= 1100L
                        ) {
                            favoriteLongPressFired = true
                            onLongPress()
                            true
                        } else {
                            false
                        }
                    }
                    KeyEventType.KeyUp -> {
                        val consume = favoriteLongPressFired
                        favoritePressStartMs = 0L
                        favoriteLongPressFired = false
                        consume
                    }
                    else -> false
                }
            }
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            ),
        shape = RoundedCornerShape(7.dp),
        color = when {
            selected -> Color(0xFF1A1A1F)
            focused -> Color(0xFF1F1A28)
            else -> Color.Transparent
        },
        contentColor = QtoneColors.Text
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isFavorite) {
                Text("★", color = Color(0xFFFFC857), fontSize = 16.sp, modifier = Modifier.width(18.dp))
            } else {
                Spacer(Modifier.width(18.dp))
            }

            Text(
                item.name,
                fontSize = 13.sp,
                lineHeight = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun EmbeddedLivePlayer(
    player: ExoPlayer?,
    modifier: Modifier = Modifier,
    onFullscreen: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black)
            .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(14.dp))
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyUp && it.key == Key.Menu) {
                    onFullscreen()
                    true
                } else {
                    false
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.player = player
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color(0x88000000))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text("Press OK on channel name or ☰ for fullscreen", color = QtoneColors.Text, fontSize = 12.sp)
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun FullscreenLivePlayer(
    player: ExoPlayer?,
    title: String,
    onExitFullscreen: () -> Unit
) {
    var showOsd by remember { mutableStateOf(true) }

    BackHandler { onExitFullscreen() }

    LaunchedEffect(showOsd, title) {
        if (showOsd) {
            delay(3000)
            showOsd = false
        }
    }

    fun toggleOsd() {
        showOsd = true
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent {
                when {
                    it.type == KeyEventType.KeyUp && it.key == androidx.compose.ui.input.key.Key.Menu -> {
                        onExitFullscreen()
                        true
                    }
                    it.type == KeyEventType.KeyUp &&
                        (it.key == androidx.compose.ui.input.key.Key.DirectionCenter ||
                         it.key == androidx.compose.ui.input.key.Key.Enter ||
                         it.key == androidx.compose.ui.input.key.Key.NumPadEnter) -> {
                        toggleOsd()
                        true
                    }
                    else -> false
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    keepScreenOn = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    requestFocus()
                    setOnKeyListener { _, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_MENU) {
                            onExitFullscreen()
                            true
                        } else if (
                            event.action == KeyEvent.ACTION_UP &&
                            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                             keyCode == KeyEvent.KEYCODE_ENTER ||
                             keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                        ) {
                            toggleOsd()
                            true
                        } else {
                            false
                        }
                    }
                    this.player = player
                }
            },
            update = { view ->
                view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                view.player = player
                view.requestFocus()
            },
            modifier = Modifier.fillMaxSize()
        )

        if (showOsd) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color(0x55000000))
                    .padding(22.dp)
            ) {
                Text(title, color = QtoneColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
