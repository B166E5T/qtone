package com.qtone.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.qtone.app.update.UpdateChecker
import com.qtone.app.update.UpdateInstaller
import kotlinx.coroutines.launch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem as ExoMediaItem
import com.qtone.app.model.Category
import com.qtone.app.model.Credentials
import com.qtone.app.model.MediaItem
import com.qtone.app.model.Section
import com.qtone.app.model.UiState
import com.qtone.app.player.PlayerActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.qtone.app.ui.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var launchingPlayerActivity = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by vm.state.collectAsState()
            var detailItem by remember { mutableStateOf<MediaItem?>(null) }
            var initialSimilarFocusId by remember { mutableStateOf<String?>(null) }
            var forceFirstSimilarFocusForItemId by remember { mutableStateOf<String?>(null) }
            var posterGridRestoreRequest by remember { mutableStateOf(0) }
            // Last-clicked tile IDs survive AppShell unmount/remount when the
            // user opens a detail screen and presses Back. Hoisted to the
            // outer setContent scope (above the `detailItem?.let { ... } ?: AppShell`
            // branch) because AppShell itself gets unmounted while detail is
            // shown — its internal `remember` state resets when it remounts,
            // which was the cause of "Back lands focus on Live TV" instead
            // of on the clicked card.
            //
            // Per-section storage (movies vs series) so each section can
            // restore its own last-clicked tile independently.
            var lastMovieClickedId by remember { mutableStateOf<String?>(null) }
            var lastSeriesClickedId by remember { mutableStateOf<String?>(null) }
            // Saved viewport scroll state for the Movies and Series grids.
            // Captured at the moment the user clicks a tile, restored when the
            // grid is remounted (after the user presses Back from detail).
            // Format: Pair(firstVisibleItemIndex, firstVisibleItemScrollOffset).
            val moviesSavedScroll = remember { mutableStateOf<Pair<Int, Int>?>(null) }
            val seriesSavedScroll = remember { mutableStateOf<Pair<Int, Int>?>(null) }

            // Quick-info popup state.
            //
            // quickInfoFor: the MediaItem currently shown in the popup, or
            //   null when the popup is dismissed. Setting this to a non-null
            //   value opens the popup; setting to null closes it.
            // focusedCardBounds: where the currently-focused grid card sits
            //   on screen, used to anchor the popup. Updated whenever a tile
            //   gains focus via the onCardBoundsCaptured callback plumbed
            //   through MovieMediaGrid.
            //
            // Both states are hoisted to setContent {} so they survive
            // PosterLayout / AppShell remounts (similar reasoning as
            // lastMovieClickedId — though for the popup it's mainly so
            // dismissal and focus events can both reach it from any layer).
            var quickInfoFor by remember { mutableStateOf<MediaItem?>(null) }
            var focusedCardBounds by remember { mutableStateOf<FocusedCardBounds?>(null) }

            // Self-update state. checkResult is set once on app start; the
            // dialog renders only while it holds an UpdateAvailable instance.
            // The user can dismiss to hide the dialog (non-mandatory updates)
            // or accept it to download + install.
            var updateCheck by remember { mutableStateOf<UpdateChecker.Result?>(null) }
            var updateDismissed by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                // Single check on app start. Runs in parallel with content
                // loading so it doesn't delay the user's first interaction.
                // Failures are silent — see UpdateChecker for details.
                updateCheck = UpdateChecker.check()
            }

            // Clear the last-clicked tile state when the user navigates AWAY
            // from Movies/Series to a different section (Live TV, Search,
            // Settings, etc.). Without this, returning to Movies via the top
            // bar would restore focus to the previously-clicked card —
            // because `posterGridRestoreRequest` retains its non-zero value
            // from a prior back-from-detail event, and the grid's
            // LaunchedEffect(restoreFocusRequest) fires whenever the key is
            // non-zero on initial composition.
            //
            // The back-from-detail path is NOT affected by this: that path
            // doesn't change `state.section` (the detail screen renders on
            // top of the Movies/Series section). So the focus-restore on
            // Back from a movie detail still works.
            LaunchedEffect(state.section) {
                if (state.section != Section.Movies) {
                    lastMovieClickedId = null
                    moviesSavedScroll.value = null
                }
                if (state.section != Section.Series) {
                    lastSeriesClickedId = null
                    seriesSavedScroll.value = null
                }
            }
            val similarMovieIdSnapshots = remember { mutableStateMapOf<String, List<String>>() }
            val detailBackStack = remember { mutableStateListOf<MediaItem>() }
            detailItem?.let { selected ->
                BackHandler {
                    if (detailBackStack.isNotEmpty()) {
                        val leaving = selected
                        val previous = detailBackStack.removeAt(detailBackStack.lastIndex)
                        detailItem = previous
                        forceFirstSimilarFocusForItemId = null
                        initialSimilarFocusId = leaving.id
                    } else {
                        detailItem = null
                        forceFirstSimilarFocusForItemId = null
                        initialSimilarFocusId = null
                        similarMovieIdSnapshots.clear()
                        posterGridRestoreRequest += 1
                    }
                }
                LaunchedEffect(selected.id, selected.streamType) {
                    if (selected.streamType == "series") {
                        vm.loadSeriesEpisodes(selected.id)
                    }
                    // ALWAYS fetch from TMDB when the user opens a detail screen.
                    //
                    // The fetch was previously gated by `latest.plot.isNullOrBlank()`,
                    // which meant TMDB never ran for items where the provider
                    // already supplied a plot. With the TMDB-first merge priority
                    // (see mergeMovieFallback / mergeSeriesFallback in MainViewModel)
                    // we want every detail open to refresh the metadata against
                    // TMDB so the detail view shows authoritative TMDB data
                    // rather than whatever stale or partial info the provider
                    // happened to ship.
                    //
                    // fetchMovieMetadata is itself idempotent and cheap — it
                    // checks the persistent disk cache first, only goes to the
                    // network if the cache is missing for the current language,
                    // and updates state in-place. So repeating it on every
                    // detail open is safe and fast.
                    val latest = state.movies.firstOrNull { it.id == selected.id }
                        ?: state.series.firstOrNull { it.id == selected.id }
                        ?: selected
                    if (selected.streamType == "movie") {
                        vm.fetchMovieMetadata(latest, fetchSimilar = true)
                    } else if (selected.streamType == "series") {
                        vm.fetchSeriesMetadata(latest)
                    }
                }

                // TMDB-backed similar movies.
                //
                // Movies only. Kicks off the fetch on detail-open and reads
                // the result reactively from the ViewModel. Series detail
                // screens skip this entirely.
                //
                // fetchSimilarMoviesFor is idempotent — repeated calls for
                // the same id (e.g. on recomposition) return early if a
                // result is already cached or a request is in flight. No
                // duplicate network calls.
                LaunchedEffect(selected.id, selected.streamType, state.metadataLanguage) {
                    if (selected.streamType == "movie") {
                        vm.fetchSimilarMoviesFor(selected)
                    }
                }

                // Read the TMDB-cross-referenced result.
                //
                // The result is cached in the ViewModel for the lifetime of
                // the session (TMDB recommendations don't change between
                // detail opens), so card content is stable across re-opens
                // of the same detail screen — no need for the
                // similarMovieIdSnapshots mechanism that the local algorithm
                // used to keep its output stable (that snapshot map remains
                // declared elsewhere in this file but is no longer read from
                // this path; removing its declaration / cleanup hooks would
                // touch unrelated state-change handlers, so it stays put as
                // harmless bookkeeping).
                //
                // During the brief in-flight window after first opening a
                // movie detail (typically 1-2 seconds), the cache returns
                // null and stableSimilarMovies is empty. The "Similar Movies"
                // header inside MovieDetailScreen is gated on the list being
                // non-empty so the user doesn't see a stranded header.
                //
                // showSimilar stays a pure streamType check below — DO NOT
                // gate it on the list being non-empty. showSimilar is also
                // the discriminator between movie-mode and series-mode
                // rendering inside MovieDetailScreen; gating it on the list
                // breaks movies during the in-flight window.
                val similarMoviesFromVm by vm.similarMoviesByItemId.collectAsState()
                val watchedEpisodes by vm.watchedEpisodeIds.collectAsState()
                val stableSimilarMovies = if (selected.streamType == "movie") {
                    similarMoviesFromVm[selected.id].orEmpty()
                } else {
                    emptyList()
                }

                // Live, reactive lookup of the currently-displayed item.
                //
                // `selected` is a captured snapshot taken at click time — it
                // does NOT update when fetchMovieMetadata writes back to
                // state.movies. That captured snapshot was the source of the
                // "click → No Summary available" bug: TMDB data arrived in
                // state.movies but the detail screen kept rendering the
                // pre-fetch `selected` plot/name fields.
                //
                // By deriving `liveItem` from state on every recomposition we
                // get the merged TMDB-priority data the moment applyMovieMetadata
                // updates state. Fallback to `selected` covers transient gaps
                // (e.g. the item not yet present in state for whatever reason).
                val liveItem = state.movies.firstOrNull { it.id == selected.id }
                    ?: state.series.firstOrNull { it.id == selected.id }
                    ?: selected

                // Detail screen renders immediately on click — no fade-in.
                //
                // We experimented with a 220ms fadeIn (Animatable + graphicsLayer
                // alpha) but the visual result was poor: the partially-transparent
                // detail screen showed the grid bleeding through, and the
                // animation made the title/plot text look like it was "loading"
                // when in fact the content was already there. Snapping into
                // place is the right call for this transition.
                MovieDetailScreen(
                    // liveItem refreshes from state on every recompose; this
                    // is what makes the TMDB plot/title appear without
                    // requiring the user to back out and reopen the card.
                    item = liveItem,
                    showSimilar = selected.streamType == "movie",
                    similarItems = stableSimilarMovies,
                    movieFavorites = state.movieFavorites,
                    seriesEpisodes = if (selected.streamType == "series") state.seriesEpisodes[selected.id].orEmpty() else emptyList(),
                    isLoadingEpisodes = selected.streamType == "series" && state.seriesEpisodesLoading.contains(selected.id),
                    isPlotLoading = state.plotFetchingFor.contains(selected.id),
                    watchedEpisodeIds = watchedEpisodes,
                    initialSimilarFocusId = initialSimilarFocusId,
                    forceFirstSimilarFocusForItemId = forceFirstSimilarFocusForItemId,
                    onToggleSimilarFavorite = { movie -> vm.toggleMovieFavorite(movie) },
                    onSimilarFocused = { movie -> vm.fetchMovieMetadata(movie) },
                    onSimilarOpen = { next ->
                        // Push the live (latest) version onto the back
                        // stack — when the user comes back via Back, we
                        // want to see the merged data, not the stale
                        // pre-fetch snapshot.
                        detailBackStack.add(liveItem)
                        initialSimilarFocusId = null
                        forceFirstSimilarFocusForItemId = next.id
                        detailItem = next
                    },
                    onEpisodeOpen = { episode ->
                        openItem(
                            MediaItem(
                                id = episode.id,
                                name = episode.title,
                                streamType = "series_episode",
                                categoryId = episode.seriesId,
                                poster = episode.poster ?: liveItem.poster,
                                plot = episode.plot,
                                streamUrl = episode.streamUrl
                            )
                        )
                    },
                    onPlay = { openItem(liveItem) }
                )
            } ?: Box(Modifier.fillMaxSize()) {
                AppBackground()
                when {
                    state.loading -> LoadingScreen()
                    !state.loggedIn -> LoginScreen(
                        state = state,
                        onLogin = { server, username, password -> vm.login(server, username, password) }
                    )
                    state.updating -> ContentUpdateScreen(state)
                    state.error != null && state.live.isEmpty() && state.movies.isEmpty() && state.series.isEmpty() ->
                        ErrorScreen(state.error ?: "Error") { vm.manualUpdate() }
                    else -> AppShell(
                        state = state,
                        filteredItems = when (state.section) {
                            Section.Live -> vm.filteredLive(state)
                            Section.Movies -> vm.filteredMovies(state)
                            Section.Series -> vm.filteredSeries(state)
                            else -> emptyList()
                        },
                        onSection = { vm.setSection(it) },
                        onCategory = { vm.setCategory(state.section, it) },
                        onFocused = { vm.setFocused(it) },
                        onOpen = { item ->
                            if (item.streamType == "movie" || item.streamType == "series") {
                                detailBackStack.clear()
                                similarMovieIdSnapshots.clear()
                                initialSimilarFocusId = null
                                forceFirstSimilarFocusForItemId = null
                                // Dismiss the quick-info popup if it's open.
                                // The user pressed OK on a card while the
                                // popup was showing — the natural expectation
                                // is "open the detail screen for that card."
                                // Without this reset, the popup would remain
                                // visible underneath the detail screen and
                                // still be there when the user pressed Back.
                                quickInfoFor = null
                                // Kick off the TMDB fetch BEFORE setting
                                // detailItem. The cache-hit branch is
                                // synchronous: applyMovieMetadata updates
                                // state.movies in place, so by the time the
                                // detail screen mounts (next composition) the
                                // plot/title are already in state and liveItem
                                // resolves to the merged data on first render.
                                //
                                // For cache-miss the network fetch is still
                                // async, but plotFetchingFor IS set synchronously,
                                // so the detail screen at least renders "Loading
                                // plot..." instead of "No summary available."
                                // while the network call completes.
                                //
                                // This eliminates the click-back-click pattern
                                // the user was hitting on first opens.
                                if (item.streamType == "movie") {
                                    vm.fetchMovieMetadata(item, fetchSimilar = true)
                                } else {
                                    vm.fetchSeriesMetadata(item)
                                }
                                detailItem = item
                            } else {
                                openItem(item)
                            }
                        },
                        onRefresh = { vm.manualUpdate() },
                        onToggleLiveFavorite = { vm.toggleLiveFavorite(it) },
                        onToggleMovieFavorite = { vm.toggleMovieFavorite(it) },
                        onToggleSeriesFavorite = { vm.toggleSeriesFavorite(it) },
                        onClearContinueWatching = { item -> vm.clearContinueWatchingItem(item) },
                        posterGridRestoreRequest = posterGridRestoreRequest,
                        moviesSavedScroll = moviesSavedScroll,
                        seriesSavedScroll = seriesSavedScroll,
                        // Hoisted last-clicked-tile state (per section) is passed
                        // both as the current value (read by the grid for focus
                        // restore) and as a setter the click handler uses to
                        // record the just-clicked item. See the variable
                        // declarations above for rationale.
                        lastMovieClickedId = lastMovieClickedId,
                        lastSeriesClickedId = lastSeriesClickedId,
                        onMovieClicked = { id -> lastMovieClickedId = id },
                        onSeriesClicked = { id -> lastSeriesClickedId = id },
                        searchItems = { section, query -> vm.searchItems(section, query) },
                        onLiveSearchSubmit = { query -> vm.submitLiveSearch(query) },
                        onMovieSearchSubmit = { query -> vm.submitMovieSearch(query) },
                        onSeriesSearchSubmit = { query -> vm.submitSeriesSearch(query) },
                        onMetadataLanguage = { language -> vm.setMetadataLanguage(language) },
                        onLogout = { vm.logout() },
                        onChangeUrl = { url -> vm.changeServerUrl(url) },
                        onCheckForUpdates = {
                            lifecycleScope.launch {
                                updateDismissed = false
                                updateCheck = UpdateChecker.check()
                                // Show a toast with the result so we can see
                                // what's happening (especially on errors).
                                val msg = when (val r = updateCheck) {
                                    is UpdateChecker.Result.UpToDate ->
                                        "You're running the latest version (${com.qtone.app.BuildConfig.VERSION_CODE})"
                                    is UpdateChecker.Result.UpdateAvailable ->
                                        "Update available: ${r.manifest.versionName}"
                                    is UpdateChecker.Result.Error ->
                                        "Update check failed: ${r.reason}"
                                    else -> "Unknown result"
                                }
                                android.widget.Toast.makeText(
                                    this@MainActivity, msg, android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        // Quick-info popup state plumbing. The popup itself
                        // is rendered below (outside AppShell) so it can
                        // overlay the grid. The setter additionally kicks off
                        // a TMDB metadata fetch so the popup populates with
                        // plot/genre/cast when those aren't already cached.
                        quickInfoFor = quickInfoFor,
                        onQuickInfoForChange = { item ->
                            quickInfoFor = item
                            if (item != null) {
                                // Same prefetch pattern used by the click
                                // handler — synchronous cache-hit path fills
                                // state.movies before the popup renders, so
                                // it shows TMDB plot immediately.
                                if (item.streamType == "movie") {
                                    vm.fetchMovieMetadata(item)
                                } else if (item.streamType == "series") {
                                    vm.fetchSeriesMetadata(item)
                                }
                            }
                        },
                        onCardBoundsCaptured = { bounds ->
                            focusedCardBounds = bounds
                        }
                    )
                }
            }

            // Quick-info popup overlay.
            //
            // Renders above AppShell but below the update dialog. The popup
            // composable handles its own scrim, layout, and animations.
            //
            // liveItem pattern: read the latest version of the popup's item
            // from state.movies / state.series on every recomposition. This
            // makes the panel reactive to TMDB data arriving — provider
            // plot shows immediately, then the panel re-renders with the
            // richer TMDB plot/genre/cast a moment later.
            //
            // Fixed right-side layout. The previous floating placement was
            // unpredictable and sometimes overlapped the focused card; the
            // new design uses a consistent right-edge layout that includes
            // the card's poster + info in one self-contained panel. See
            // QuickInfoPopup.kt for the full design rationale.
            //
            // Bounds tracking (focusedCardBounds) is still plumbed through
            // from the grid but is no longer consumed here — kept in place
            // in case we want card-relative effects later.
            quickInfoFor?.let { popupItem ->
                val livePopupItem = state.movies.firstOrNull { it.id == popupItem.id }
                    ?: state.series.firstOrNull { it.id == popupItem.id }
                    ?: popupItem
                val config = androidx.compose.ui.platform.LocalConfiguration.current
                val viewportHeight = config.screenHeightDp.dp
                val viewportWidth = config.screenWidthDp.dp
                QuickInfoPopup(
                    item = livePopupItem,
                    viewportWidthDp = viewportWidth,
                    viewportHeightDp = viewportHeight
                )
            }

            // Self-update dialog. Renders on top of all other UI.
            //
            // Visible when:
            //   - UpdateChecker reported an UpdateAvailable, AND
            //   - the user has not dismissed it this session, OR the update
            //     is mandatory (no dismiss button in that case).
            //
            // Dismissal is session-scoped — there is intentionally no
            // "Don't ask again" option. On the next app launch the check
            // runs again, and if the user still hasn't updated they will
            // be prompted again. Mandatory updates also disable the Back
            // button on the dialog (handled inside UpdateAvailableDialog).
            (updateCheck as? UpdateChecker.Result.UpdateAvailable)?.let { available ->
                if (!updateDismissed || available.isMandatory) {
                    UpdateAvailableDialog(
                        manifest = available.manifest,
                        isMandatory = available.isMandatory,
                        onDismiss = {
                            if (!available.isMandatory) updateDismissed = true
                        },
                        onAcceptUpdate = {
                            // The install handoff is fire-and-forget — Android
                            // takes the user to the system installer; when
                            // they accept, the app process is killed and
                            // replaced. If they cancel, control returns here
                            // with the dialog still visible.
                            lifecycleScope.launch {
                                UpdateInstaller.downloadAndInstall(
                                    this@MainActivity,
                                    available.manifest.apkUrl
                                )
                            }
                        }
                    )
                }
            }
        }
    }


    private fun similarMoviesFor(selected: MediaItem, allMovies: List<MediaItem>): List<MediaItem> {
        val selectedGenres = selected.genre
            ?.split(",", "·", "/", "|")
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        return allMovies
            .filter { it.id != selected.id && it.streamType == "movie" }
            .map { movie ->
                var score = 0
                if (movie.categoryId == selected.categoryId) score += 20

                val movieGenres = movie.genre
                    ?.split(",", "·", "/", "|")
                    ?.map { it.trim().lowercase() }
                    ?.filter { it.isNotBlank() }
                    ?: emptyList()

                score += movieGenres.count { it in selectedGenres } * 35

                val selectedYear = selected.year?.take(4)?.toIntOrNull()
                val movieYear = movie.year?.take(4)?.toIntOrNull()
                if (selectedYear != null && movieYear != null) {
                    val diff = kotlin.math.abs(selectedYear - movieYear)
                    if (diff <= 2) score += 10
                    else if (diff <= 5) score += 5
                }

                movie to score
            }
            .sortedWith(compareByDescending<Pair<MediaItem, Int>> { it.second }.thenBy { it.first.name })
            .map { it.first }
            .take(12)
    }

    override fun onResume() {
        super.onResume()
        launchingPlayerActivity = false
        vm.refreshMovieContinueWatching()
        vm.refreshWatchedEpisodes()
    }

    override fun onStop() {
        super.onStop()

        // Reset the player-launch flag if it was set. We do NOT finish() here.
        // Previously this called finish() to "treat background like exit", but
        // that also triggered when the Fire TV screensaver activates, which
        // made the app appear to close every time the screensaver came on.
        // Letting Android manage the lifecycle normally means the screensaver
        // / display-off / temporary backgrounding all simply pause the
        // activity, and the user returns to where they left off. The Home
        // button still works correctly — Android backgrounds the app and
        // may reclaim it later if needed.
        if (launchingPlayerActivity || isChangingConfigurations) {
            launchingPlayerActivity = false
        }
    }

    private fun openItem(item: MediaItem) {
        if (item.streamUrl != null) {
            launchingPlayerActivity = true
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra("title", item.name)
                putExtra("url", item.streamUrl)
                putExtra("item_id", item.id)
                putExtra("stream_type", item.streamType)
                putExtra("series_id", if (item.streamType == "series_episode") item.categoryId else "")
                putExtra("rating", item.rating.orEmpty())
                putExtra("genre", item.genre.orEmpty())
                putExtra("year", item.year.orEmpty())
                putExtra("plot", item.plot.orEmpty())
            })
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color(0xFFFFFFFF))
            Spacer(Modifier.height(14.dp))
            Text("Loading your content…", color = QtoneColors.Muted, fontSize = 17.sp)
        }
    }
}

