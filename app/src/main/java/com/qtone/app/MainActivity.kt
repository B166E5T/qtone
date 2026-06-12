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
            // Hoisted LazyListState for the Movies and Series categories sidebars.
            // Lives in the outer setContent {} scope so it survives the
            // AppShell unmount/remount that happens when the user opens a
            // detail screen. Without this, the sidebar would reset to scroll
            // position 0 every time the user pressed Back from a movie or
            // series detail screen — even if they had scrolled the sidebar
            // to (say) "Action" or "Horror" before clicking the card.
            //
            // Same pattern as moviesSavedScroll / seriesSavedScroll above,
            // just for the sidebar instead of the grid. Live TV's sidebar
            // doesn't need this treatment because its "detail mode" is an
            // in-place state change (LiveLayout stays mounted), not an
            // AppShell-unmount transition.
            val moviesCategoryListState = rememberLazyListState()
            val seriesCategoryListState = rememberLazyListState()
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
            var multiviewActive by remember { mutableStateOf(false) }
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
            if (multiviewActive) {
                MultiviewScreen(
                    liveCategories = state.liveCategories,
                    liveStreams = state.live,
                    onExit = {
                        multiviewActive = false
                        vm.setSection(Section.Live)
                    }
                )
            } else {
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
                    val latest = state.movies.firstOrNull { it.id == selected.id }
                        ?: state.series.firstOrNull { it.id == selected.id }
                        ?: selected
                    if (selected.streamType == "movie") {
                        vm.fetchMovieMetadata(latest, fetchSimilar = true)
                    } else if (selected.streamType == "series") {
                        vm.fetchSeriesMetadata(latest)
                    }
                }
                LaunchedEffect(selected.id, selected.streamType, state.metadataLanguage) {
                    if (selected.streamType == "movie") {
                        vm.fetchSimilarMoviesFor(selected)
                    }
                }
                val similarMoviesFromVm by vm.similarMoviesByItemId.collectAsState()
                val watchedEpisodes by vm.watchedEpisodeIds.collectAsState()
                val stableSimilarMovies = if (selected.streamType == "movie") {
                    similarMoviesFromVm[selected.id].orEmpty()
                } else {
                    emptyList()
                }
                val liveItem = state.movies.firstOrNull { it.id == selected.id }
                    ?: state.series.firstOrNull { it.id == selected.id }
                    ?: selected
                MovieDetailScreen(
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
                        detailBackStack.add(liveItem)
                        initialSimilarFocusId = null
                        forceFirstSimilarFocusForItemId = next.id
                        detailItem = next
                    },
                    onEpisodeOpen = { episode ->
                        val allEpisodes = state.seriesEpisodes[selected.id]
                            .orEmpty()
                            .sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
                        val currentIndex = allEpisodes.indexOfFirst { it.id == episode.id }
                        openItem(
                            MediaItem(
                                id = episode.id,
                                name = episode.title,
                                streamType = "series_episode",
                                categoryId = episode.seriesId,
                                poster = episode.poster ?: liveItem.poster,
                                plot = episode.plot,
                                streamUrl = episode.streamUrl
                            ),
                            episodes = allEpisodes,
                            episodeIndex = currentIndex
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
                                quickInfoFor = null
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
                        moviesCategoryListState = moviesCategoryListState,
                        seriesCategoryListState = seriesCategoryListState,
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
                        onMultiview = { multiviewActive = true },
                        onCheckForUpdates = {
                            lifecycleScope.launch {
                                updateDismissed = false
                                updateCheck = UpdateChecker.check()
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
                        quickInfoFor = quickInfoFor,
                        onQuickInfoForChange = { item ->
                            quickInfoFor = item
                            if (item != null) {
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
            (updateCheck as? UpdateChecker.Result.UpdateAvailable)?.let { available ->
                if (!updateDismissed || available.isMandatory) {
                    UpdateAvailableDialog(
                        manifest = available.manifest,
                        isMandatory = available.isMandatory,
                        onDismiss = {
                            if (!available.isMandatory) updateDismissed = true
                        },
                        onAcceptUpdate = {
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
            } // end else (multiviewActive)
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
        if (launchingPlayerActivity || isChangingConfigurations) {
            launchingPlayerActivity = false
        }
    }
    private fun openItem(
        item: MediaItem,
        episodes: List<com.qtone.app.model.SeriesEpisode> = emptyList(),
        episodeIndex: Int = -1
    ) {
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
                if (episodes.isNotEmpty() && episodeIndex >= 0) {
                    putExtra("episodes_json", com.google.gson.Gson().toJson(episodes))
                    putExtra("episode_index", episodeIndex)
                }
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
    // Hoisted sidebar scroll state. See declarations in setContent {} for
    // why these can't live inside AppShell (AppShell unmounts on detail
    // screen open, which would reset any locally-remembered state).
    moviesCategoryListState: LazyListState,
    seriesCategoryListState: LazyListState,
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
    onMultiview: () -> Unit,
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
    var posterFocusedItem by remember(state.section) { mutableStateOf<MediaItem?>(null) }
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
            title = { Text("Exit?", color = QtoneColors.Text) },
            text = { Text("Do you really want to exit the app?", color = QtoneColors.Muted) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitDialog = false
                        (context as? android.app.Activity)?.finish()
                    }
                ) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) { Text("No") }
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
                focusedItem = posterFocusedItem,
                gridFocusedItemId = lastMovieClickedId,
                favoriteIds = state.movieFavorites,
                onToggleFavorite = onToggleMovieFavorite,
                onClearContinueWatching = onClearContinueWatching,
                restoreFocusRequest = posterGridRestoreRequest,
                savedScroll = moviesSavedScroll,
                categoryListState = moviesCategoryListState,
                onCategory = { dismissKeyboardOnly(); onCategory(it) },
                onFocused = onPosterFocused,
                onOpen = { item ->
                    dismissKeyboardOnly()
                    onMovieClicked(item.id)
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
                categoryListState = seriesCategoryListState,
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
                onMultiview = onMultiview,
                onCheckForUpdates = onCheckForUpdates
            )
        }
    }
}
@Composable
private fun TopNav(selected: Section, onSection: (Section) -> Unit, onUpdate: () -> Unit) {
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
    var focused by remember { mutableStateOf(false) }
    val pillColor by animateColorAsState(
        targetValue = if (selected) Color.White else Color.Transparent,
        animationSpec = tween(durationMillis = 90),
        label = "topNavPillColor"
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused) Color(0xFFFFFFFF) else Color.Transparent,
        animationSpec = tween(durationMillis = 90),
        label = "topNavBorderColor"
    )
    val textColor by animateColorAsState(
        targetValue = when {
            selected -> Color(0xFF0A0A0E)
            focused -> Color.White
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
        color = Color.Transparent,
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
    focusedItem: MediaItem?,
    gridFocusedItemId: String? = null,
    favoriteIds: Set<String> = emptySet(),
    onToggleFavorite: (MediaItem) -> Unit = {},
    onClearContinueWatching: (MediaItem) -> Unit = {},
    restoreFocusRequest: Int = 0,
    savedScroll: androidx.compose.runtime.MutableState<Pair<Int, Int>?>? = null,
    // Hoisted sidebar scroll state. Forwarded to CategoryColumnRevealable so
    // the LazyColumn binds to a state object that survives the AppShell
    // unmount/remount during the detail screen round trip.
    categoryListState: LazyListState,
    onCategory: (String) -> Unit,
    onFocused: (MediaItem) -> Unit,
    onOpen: (MediaItem) -> Unit,
    quickInfoFor: MediaItem? = null,
    onQuickInfoForChange: (MediaItem?) -> Unit = {},
    onCardBoundsCaptured: (FocusedCardBounds?) -> Unit = {}
) {
    var menuPressStartMs by remember { mutableStateOf(0L) }
    var menuLongPressFired by remember { mutableStateOf(false) }
    val columns = 5
    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                val focused = focusedItem
                if (
                    quickInfoFor != null &&
                    event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionUp || event.key == Key.DirectionDown ||
                     event.key == Key.DirectionLeft || event.key == Key.DirectionRight)
                ) {
                    onQuickInfoForChange(null)
                    return@onPreviewKeyEvent false
                }
                if (
                    quickInfoFor != null &&
                    event.type == KeyEventType.KeyDown &&
                    event.key == Key.Back
                ) {
                    onQuickInfoForChange(null)
                    return@onPreviewKeyEvent true
                }
                if (event.key != Key.Menu || focused == null) {
                    return@onPreviewKeyEvent false
                }
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (menuPressStartMs == 0L) {
                            menuPressStartMs = System.currentTimeMillis()
                            menuLongPressFired = false
                        }
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
                            false
                        }
                    }
                    KeyEventType.KeyUp -> {
                        val wasLongPress = menuLongPressFired
                        val pressDurationMs = System.currentTimeMillis() - menuPressStartMs
                        menuPressStartMs = 0L
                        menuLongPressFired = false
                        if (wasLongPress) {
                            true
                        } else {
                            val streamType = focused.streamType
                            if (streamType != "movie" && streamType != "series") {
                                return@onPreviewKeyEvent false
                            }
                            if (pressDurationMs in 50..2_500L) {
                                if (quickInfoFor?.id == focused.id) {
                                    onQuickInfoForChange(null)
                                } else {
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
            Spacer(Modifier.width(24.dp))
            CategoryColumnRevealable(
                categories = categories,
                selected = selectedCategoryId,
                listState = categoryListState,
                onCategory = onCategory
            )
            Spacer(Modifier.width(22.dp))
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
                        selectedCategoryId = selectedCategoryId,
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
// is kept for source-stability). The listState is passed in (hoisted to
// setContent {}) so the scroll position survives the AppShell unmount/remount
// that happens when the user opens a detail screen and presses Back.
@Composable
private fun CategoryColumnRevealable(
    categories: List<Category>,
    selected: String,
    listState: LazyListState,
    onCategory: (String) -> Unit
) {
    Column(
        Modifier
            .width(220.dp)
            .fillMaxHeight()
    ) {
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveEdgeFollowBringIntoView(content: @Composable () -> Unit) {
    val edgeFollowSpec = remember {
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> =
                androidx.compose.animation.core.spring(
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                    visibilityThreshold = 0.5f
                )
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
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
    val player = remember {
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setExtensionRendererMode(
                androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,    // minBufferMs: keep at least 15s buffered
                50_000,    // maxBufferMs: buffer up to 50s ahead
                2_500,     // bufferForPlaybackMs: 2.5s before initial playback (unchanged from default — fast channel load)
                5_000      // bufferForPlaybackAfterRebufferMs: 5s before resuming after a stall
            )
            .build()
        ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .build()
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }
    LaunchedEffect(detailMode, playing?.id, state.activeLiveCategoryId) {
        if (detailMode && playing != null) {
            val selectedChannelIndex = items.indexOfFirst { it.id == playing?.id }
            val selectedCategoryIndex = state.liveCategories.indexOfFirst { it.id == state.activeLiveCategoryId }
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
    LaunchedEffect(state.activeLiveCategoryId) {
        if (!detailMode) {
            liveGridState.scrollToItem(0)
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
                                    val rowStart = index - currentColumn
                                    val targetIndex = rowStart + preferredColumn
                                    if (targetIndex in items.indices) {
                                        liveGridRedirectingFocus = true
                                        liveGridVerticalNavigationPending = false
                                        coroutineScope.launch {
                                            try {
                                                val attachedNow = liveGridState.layoutInfo
                                                    .visibleItemsInfo.any { it.index == targetIndex }
                                                val targetId = items.getOrNull(targetIndex)?.id
                                                val requester = targetId?.let { liveGridFocusRequesters[it] }
                                                if (attachedNow && requester != null) {
                                                    requester.requestFocus()
                                                } else if (requester != null) {
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
                                    liveGridVerticalNavigationPending = false
                                    onFocused(item)
                                    return@ChannelTile
                                }
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
                                                if (liveGridPreferredColumn == null) {
                                                    liveGridPreferredColumn = index % 5
                                                }
                                                liveGridVerticalNavigationPending = true
                                            }
                                            Key.DirectionLeft, Key.DirectionRight -> {
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
    onMultiview: () -> Unit,
    onCheckForUpdates: () -> Unit
) {
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
        Spacer(Modifier.height(22.dp))
        SettingsSectionTitle("Multiview")
        Text(
            "Watch two Live TV channels side by side.",
            color = QtoneColors.Muted,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(10.dp))
        PurpleButton("Launch Multiview", onClick = onMultiview)
        Spacer(Modifier.height(22.dp))
        SettingsSectionTitle("App Update")
        Text(
            "Installed version: ${BuildConfig.VERSION_NAME}",
            color = QtoneColors.Muted,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(10.dp))
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
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(520.dp)
                .background(Color(0xDD101015), androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .padding(30.dp)
        ) {
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