@Composable
private fun AppShell(
    state: UiState,
    filteredItems: List<MediaItem>,
    onSection: (Section) -> Unit,
    onCategory: (String) -> Unit,
    onFocused: (MediaItem) -> Unit,
    onOpen: (MediaItem) -> Unit,
    onRefresh: () -> Unit,
    onToggleLiveFavorite: (MediaItem) -> Unit,
    onToggleMovieFavorite: (MediaItem) -> Unit,
    onToggleSeriesFavorite: (MediaItem) -> Unit,
    onClearContinueWatching: (MediaItem) -> Unit,
    posterGridRestoreRequest: Int = 0,
    moviesSavedScroll: androidx.compose.runtime.MutableState<Pair<Int, Int>?>,
    seriesSavedScroll: androidx.compose.runtime.MutableState<Pair<Int, Int>?>,
    // Hoisted last-clicked tile IDs (read for focus-restore, written on click).
    // See declarations in setContent {} for why these can't live inside AppShell.
    lastMovieClickedId: String?,
    lastSeriesClickedId: String?,
    onMovieClicked: (String) -> Unit,
    onSeriesClicked: (String) -> Unit,
    searchItems: (Section, String) -> List<MediaItem>,
    onLiveSearchSubmit: (String) -> Unit,
    onMovieSearchSubmit: (String) -> Unit,
    onSeriesSearchSubmit: (String) -> Unit,
    onMetadataLanguage: (String) -> Unit,
    onLogout: () -> Unit,
    onChangeUrl: (String) -> Unit,
    onCheckForUpdates: () -> Unit,
    // Quick-info popup integration. Plumbed straight through to PosterLayout.
    // See QuickInfoPopup.kt for behavior contract.
    quickInfoFor: MediaItem?,
    onQuickInfoForChange: (MediaItem?) -> Unit,
    onCardBoundsCaptured: (FocusedCardBounds?) -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    LaunchedEffect(state.loggedIn, state.updating, state.section) {
        if (state.loggedIn && !state.updating && state.section != Section.Search) {
            keyboard?.hide()
            // focusManager.clearFocus(force = true)
        }
    }

    fun dismissKeyboardOnly() {
        keyboard?.hide()
    }

    var liveFullscreenActive by remember { mutableStateOf(false) }
    var currentLivePlaying by remember { mutableStateOf<MediaItem?>(null) }
    var menuFullscreenRequest by remember { mutableStateOf(0) }
    var searchBaseSection by remember { mutableStateOf(Section.Live) }
    var showExitDialog by remember { mutableStateOf(false) }

    // Local focused-poster state. Tracks which movie/series card is focused
    // WITHOUT going through the ViewModel.
    //
    // Previously every focus change went VM.setFocused → state.focusedItem
    // update → cascade recomposition of AppBackground, PosterLayout, and
    // every visible grid tile. That cascade was the source of the "freezing"
    // feel when holding D-pad down on a long list.
    //
    // Now there are two pieces of focus-tracking state, each updated at a
    // different cadence:
    //
    //   posterFocusedItem — updates on EVERY D-pad focus change. Used only
    //     for the Menu-long-press handler (Continue Watching clear). The
    //     handler reads it inside an onPreviewKeyEvent lambda, which only
    //     runs on actual key events — NOT during normal grid recomposition.
    //     So updating this state does not trigger cascade recomposition of
    //     the grid items.
    //
    //   lastMovieClickedId / lastSeriesClickedId — updated ONLY when the
    //     user clicks/opens a tile. These are hoisted to the outer
    //     setContent {} scope so they survive AppShell unmount while the
    //     detail screen is shown. Passed down to MovieMediaGrid as
    //     focusedItemId for the Back-from-detail focus-restore path. Stable
    //     during browsing → no per-focus modifier churn on grid items.
    //
    // posterFocusedItem resets to null when section changes so a stale focused
    // item from Movies doesn't leak into Series.
    //
    // CRITICAL: onPosterFocused MUST share the same remember-key as
    // posterFocusedItem. If `onPosterFocused` is `remember { ... }` with no
    // key, its closure captures the FIRST posterFocusedItem MutableState
    // ever created. When state.section changes, `remember(state.section)`
    // creates a FRESH MutableState for posterFocusedItem, but the lambda
    // still writes to the original. The new one stays null forever, and
    // every consumer (the Menu keyhandler, the quick-info popup trigger,
    // etc.) sees null even though tiles are firing onPosterFocused.
    //
    // This was the cause of the "popup doesn't work on first navigation
    // until you go into detail and back" bug. Detail-screen open unmounts
    // AppShell entirely; coming back from detail recreates BOTH state and
    // lambda together, accidentally fixing the binding until the next
    // section switch, which broke it again.
    var posterFocusedItem by remember(state.section) { mutableStateOf<MediaItem?>(null) }
    // The last-clicked-id state is now hoisted to the outer setContent {}
    // scope (see lastMovieClickedId / lastSeriesClickedId parameters).
    val onPosterFocused: (MediaItem) -> Unit = remember(state.section) {
        { item -> posterFocusedItem = item }
    }

    BackHandler {
        if (showExitDialog) {
            showExitDialog = false
        } else if (state.section == Section.Settings) {
            onSection(Section.Live)
        } else {
            showExitDialog = true
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = {
                Text(
                    "Exit?",
                    color = QtoneColors.Text
                )
            },
            text = {
                Text(
                    "Do you really want to exit the app?",
                    color = QtoneColors.Muted
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitDialog = false
                        (context as? android.app.Activity)?.finish()
                    }
                ) {
                    Text("Yes")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showExitDialog = false
                    }
                ) {
                    Text("No")
                }
            },
            containerColor = Color(0xEE101015)
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent {
                if (state.section != Section.Search) {
                    dismissKeyboardOnly()
                }
                if (it.type == KeyEventType.KeyUp &&
                    it.key == Key.Menu &&
                    state.section == Section.Live &&
                    currentLivePlaying != null
                ) {
                    menuFullscreenRequest += 1
                    true
                } else {
                    false
                }
            }
    ) {
        if (!liveFullscreenActive) {
            TopNav(
                selected = state.section,
                onSection = { target ->
                    dismissKeyboardOnly()
                    if (target == Section.Search) {
                        searchBaseSection = when (state.section) {
                            Section.Live, Section.Movies, Section.Series -> state.section
                            else -> searchBaseSection
                        }
                    }
                    onSection(target)
                },
                onUpdate = onRefresh
            )
        }
        when (state.section) {
            Section.Live -> LiveLayout(
                state = state,
                items = filteredItems,
                onCategory = { dismissKeyboardOnly(); onCategory(it) },
                onFocused = onFocused,
                onOpen = { dismissKeyboardOnly(); onOpen(it) },
                onToggleFavorite = onToggleLiveFavorite,
                onFullscreenStateChange = { liveFullscreenActive = it },
                onPlayingChanged = { currentLivePlaying = it },
                menuFullscreenRequest = menuFullscreenRequest
            )
            Section.Movies -> PosterLayout(
                title = "Movies",
                categories = state.movieCategories,
                selectedCategoryId = state.activeMovieCategoryId,
                items = filteredItems,
                // focusedItem is used by PosterLayout ONLY for the Menu-long-
                // press handler (reading inside an onPreviewKeyEvent lambda).
                // Updates on every focus change but doesn't propagate to grid.
                focusedItem = posterFocusedItem,
                // gridFocusedItemId is passed down to MovieMediaGrid for the
                // Back-from-detail focus-restore path. Updates ONLY at click
                // time so grid items don't churn during browsing.
                gridFocusedItemId = lastMovieClickedId,
                favoriteIds = state.movieFavorites,
                onToggleFavorite = onToggleMovieFavorite,
                onClearContinueWatching = onClearContinueWatching,
                restoreFocusRequest = posterGridRestoreRequest,
                savedScroll = moviesSavedScroll,
                onCategory = { dismissKeyboardOnly(); onCategory(it) },
                onFocused = onPosterFocused,
                onOpen = { item ->
                    dismissKeyboardOnly()
                    // Capture which item the user is opening — this is what
                    // we'll restore focus to when they press Back. The setter
                    // is hoisted state so this survives the AppShell unmount
                    // that happens while the detail screen is shown.
                    onMovieClicked(item.id)
                    // Sync to VM state at click time so favorites/language
                    // change still works on the clicked item.
                    onFocused(item)
                    onOpen(item)
                },
                quickInfoFor = quickInfoFor,
                onQuickInfoForChange = onQuickInfoForChange,
                onCardBoundsCaptured = onCardBoundsCaptured
            )
            Section.Series -> PosterLayout(
                title = "Series",
                categories = state.seriesCategories,
                selectedCategoryId = state.activeSeriesCategoryId,
                items = filteredItems,
                focusedItem = posterFocusedItem,
                gridFocusedItemId = lastSeriesClickedId,
                favoriteIds = state.seriesFavorites,
                onToggleFavorite = onToggleSeriesFavorite,
                onClearContinueWatching = onClearContinueWatching,
                restoreFocusRequest = posterGridRestoreRequest,
                savedScroll = seriesSavedScroll,
                onCategory = { dismissKeyboardOnly(); onCategory(it) },
                onFocused = onPosterFocused,
                onOpen = { item ->
                    dismissKeyboardOnly()
                    onSeriesClicked(item.id)
                    onFocused(item)
                    onOpen(item)
                },
                quickInfoFor = quickInfoFor,
                onQuickInfoForChange = onQuickInfoForChange,
                onCardBoundsCaptured = onCardBoundsCaptured
            )
            Section.Search -> SearchScreen(
                searchSection = searchBaseSection,
                searchItems = searchItems,
                liveFavorites = state.liveFavorites,
                onToggleLiveFavorite = onToggleLiveFavorite,
                onLiveSearchSubmit = { query ->
                    onLiveSearchSubmit(query)
                    onSection(Section.Live)
                },
                onMovieSearchSubmit = { query ->
                    onMovieSearchSubmit(query)
                    onSection(Section.Movies)
                },
                onSeriesSearchSubmit = { query ->
                    onSeriesSearchSubmit(query)
                    onSection(Section.Series)
                },
                onOpen = onOpen
            )
            Section.Settings -> SettingsScreen(
                metadataLanguage = state.metadataLanguage,
                credentials = state.credentials,
                accountExpirationMs = state.accountExpirationMs,
                error = state.error,
                onMetadataLanguage = onMetadataLanguage,
                onLogout = onLogout,
                onChangeUrl = onChangeUrl,
                onCheckForUpdates = onCheckForUpdates
            )
        }
    }
}

@Composable
private fun TopNav(selected: Section, onSection: (Section) -> Unit, onUpdate: () -> Unit) {
    // Nextv-style top nav (matches frame f_025/f_038 of the reference video):
    //
    //   - No logo, no brand name; just the nav buttons floating on the
    //     translucent dark bar.
    //   - Items are evenly spread across the full width using weighted
    //     Spacers between them.
    //   - Three visual states per button:
    //       FOCUSED  → white text on a thin white outline (transparent fill).
    //                  This is the D-pad cursor.
    //       SELECTED → subtle gray pill fill (~25% white), muted text.
    //                  This is the "you are here" indicator.
    //       NEITHER  → plain bright white text, no decoration.
    //
    // Hidden in this nav row: the Update button. Same logic as before
    // (acts as a one-shot trigger, never "selected"), placed near the
    // right side after Search but before Settings.
    Row(
        Modifier
            .fillMaxWidth()
            .height(62.dp)
            .background(Color(0xE6050508))
            .onPreviewKeyEvent { event ->
                event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp
            }
            .padding(horizontal = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Weighted spacers between each pair of buttons make the buttons
        // distribute evenly across the bar regardless of label length.
        // Pattern: button [weight] button [weight] button [weight] button ...
        CompactTopButton("Live TV", selected == Section.Live) { onSection(Section.Live) }
        Spacer(Modifier.weight(1f))
        CompactTopButton("Movies", selected == Section.Movies) { onSection(Section.Movies) }
        Spacer(Modifier.weight(1f))
        CompactTopButton("Series", selected == Section.Series) { onSection(Section.Series) }
        Spacer(Modifier.weight(1.4f))
        CompactTopButton("Search", selected == Section.Search) { onSection(Section.Search) }
        Spacer(Modifier.weight(1f))
        CompactTopButton("Update", false) { onUpdate() }
        Spacer(Modifier.weight(1f))
        CompactTopButton("Settings", selected == Section.Settings) { onSection(Section.Settings) }
    }
}

@Composable
private fun CompactTopButton(text: String, selected: Boolean, onClick: () -> Unit) {
    // See TopNav() for the three-state behavior contract.
    //
    // Visual states (matches category sidebar styling for consistency):
    //   SELECTED      → SOLID WHITE pill, near-black text. Same visual as
    //                   a selected (clicked) category row in the sidebar.
    //   FOCUSED only  → white outline border, transparent inside, white text.
    //                   D-pad cursor; no gray fill of any kind.
    //   BOTH          → white pill (selected wins visually since it has more
    //                   coverage); the outline still gets drawn but on top
    //                   of the white pill it's invisible against the same color.
    //   NEITHER       → plain medium-weight muted-white text, no decoration.
    //
    // Implementation note: we paint pill + border ourselves via animated
    // Modifier.background + Modifier.border. We deliberately do NOT use
    // Material3 Surface's container color or its built-in focused state
    // layer — Surface's state layer renders a translucent gray overlay
    // when focused, which was the "gray inside the outline" the user
    // reported. By providing transparent container color AND wrapping the
    // Surface's content with our own painting, we get full control over
    // the visual.
    var focused by remember { mutableStateOf(false) }

    val pillColor by animateColorAsState(
        targetValue = if (selected) Color.White else Color.Transparent,
        animationSpec = tween(durationMillis = 90),
        label = "topNavPillColor"
    )
    val borderColor by animateColorAsState(
        // When already on the white pill (selected), the border would be
        // invisible against the pill color — but draw it anyway so the
        // transition between selected/non-selected feels uniform.
        targetValue = if (focused) Color(0xFFFFFFFF) else Color.Transparent,
        animationSpec = tween(durationMillis = 90),
        label = "topNavBorderColor"
    )
    val textColor by animateColorAsState(
        targetValue = when {
            selected -> Color(0xFF0A0A0E)          // near-black text on white pill
            focused -> Color.White                  // bright white inside the outline
            else -> Color(0xCCFFFFFF)
        },
        animationSpec = tween(durationMillis = 90),
        label = "topNavTextColor"
    )

    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = Modifier
            .height(42.dp)
            .onFocusChanged { focused = it.isFocused },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
        color = Color.Transparent,            // Surface stays transparent
        contentColor = QtoneColors.Text,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = null
    ) {
        Box(
            Modifier
                .background(pillColor, androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
                .border(1.5.dp, borderColor, androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
                .padding(horizontal = 22.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text,
                color = textColor,
                fontSize = 15.sp,
                fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier.padding(vertical = 9.dp)
            )
        }
    }
}

@Composable
private fun PosterLayout(
    title: String,
    categories: List<Category>,
    selectedCategoryId: String,
    items: List<MediaItem>,
    // Used only by the Menu-long-press handler (read inside an onPreviewKeyEvent
    // lambda which runs on actual key events, not during normal browsing).
    focusedItem: MediaItem?,
    // Passed to MovieMediaGrid for the Back-from-detail focus-restore path.
    // Stable during browsing — only updates at click time — so grid items
    // don't recompose on every D-pad press.
    gridFocusedItemId: String? = null,
    favoriteIds: Set<String> = emptySet(),
    onToggleFavorite: (MediaItem) -> Unit = {},
    onClearContinueWatching: (MediaItem) -> Unit = {},
    restoreFocusRequest: Int = 0,
    savedScroll: androidx.compose.runtime.MutableState<Pair<Int, Int>?>? = null,
    onCategory: (String) -> Unit,
    onFocused: (MediaItem) -> Unit,
    onOpen: (MediaItem) -> Unit,
    // Quick-info popup integration. The Menu key on a focused card opens
    // a floating info panel anchored next to the card. Three things
    // plumbed in/out:
    //   - quickInfoFor: when non-null, the popup is currently visible for
    //     this item. The popup's render is done by the outer setContent {}
    //     so it can overlay both PosterLayout AND the detail screen if
    //     needed. PosterLayout only manages OPENING and DISMISSING.
    //   - onQuickInfoForChange: setter. The keyhandler in PosterLayout
    //     toggles via this on Menu tap; arrow-key dismiss also routes here.
    //   - onCardBoundsCaptured: forwarded to MovieMediaGrid so each tile
    //     can report its window-space bounds when it gains focus.
    quickInfoFor: MediaItem? = null,
    onQuickInfoForChange: (MediaItem?) -> Unit = {},
    onCardBoundsCaptured: (FocusedCardBounds?) -> Unit = {}
) {
    var menuPressStartMs by remember { mutableStateOf(0L) }
    var menuLongPressFired by remember { mutableStateOf(false) }

    // ── Layout ──────────────────────────────────────────────────────────
    // Sidebar is always visible (matches the current Nextv-style layout we
    // landed on after testing). Grid is fixed at 5 columns. No slide-off,
    // no sidebar-hide state, no programmatic focus transfer between
    // sidebar and grid — Compose's default D-pad focus traversal handles
    // LEFT/RIGHT navigation between the sidebar and the first/last column
    // of the grid for free.
    //
    // The grid stays mounted at the same column count throughout, so there
    // is zero re-layout cost on category changes. Only `items` changes,
    // which is the cheapest possible update path.
    val columns = 5

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                val focused = focusedItem

                // ── Quick-info popup dismissal via arrow keys ─────────
                // When the popup is open and the user presses any D-pad
                // arrow, dismiss the popup but DO NOT consume the event —
                // let focus actually move to the next card. This is the
                // "keep browsing fluid" property: a single right-press
                // both closes the popup and moves to the next card.
                if (
                    quickInfoFor != null &&
                    event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionUp || event.key == Key.DirectionDown ||
                     event.key == Key.DirectionLeft || event.key == Key.DirectionRight)
                ) {
                    onQuickInfoForChange(null)
                    return@onPreviewKeyEvent false
                }

                // ── Quick-info popup dismissal via Back ───────────────
                // Consume so Compose's BackHandler at the outer level
                // doesn't try to navigate away.
                if (
                    quickInfoFor != null &&
                    event.type == KeyEventType.KeyDown &&
                    event.key == Key.Back
                ) {
                    onQuickInfoForChange(null)
                    return@onPreviewKeyEvent true
                }

                // ── Menu key handling ─────────────────────────────────
                // Single Menu tap on a focused card → toggle quick-info
                //   popup for that card.
                // Long-hold Menu (≥3s) on a Continue Watching item →
                //   clear that item from the CW list (existing behavior
                //   preserved). Only fires from CW category.
                //
                // We track press start time and long-press fired flag.
                // On KeyDown we either consume immediately (for long-press
                // detection on subsequent KeyDown autorepeats) or pass
                // through (so the system doesn't do something else).
                // On KeyUp we decide: long-press already handled → consume;
                // otherwise it was a quick tap → toggle the popup.
                if (event.key != Key.Menu || focused == null) {
                    return@onPreviewKeyEvent false
                }

                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (menuPressStartMs == 0L) {
                            menuPressStartMs = System.currentTimeMillis()
                            menuLongPressFired = false
                        }

                        // Long-press CW clear only triggers within the
                        // Continue Watching category. For other categories
                        // we only treat Menu as a quick-tap on KeyUp.
                        val isCwCategory = selectedCategoryId == "continue_watching"
                        if (
                            isCwCategory &&
                            !menuLongPressFired &&
                            System.currentTimeMillis() - menuPressStartMs >= 3_000L
                        ) {
                            menuLongPressFired = true
                            onClearContinueWatching(focused)
                            true
                        } else {
                            // Don't consume yet — we need KeyUp to fire
                            // for the quick-tap path. Returning false here
                            // is correct because the framework otherwise
                            // would still autorepeat KeyDown but we don't
                            // care about that for Menu.
                            false
                        }
                    }
                    KeyEventType.KeyUp -> {
                        val wasLongPress = menuLongPressFired
                        val pressDurationMs = System.currentTimeMillis() - menuPressStartMs
                        menuPressStartMs = 0L
                        menuLongPressFired = false

                        if (wasLongPress) {
                            // Long-press already did its thing on KeyDown;
                            // just swallow the KeyUp.
                            true
                        } else {
                            // Quick tap. Skip non-Movies/Series-style items
                            // (the popup is only meaningful for VOD content).
                            val streamType = focused.streamType
                            if (streamType != "movie" && streamType != "series") {
                                return@onPreviewKeyEvent false
                            }

                            // Ignore unreasonably short presses (< 50ms is
                            // almost certainly a stray event from the
                            // remote, not an intentional tap). Standard
                            // remote-button taps are 100-300ms typically.
                            if (pressDurationMs in 50..2_500L) {
                                if (quickInfoFor?.id == focused.id) {
                                    // Re-tap on same card = close
                                    onQuickInfoForChange(null)
                                } else {
                                    // New tap or different card = open
                                    onQuickInfoForChange(focused)
                                }
                                true
                            } else {
                                false
                            }
                        }
                    }
                    else -> false
                }
            }
            .padding(top = 22.dp, bottom = 18.dp)
    ) {
        Row(Modifier.fillMaxSize()) {
            // Sidebar — always present, no animated visibility wrapper.
            Spacer(Modifier.width(24.dp))
            CategoryColumnRevealable(
                categories = categories,
                selected = selectedCategoryId,
                onCategory = onCategory
            )
            Spacer(Modifier.width(22.dp))

            // Grid takes the remaining width. Fixed 5 columns.
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(end = 16.dp)
            ) {
                if (title == "Movies" || title == "Series") {
                    MovieMediaGrid(
                        items = items,
                        movieFavorites = favoriteIds,
                        focusedItemId = gridFocusedItemId,
                        restoreFocusRequest = restoreFocusRequest,
                        savedScroll = savedScroll,
                        columns = columns,
                        onFocused = onFocused,
                        onClick = onOpen,
                        onLongPress = onToggleFavorite,
                        onCardBoundsCaptured = onCardBoundsCaptured,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    MediaGrid(items = items, columns = columns, onFocused = onFocused, onClick = onOpen, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

// Sidebar category list. Always visible (no longer "revealable" — the name
// is kept for source-stability). Uses Compose's default D-pad focus
// traversal to move into/out of the grid; no custom RIGHT/LEFT handlers
// needed. The selected-category focus requester is kept around purely for
// back-from-detail focus restore via the parent.
@Composable
private fun CategoryColumnRevealable(
    categories: List<Category>,
    selected: String,
    onCategory: (String) -> Unit
) {
    Column(
        Modifier
            .width(220.dp)
            .fillMaxHeight()
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(
                categories,
                key = { it.id },
                contentType = { "category_row" }
            ) { cat ->
                CategoryRow(cat, selected == cat.id) { onCategory(cat.id) }
            }
        }
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveEdgeFollowBringIntoView(content: @Composable () -> Unit) {
    val edgeFollowSpec = remember {
        object : BringIntoViewSpec {
            // Snappy scroll animation matching the movie grid: 130ms tween
            // with FastOutSlowInEasing. Channel grid focus moves feel
            // immediate rather than dragging.
            // See Components.kt for full explanation; Nextv uses Compose's default
            // BringIntoViewSpec which is a spring at StiffnessMediumLow (400f).
            override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> =
                androidx.compose.animation.core.spring(
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                    visibilityThreshold = 0.5f
                )

            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                // Center-anchored scroll: focused item's center aligns with
                // viewport center. See Components.kt for full rationale.
                return offset + size / 2f - containerSize / 2f
            }
        }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides edgeFollowSpec) {
        content()
    }
}

@Composable
private fun LiveLayout(
    state: UiState,
    items: List<MediaItem>,
    onCategory: (String) -> Unit,
    onFocused: (MediaItem) -> Unit,
    onOpen: (MediaItem) -> Unit,
    onToggleFavorite: (MediaItem) -> Unit,
    onFullscreenStateChange: (Boolean) -> Unit,
    onPlayingChanged: (MediaItem?) -> Unit,
    menuFullscreenRequest: Int
) {
    var detailMode by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<MediaItem?>(null) }
    var fullscreen by remember { mutableStateOf(false) }
    var liveGridRestoreItemId by remember { mutableStateOf<String?>(null) }
    var liveGridPreferredColumn by remember { mutableStateOf<Int?>(null) }
    var liveGridVerticalNavigationPending by remember { mutableStateOf(false) }
    var liveGridRedirectingFocus by remember { mutableStateOf(false) }
    val selectedChannelFocusRequester = remember { FocusRequester() }
    val liveGridFocusRequesters = remember(items) { items.associate { it.id to FocusRequester() } }
    val liveGridState = rememberLazyGridState()
    val liveChannelListState = rememberLazyListState()
    val liveCategoryListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val context = LocalContext.current
    val liveKeyboard = LocalSoftwareKeyboardController.current
    val player = remember { ExoPlayer.Builder(context).build() }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    LaunchedEffect(detailMode, playing?.id, state.activeLiveCategoryId) {
        if (detailMode && playing != null) {
            val selectedChannelIndex = items.indexOfFirst { it.id == playing?.id }
            val selectedCategoryIndex = state.liveCategories.indexOfFirst { it.id == state.activeLiveCategoryId }

            // Only center the channel list when a channel is first selected or changed.
            // Do NOT re-scroll the channel list when returning from fullscreen, because that
            // causes the selected playing channel to visibly jump.
            if (selectedChannelIndex >= 0) {
                liveChannelListState.scrollToItem((selectedChannelIndex - 4).coerceAtLeast(0))
            }

            if (selectedCategoryIndex >= 0) {
                liveCategoryListState.scrollToItem((selectedCategoryIndex - 4).coerceAtLeast(0))
            }

            kotlinx.coroutines.delay(180)
            try { selectedChannelFocusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    LaunchedEffect(fullscreen) {
        if (detailMode && playing != null && !fullscreen) {
            val selectedCategoryIndex = state.liveCategories.indexOfFirst { it.id == state.activeLiveCategoryId }

            // When returning from fullscreen, keep the channel list exactly where it already is.
            // Only move the category column so the active category aligns with the selected channel.
            if (selectedCategoryIndex >= 0) {
                liveCategoryListState.scrollToItem((selectedCategoryIndex - 4).coerceAtLeast(0))
            }

            kotlinx.coroutines.delay(180)
            try { selectedChannelFocusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    LaunchedEffect(playing?.streamUrl) {
        val url = playing?.streamUrl
        if (url.isNullOrBlank()) {
            player.stop()
            player.clearMediaItems()
        } else {
            player.setMediaItem(ExoMediaItem.fromUri(url))
            player.prepare()
            player.playWhenReady = true
        }
    }

BackHandler(enabled = fullscreen) {
        fullscreen = false
    }

    BackHandler(enabled = detailMode && !fullscreen) {
        liveGridRestoreItemId = playing?.id
        detailMode = false
        playing = null
        onPlayingChanged(null)
    }

    LaunchedEffect(fullscreen) {
        onFullscreenStateChange(fullscreen)
    }

    LaunchedEffect(detailMode, liveGridRestoreItemId, items) {
        val restoreId = liveGridRestoreItemId
        if (!detailMode && restoreId != null) {
            // Give the grid time to reattach after leaving the medium player, then request the exact card.
            // No scroll calls are used here; the remembered LazyGridState preserves the position naturally.
            kotlinx.coroutines.delay(120)
            try {
                liveGridFocusRequesters[restoreId]?.requestFocus()
            } catch (_: Throwable) {
                kotlinx.coroutines.delay(80)
                try { liveGridFocusRequesters[restoreId]?.requestFocus() } catch (_: Throwable) {}
            }
            liveGridRestoreItemId = null
        }
    }

    LaunchedEffect(playing?.id) {
        onPlayingChanged(playing)
    }

    LaunchedEffect(menuFullscreenRequest) {
        if (menuFullscreenRequest > 0 && detailMode && playing != null && !fullscreen) {
            fullscreen = true
        }
    }

    if (fullscreen && playing != null) {
        FullscreenLivePlayer(
            player = player,
            title = playing?.name.orEmpty(),
            onExitFullscreen = { fullscreen = false }
        )
        return
    }



    Row(
        Modifier
            .fillMaxSize()
            .padding(start = 24.dp, top = 22.dp, end = 24.dp, bottom = 18.dp)
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyUp &&
                    it.key == Key.Menu &&
                    detailMode && playing != null
                ) {
                    liveKeyboard?.hide()
                    fullscreen = true
                    true
                } else {
                    false
                }
            }
    ) {
        LiveDetailCategoryColumn(state.liveCategories, state.activeLiveCategoryId, liveCategoryListState, onCategory)
        Spacer(Modifier.width(18.dp))

        if (!detailMode) {
            LiveEdgeFollowBringIntoView {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    state = liveGridState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                        val itemFocusRequester = liveGridFocusRequesters[item.id] ?: FocusRequester()

                        ChannelTile(
                            item = item,
                            isFavorite = state.liveFavorites.contains(item.id),
                            onFocused = {
                                val preferredColumn = liveGridPreferredColumn
                                val currentColumn = index % 5

                                if (liveGridVerticalNavigationPending &&
                                    !liveGridRedirectingFocus &&
                                    preferredColumn != null &&
                                    currentColumn != preferredColumn
                                ) {
                                    // Vertical navigation landed us in the wrong column —
                                    // either because Compose's geometric focus search picked
                                    // the leftmost attached focusable when the true target
                                    // wasn't yet composed/scrolled-in, OR because the target
                                    // row has fewer than 5 items.
                                    val rowStart = index - currentColumn
                                    val targetIndex = rowStart + preferredColumn

                                    // If the preferred-column item exists at all in this row,
                                    // redirect to it. If the target isn't currently attached
                                    // (still being scrolled in), wait for it via snapshotFlow.
                                    // CRITICALLY: do NOT overwrite preferredColumn here on
                                    // failure — preserve user intent for subsequent presses.
                                    if (targetIndex in items.indices) {
                                        liveGridRedirectingFocus = true
                                        liveGridVerticalNavigationPending = false
                                        coroutineScope.launch {
                                            try {
                                                // Try immediately; if target is attached, done.
                                                val attachedNow = liveGridState.layoutInfo
                                                    .visibleItemsInfo.any { it.index == targetIndex }
                                                val targetId = items.getOrNull(targetIndex)?.id
                                                val requester = targetId?.let { liveGridFocusRequesters[it] }

                                                if (attachedNow && requester != null) {
                                                    requester.requestFocus()
                                                } else if (requester != null) {
                                                    // Wait for the target to become attached
                                                    // (Compose's bring-into-view will scroll
                                                    // it on-screen within a few frames).
                                                    withTimeoutOrNull(200) {
                                                        snapshotFlow {
                                                            liveGridState.layoutInfo
                                                                .visibleItemsInfo.any { it.index == targetIndex }
                                                        }.first { it }
                                                    }
                                                    try { requester.requestFocus() } catch (_: Throwable) {}
                                                }
                                            } catch (_: Throwable) {}
                                            kotlinx.coroutines.delay(40)
                                            liveGridRedirectingFocus = false
                                        }
                                        return@ChannelTile
                                    }
                                    // Target row genuinely doesn't have preferred column
                                    // (e.g. ragged last row). Accept current placement but
                                    // DO NOT overwrite preferredColumn — user's intent for
                                    // subsequent navigation stays intact.
                                    liveGridVerticalNavigationPending = false
                                    onFocused(item)
                                    return@ChannelTile
                                }

                                // Focus arrived without a pending vertical-nav redirect:
                                // record the current column ONLY when this is a fresh
                                // entry / horizontal navigation. If a vertical-nav was
                                // pending but redirect wasn't triggered (currentColumn
                                // already matched preferredColumn — i.e. correct landing),
                                // we keep preferredColumn unchanged so subsequent
                                // vertical presses continue using the same column.
                                if (!liveGridVerticalNavigationPending) {
                                    liveGridPreferredColumn = currentColumn
                                }
                                liveGridVerticalNavigationPending = false
                                onFocused(item)
                            },
                            onClick = {
                                liveKeyboard?.hide()
                                liveGridRestoreItemId = item.id
                                detailMode = true
                                playing = item
                                onFocused(item)
                            },
                            onLongPress = { onToggleFavorite(item) },
                            modifier = Modifier
                                .focusRequester(itemFocusRequester)
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown) {
                                        when (event.key) {
                                            Key.DirectionDown, Key.DirectionUp -> {
                                                // Vertical press: remember our column ONLY
                                                // if we don't already have a preferred column
                                                // from an earlier vertical press. This keeps
                                                // the column "locked" across multiple Down/Up
                                                // presses, even if the user passes through
                                                // ragged rows.
                                                if (liveGridPreferredColumn == null) {
                                                    liveGridPreferredColumn = index % 5
                                                }
                                                liveGridVerticalNavigationPending = true
                                            }
                                            Key.DirectionLeft, Key.DirectionRight -> {
                                                // Horizontal press: user explicitly switched
                                                // columns — clear so the new column gets
                                                // adopted as soon as focus arrives.
                                                liveGridPreferredColumn = null
                                                liveGridVerticalNavigationPending = false
                                            }
                                        }
                                    }
                                    false
                                }
                        )
                    }
                }
            }
        } else {
            Column(Modifier.width(315.dp).fillMaxHeight()) {
                Text(
                    selectedName(state.liveCategories, state.activeLiveCategoryId),
                    color = QtoneColors.Text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(state = liveChannelListState, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(items.size) { i ->
                        val item = items[i]
                        LiveChannelListRow(
                            item = item,
                            selected = playing?.id == item.id,
                            isFavorite = state.liveFavorites.contains(item.id),
                            modifier = if (playing?.id == item.id) Modifier.focusRequester(selectedChannelFocusRequester) else Modifier,
                            onFocused = { onFocused(item) },
                            onClick = {
                                if (playing?.id == item.id) {
                                    liveKeyboard?.hide()
                                    fullscreen = true
                                } else {
                                    playing = item
                                    onFocused(item)
                                }
                            },
                            onLongPress = { onToggleFavorite(item) }
                        )
                    }
                }
            }

            Spacer(Modifier.width(22.dp))

            Column(Modifier.weight(1f).fillMaxHeight()) {
                playing?.let { current ->
                    EmbeddedLivePlayer(
                        player = player,
                        modifier = Modifier.fillMaxWidth().height(380.dp),
                        onFullscreen = { liveKeyboard?.hide(); fullscreen = true }
                    )

                    Spacer(Modifier.height(14.dp))

                    Surface(
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                        color = Color(0x66000000),
                        contentColor = QtoneColors.Text
                    ) {
                        Row(
                            Modifier.fillMaxSize().padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                current.name,
                                color = QtoneColors.Text,
                                fontSize = 15.sp,
                                lineHeight = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
        }



@Composable
private fun LiveDetailCategoryColumn(
    categories: List<Category>,
    selected: String,
    listState: LazyListState,
    onCategory: (String) -> Unit
) {
    Column(Modifier.width(154.dp).fillMaxHeight()) {
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(
                categories,
                key = { it.id },
                contentType = { "category_row" }
            ) { cat ->
                CategoryRow(cat, selected == cat.id) { onCategory(cat.id) }
            }
        }
    }
}

@Composable
private fun CategoryColumn(categories: List<Category>, selected: String, onCategory: (String) -> Unit) {
    Column(Modifier.width(154.dp).fillMaxHeight()) {
        // Stable keys keyed on category.id let Compose reuse composables when
        // the category list changes order or contents (e.g. recently_added,
        // favorites entries that come and go). Index-based items() cause
        // unnecessary recomposition.
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(
                categories,
                key = { it.id },
                contentType = { "category_row" }
            ) { cat ->
                CategoryRow(cat, selected == cat.id) { onCategory(cat.id) }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    metadataLanguage: String,
    credentials: Credentials,
    accountExpirationMs: Long?,
    error: String?,
    onMetadataLanguage: (String) -> Unit,
    onLogout: () -> Unit,
    onChangeUrl: (String) -> Unit,
    onCheckForUpdates: () -> Unit
) {
    // NOTE: onChangeUrl and credentials.server are intentionally left in the
    // signature (even though the URL editing UI is no longer rendered) so
    // call sites in MainActivity don't need to change. If you later want
    // to fully drop the URL-change capability, remove them from
    // AppShell's SettingsScreen invocation and from this declaration.

    val expirationText = remember(accountExpirationMs) {
        accountExpirationMs
            ?.let { SimpleDateFormat("dd/MM/yyyy h:mm a", Locale.US).format(Date(it)) }
            ?: "Unavailable"
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(44.dp)) {
        Text("Settings", color = QtoneColors.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold)

        Spacer(Modifier.height(22.dp))
        SettingsSectionTitle("Account")
        DarkButton("Expiration Date: $expirationText")

        Spacer(Modifier.height(22.dp))
        SettingsSectionTitle("Movies and Series Display Language")
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DarkButton(if (metadataLanguage == "en-US") "✓ English" else "English") {
                onMetadataLanguage("en-US")
            }

            DarkButton(if (metadataLanguage == "es-MX") "✓ Spanish" else "Spanish") {
                onMetadataLanguage("es-MX")
            }
        }

        // URL section removed per design — users no longer change the
        // server URL from Settings. They log out and re-enter credentials
        // on the login screen if they need to switch providers.

        Spacer(Modifier.height(22.dp))
        SettingsSectionTitle("App Update")
        // Show the installed version. BuildConfig is generated by Gradle
        // from versionName in app/build.gradle.kts — both stay in sync.
        Text(
            "Installed version: ${BuildConfig.VERSION_NAME}",
            color = QtoneColors.Muted,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(10.dp))
        // The button triggers an immediate update check (delegated up to
        // MainActivity via the onCheckForUpdates callback). If a newer
        // version exists, the global UpdateAvailableDialog appears. If not,
        // the button is a no-op visually — the absence of the dialog is
        // the signal "you're up to date." We could add a toast here later.
        DarkButton("Check for updates") {
            onCheckForUpdates()
        }

        error?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = Color(0xFFFF6B6B), fontSize = 13.sp)
        }

        Spacer(Modifier.height(28.dp))
        SettingsSectionTitle("Session")
        Text(
            "Log out and return to the login screen. Your saved credentials will remain filled in.",
            color = QtoneColors.Muted,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(10.dp))
        PurpleButton("Log Out", onClick = onLogout)

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(text, color = QtoneColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun SimpleScreen(title: String, subtitle: String) {
    Column(Modifier.fillMaxSize().padding(44.dp)) {
        Text(title, color = QtoneColors.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(subtitle, color = QtoneColors.Muted, fontSize = 17.sp)
    }
}


@Composable
private fun LoginScreen(
    state: UiState,
    onLogin: (String, String, String) -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Fields start blank for new users. If credentials have been saved
    // previously (returning user after logout, or successful prior login),
    // those values populate the fields automatically.
    var server by remember { mutableStateOf(state.credentials.server) }
    var username by remember { mutableStateOf(state.credentials.username) }
    var password by remember { mutableStateOf(state.credentials.password) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(430.dp)
                .background(Color(0xDD101015), androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .padding(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Branding text removed. The card itself is the entry point.
            LoginField("Server URL", server, false) { server = it }
            Spacer(Modifier.height(12.dp))
            LoginField("Username", username, false) { username = it }
            Spacer(Modifier.height(12.dp))
            LoginField("Password", password, true) { password = it }

            state.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = Color(0xFFFF6B6B), fontSize = 13.sp)
            }

            Spacer(Modifier.height(22.dp))
            PurpleButton("CONNECT", onClick = {
                keyboard?.hide()
                // focusManager.clearFocus(force = true)
                onLogin(server, username, password)
            })
        }
    }
}




@Composable
private fun LoginField(label: String, value: String, password: Boolean, onValue: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    // When this is a password field, allow the user to toggle visibility.
    // Local state — defaults to hidden; non-password fields ignore this entirely.
    var passwordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(editing) {
        if (editing) {
            kotlinx.coroutines.delay(80)
            try { requester.requestFocus() } catch (_: Exception) {}
            keyboard?.show()
        }
    }

    if (editing) {
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            label = { Text(label) },
            visualTransformation = if (password && !passwordVisible)
                PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = if (password) {
                {
                    EyeToggleButton(
                        visible = passwordVisible,
                        onToggle = { passwordVisible = !passwordVisible }
                    )
                }
            } else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                editing = false
                keyboard?.hide()
                focusManager.clearFocus(force = true)
            }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(requester),
            textStyle = androidx.compose.ui.text.TextStyle(color = QtoneColors.Text, fontSize = 16.sp)
        )
    } else {
        // Resting mode: tappable surface that shows the label + value (bulleted
        // for passwords when hidden). For password fields, layout an inline eye
        // toggle on the right that the user can d-pad over to.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = { editing = true },
                modifier = Modifier.weight(1f).height(58.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                color = Color(0xFF15151B),
                contentColor = QtoneColors.Text,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x44FFFFFF))
            ) {
                Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 7.dp)) {
                    Text(label, color = QtoneColors.Muted, fontSize = 11.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            password && value.isNotBlank() && !passwordVisible ->
                                "•".repeat(value.length.coerceAtMost(18))
                            else -> value
                        },
                        color = QtoneColors.Text,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (password) {
                Spacer(Modifier.width(8.dp))
                EyeToggleButton(
                    visible = passwordVisible,
                    onToggle = { passwordVisible = !passwordVisible }
                )
            }
        }
    }
}

@Composable
private fun EyeToggleButton(visible: Boolean, onToggle: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onToggle,
        modifier = Modifier
            .size(48.dp)
            .onFocusChanged { focused = it.isFocused },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        color = if (focused) Color(0xFF2A2238) else Color(0xFF15151B),
        contentColor = QtoneColors.Text,
        border = androidx.compose.foundation.BorderStroke(
            if (focused) 2.dp else 1.dp,
            if (focused) Color(0xFFFFFFFF) else Color(0x44FFFFFF)
        )
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Plain-text icon glyph so we do not require an icon dependency.
            // ◉ = visible (eye open / showing), ◎ = hidden (eye closed / masked).
            Text(
                if (visible) "◉" else "◎",
                color = if (focused) QtoneColors.Text else QtoneColors.Muted,
                fontSize = 20.sp
            )
        }
    }
}

@Composable
private fun ContentUpdateScreen(state: UiState) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        keyboard?.hide()
        // focusManager.clearFocus(force = true)
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(520.dp)
                .background(Color(0xDD101015), androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .padding(30.dp)
        ) {
            // Qtone brand text removed; keep just the loading line + progress.
            Text("Loading your content…", color = QtoneColors.Muted, fontSize = 16.sp)
            Spacer(Modifier.height(26.dp))

            UpdateProgressRow("Live TV", state.liveProgress)
            Spacer(Modifier.height(18.dp))
            UpdateProgressRow("Movies", state.movieProgress)
            Spacer(Modifier.height(18.dp))
            UpdateProgressRow("Series", state.seriesProgress)
        }
    }
}

@Composable
private fun UpdateProgressRow(label: String, progress: Float) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), label = label)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = QtoneColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(90.dp))
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier.weight(1f).height(10.dp),
            color = Color(0xFFFFFFFF),
            trackColor = Color(0xFF2A2633)
        )
        Spacer(Modifier.width(16.dp))
        Text(if (progress >= 1f) "✓" else "", color = Color(0xFF80FFB0), fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
    }
}





@Composable
private fun SearchScreen(
    searchSection: Section,
    searchItems: (Section, String) -> List<MediaItem>,
    liveFavorites: Set<String>,
    onToggleLiveFavorite: (MediaItem) -> Unit,
    onLiveSearchSubmit: (String) -> Unit,
    onMovieSearchSubmit: (String) -> Unit,
    onSeriesSearchSubmit: (String) -> Unit,
    onOpen: (MediaItem) -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val searchFocusRequester = remember { FocusRequester() }

    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var hasSearched by remember { mutableStateOf(false) }

    LaunchedEffect(editing) {
        if (editing) {
            kotlinx.coroutines.delay(80)
            try { searchFocusRequester.requestFocus() } catch (_: Exception) {}
            keyboard?.show()
        }
    }

    fun runSearch() {
        keyboard?.hide()
        focusManager.clearFocus(force = true)

        if (searchSection == Section.Live) {
            onLiveSearchSubmit(query)
            return
        }

        if (searchSection == Section.Movies) {
            onMovieSearchSubmit(query)
            return
        }

        if (searchSection == Section.Series) {
            onSeriesSearchSubmit(query)
            return
        }

        results = searchItems(searchSection, query)
        hasSearched = true
        editing = false
    }

    Column(Modifier.fillMaxSize().padding(36.dp)) {
        Text("Search ${searchSection.label}", color = QtoneColors.Text, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            if (searchSection == Section.Live)
                "Searches all Live TV channels and shows results inside the Live TV Search Results category."
            else if (searchSection == Section.Movies)
                "Searches all Movies and shows results inside the Movies Search Results category."
            else if (searchSection == Section.Series)
                "Searches all Series and shows results inside the Series Search Results category."
            else
                "Searches all ${searchSection.label} content, not only the selected category.",
            color = QtoneColors.Muted,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(16.dp))

        if (editing) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("Search ${searchSection.label}") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { runSearch() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(searchFocusRequester),
                textStyle = androidx.compose.ui.text.TextStyle(color = QtoneColors.Text, fontSize = 16.sp)
            )
        } else {
            Surface(
                onClick = { editing = true },
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                color = Color(0xFF15151B),
                contentColor = QtoneColors.Text,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x44FFFFFF))
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (query.isBlank()) "Click to search all ${searchSection.label}" else query,
                        color = if (query.isBlank()) QtoneColors.Muted else QtoneColors.Text,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        if (hasSearched && results.isEmpty()) {
            Text("No results found.", color = QtoneColors.Muted, fontSize = 16.sp)
        } else if (searchSection == Section.Live) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(results, key = { it.id }) { item ->
                    ChannelTile(
                        item = item,
                        isFavorite = liveFavorites.contains(item.id),
                        onFocused = {},
                        onClick = { onOpen(item) },
                        onLongPress = { onToggleLiveFavorite(item) }
                    )
                }
            }
        } else {
            MediaGrid(
                items = results,
                columns = 5,
                onFocused = {},
                onClick = onOpen,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(60.dp), verticalArrangement = Arrangement.Center) {
        Text("Could not load content", color = QtoneColors.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(message, color = QtoneColors.Muted, fontSize = 16.sp)
        Spacer(Modifier.height(18.dp))
        PurpleButton("Retry", onRetry)
    }
}

private fun selectedName(categories: List<Category>, id: String): String =
    categories.firstOrNull { it.id == id }?.name ?: "Category"
