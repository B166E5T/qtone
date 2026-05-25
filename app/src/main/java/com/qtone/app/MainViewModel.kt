package com.qtone.app

import android.app.Application
import coil.ImageLoader
import coil.request.ImageRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qtone.app.model.Category
import com.qtone.app.model.Credentials
import com.qtone.app.model.LoadStage
import com.qtone.app.model.MediaItem
import com.qtone.app.model.Section
import com.qtone.app.model.SeriesEpisode
import com.qtone.app.model.UiState
import com.qtone.app.network.XtreamClient
import com.qtone.app.network.TmdbClient
import com.qtone.app.storage.SessionStore
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val client = XtreamClient()
    private val tmdb = TmdbClient()
    private val store = SessionStore(app.applicationContext)
    private val movieMetaCache = mutableMapOf<String, MediaItem?>()
    private var focusUpdateJob: Job? = null
    private var movieMetaJob: Job? = null
    private var moviePreloadJob: Job? = null
    private var imageWarmupJob: Job? = null
    private val seriesMetaCache = mutableMapOf<String, MediaItem?>()
    private var seriesMetaJob: Job? = null
    private var seriesPreloadJob: Job? = null
    private var moviesFirstOpenPending = true
    private var seriesFirstOpenPending = true

    private val _state = MutableStateFlow(UiState(loading = true))
    val state: StateFlow<UiState> = _state

    // ── Watched episodes ──────────────────────────────────────────────
    private val _watchedEpisodeIds = MutableStateFlow<Set<String>>(emptySet())
    val watchedEpisodeIds: StateFlow<Set<String>> = _watchedEpisodeIds.asStateFlow()

    /** Reload watched episode IDs from disk. Call after returning from player. */
    fun refreshWatchedEpisodes() {
        _watchedEpisodeIds.value = store.getWatchedEpisodeIds()
    }

    init {
        refreshWatchedEpisodes()
        viewModelScope.launch {
            // Give Compose one frame to show the startup loading screen before cache/provider work begins.
            delay(350)
            // bootstrap() reads cached content from disk (potentially many MB of JSON
            // plus per-item SharedPreferences metadata reads). On Fire TV with a large
            // cache this can take 5-15 seconds — long enough to trigger an ANR when
            // the system fires the first FocusEvent at the app. Run it on IO.
            withContext(Dispatchers.IO) { bootstrap() }
        }
    }

    private suspend fun bootstrap() {
        val creds = store.getCredentials()
        val favorites = store.getLiveFavorites()
        val movieFavorites = store.getMovieFavorites()
        val seriesFavorites = store.getSeriesFavorites()
        val metadataLanguage = store.getMetadataLanguage()
        tmdb.setLanguage(metadataLanguage)

        if (!store.hasCredentials()) {
            _state.value = UiState(
                loading = false,
                loggedIn = false,
                liveFavorites = favorites,
                movieFavorites = movieFavorites,
                seriesFavorites = seriesFavorites,
                metadataLanguage = metadataLanguage,
                accountExpirationMs = store.getAccountExpirationMs(),
                credentials = Credentials(
                    server = "",
                    username = "",
                    password = ""
                )
            )
            return
        }

        if (store.hasCache()) {
            loadCache(creds, favorites)
            if (store.shouldAutoUpdate()) {
                refreshContent(creds, manual = false)
            }
        } else {
            _state.value = _state.value.copy(
                loading = false,
                loggedIn = true,
                credentials = creds,
                liveFavorites = favorites,
                movieFavorites = movieFavorites,
                seriesFavorites = seriesFavorites,
                metadataLanguage = metadataLanguage
            )
            refreshContent(creds, manual = false)
        }
    }

    private suspend fun loadCache(creds: Credentials, favorites: Set<String>) {
        val metadataLanguage = store.getMetadataLanguage()
        tmdb.setLanguage(metadataLanguage)
        val movieFavorites = store.getMovieFavorites()
        val seriesFavorites = store.getSeriesFavorites()
        val live = store.liveItems()
        val movies = store.movieItems().map { movie ->
            val cached = try { store.getMovieMetadata(movie.id, metadataLanguage) } catch (_: Throwable) { null }
            if (cached != null && hasUsefulMovieInfo(cached)) {
                // TMDB cached data wins, including the localized name.
                // Poster + backdrop stay as provider's URLs.
                movie.copy(
                    name = cached.name.takeIf { it.isNotBlank() } ?: movie.name,
                    poster = movie.poster,
                    backdrop = movie.backdrop,
                    rating = cached.rating?.takeIf { it.isNotBlank() } ?: movie.rating,
                    year = cached.year?.takeIf { it.isNotBlank() } ?: movie.year,
                    plot = cached.plot?.takeIf { it.isNotBlank() } ?: movie.plot,
                    genre = cached.genre?.takeIf { it.isNotBlank() } ?: movie.genre,
                    director = cached.director?.takeIf { it.isNotBlank() } ?: movie.director,
                    cast = cached.cast?.takeIf { it.isNotBlank() } ?: movie.cast
                )
            } else movie
        }
        movieMetaCache.clear()
        movies.filter { movie -> hasUsefulMovieInfo(movie) }.forEach { movie -> movieMetaCache[movie.id] = movie }

        val series = store.seriesItems().map { item ->
            val cached = try { store.getSeriesMetadata(item.id, metadataLanguage) } catch (_: Throwable) { null }
            if (cached != null && hasUsefulSeriesInfo(cached)) {
                // Same priority as movies: TMDB cache wins, provider fills blanks.
                item.copy(
                    name = cached.name.takeIf { it.isNotBlank() } ?: item.name,
                    poster = item.poster,
                    backdrop = item.backdrop,
                    rating = cached.rating?.takeIf { it.isNotBlank() } ?: item.rating,
                    year = cached.year?.takeIf { it.isNotBlank() } ?: item.year,
                    plot = cached.plot?.takeIf { it.isNotBlank() } ?: item.plot,
                    genre = cached.genre?.takeIf { it.isNotBlank() } ?: item.genre,
                    director = null,
                    cast = cached.cast?.takeIf { it.isNotBlank() } ?: item.cast
                )
            } else {
                item.copy(
                    rating = null,
                    year = null,
                    plot = null,
                    genre = null,
                    director = null,
                    cast = null
                )
            }
        }

        val liveCats = withLiveFavorites(store.liveCategories(), live, favorites)
        val movieCats = withMovieSpecialCategories(store.movieCategories(), movies, movieFavorites)
        val seriesCats = withSeriesSpecialCategories(store.seriesCategories(), series, seriesFavorites)

        _state.value = UiState(
            loading = false,
            loggedIn = true,
            updating = false,
            section = Section.Live,
            liveCategories = liveCats,
            movieCategories = movieCats,
            seriesCategories = seriesCats,
            activeLiveCategoryId = "favorites",
            activeMovieCategoryId = movieCats.firstOrNull()?.id.orEmpty(),
            activeSeriesCategoryId = seriesCats.firstOrNull()?.id.orEmpty(),
            live = live,
            movies = movies,
            series = series,
            focusedItem = filteredLive(live = live, activeId = "favorites", favorites = favorites).firstOrNull()
                ?: live.firstOrNull() ?: movies.firstOrNull() ?: series.firstOrNull(),
            liveFavorites = favorites,
            movieFavorites = movieFavorites,
            seriesFavorites = seriesFavorites,
            credentials = creds,
            metadataLanguage = metadataLanguage,
            accountExpirationMs = store.getAccountExpirationMs()
        )

        warmMoviePosterImages(filteredMovies(_state.value).ifEmpty { movies }.take(60))
        // TMDB metadata is NOT preloaded in the background anymore.
        // Each movie/series fetches its TMDB enrichment lazily — only when
        // the user opens the detail screen for that item. Cuts CPU/network
        // pressure at app start and removes a perceptible source of
        // background work on lower-spec Fire TV hardware. The cached
        // poster image warm-up (above) is enough for the grid view to
        // look complete.
    }


    fun loadSeriesEpisodes(seriesId: String) {
        if (seriesId.isBlank()) return
        // Already loading or already loaded — do not fire another network call.
        if (_state.value.seriesEpisodes.containsKey(seriesId)) return
        if (_state.value.seriesEpisodesLoading.contains(seriesId)) return

        // Mark as LOADING (separately from the episodes map). The UI uses
        // seriesEpisodesLoading to distinguish "still loading" from "loaded but
        // no episodes returned". Previously both states presented an empty list
        // so the spinner showed forever for series with no episodes.
        _state.value = _state.value.copy(
            seriesEpisodesLoading = _state.value.seriesEpisodesLoading + seriesId
        )

        viewModelScope.launch {
            try {
                val baseSeries = _state.value.series.firstOrNull { it.id == seriesId }

                // Do not let a slow IPTV provider hang the detail screen forever.
                val providerEpisodes = withTimeoutOrNull(12000) {
                    client.getSeriesEpisodes(_state.value.credentials, seriesId)
                }.orEmpty()

                // Store result (possibly empty) AND clear the loading flag. UI
                // will now show either the list or a "no episodes" message.
                _state.value = _state.value.copy(
                    seriesEpisodes = _state.value.seriesEpisodes + (seriesId to providerEpisodes),
                    seriesEpisodesLoading = _state.value.seriesEpisodesLoading - seriesId
                )

                // TMDB enrichment runs only when the provider left at least one
                // episode plot blank. If every episode already has a plot from
                // the provider, there is nothing for TMDB to add — skip the
                // network call entirely. This keeps fast-provider series snappy.
                val needsEnrichment = providerEpisodes.any { it.plot.isNullOrBlank() }
                if (baseSeries != null && providerEpisodes.isNotEmpty() && needsEnrichment) {
                    val enrichedEpisodes = try {
                        withTimeoutOrNull(12000) {
                            tmdb.enrichSeriesEpisodes(baseSeries, providerEpisodes, _state.value.metadataLanguage)
                        }
                    } catch (_: Throwable) {
                        null
                    }

                    if (!enrichedEpisodes.isNullOrEmpty()) {
                        _state.value = _state.value.copy(
                            seriesEpisodes = _state.value.seriesEpisodes + (seriesId to enrichedEpisodes)
                        )
                    }
                }
            } catch (_: Throwable) {
                // Provider call failed entirely — store empty list and clear
                // loading flag so the user sees a clear "no episodes" message
                // instead of an infinite spinner.
                _state.value = _state.value.copy(
                    seriesEpisodes = _state.value.seriesEpisodes + (seriesId to emptyList()),
                    seriesEpisodesLoading = _state.value.seriesEpisodesLoading - seriesId
                )
            }
        }
    }


    fun setMetadataLanguage(language: String) {
        val normalized = if (language == "es-MX") "es-MX" else "en-US"
        if (_state.value.metadataLanguage == normalized) return

        // Cheap synchronous work stays on the calling (Main) thread so the
        // language preference is recorded immediately even if the UI is closed
        // mid-toggle.
        store.saveMetadataLanguage(normalized)
        tmdb.setLanguage(normalized)

        // Disk-heavy work (movies/series JSON reads + per-item SharedPreferences
        // reads for the TMDB cache) runs on Dispatchers.IO. On a large cache
        // this can take several seconds — running it on Main would freeze the
        // UI and could trigger an ANR (same bug pattern we fixed in bootstrap).
        viewModelScope.launch {
            val (movies, series) = withContext(Dispatchers.IO) {
                movieMetaCache.clear()
                seriesMetaCache.clear()

                val moviesBase = store.movieItems().ifEmpty { _state.value.movies }
                val newMovies = moviesBase.map { movie ->
                    val cached = try { store.getMovieMetadata(movie.id, normalized) } catch (_: Throwable) { null }
                    if (cached != null && hasUsefulMovieInfo(cached)) {
                        // TMDB cached data wins. The name now comes from the
                        // cached entry too (it was saved by fetchMovieMetadata
                        // with the localized title); poster + backdrop stay
                        // as provider's so the Coil cache continues to match.
                        movie.copy(
                            name = cached.name.takeIf { it.isNotBlank() } ?: movie.name,
                            poster = movie.poster,
                            backdrop = movie.backdrop,
                            rating = cached.rating?.takeIf { it.isNotBlank() } ?: movie.rating,
                            year = cached.year?.takeIf { it.isNotBlank() } ?: movie.year,
                            plot = cached.plot?.takeIf { it.isNotBlank() } ?: movie.plot,
                            genre = cached.genre?.takeIf { it.isNotBlank() } ?: movie.genre,
                            director = cached.director?.takeIf { it.isNotBlank() } ?: movie.director,
                            cast = cached.cast?.takeIf { it.isNotBlank() } ?: movie.cast,
                            streamUrl = movie.streamUrl
                        )
                    } else {
                        // No TMDB cache for this language yet — keep provider
                        // data intact. The detail screen will fetch TMDB on demand
                        // when the user actually clicks the movie.
                        movie
                    }
                }

                val seriesBase = store.seriesItems().ifEmpty { _state.value.series }
                val newSeries = seriesBase.map { item ->
                    val cached = try { store.getSeriesMetadata(item.id, normalized) } catch (_: Throwable) { null }
                    if (cached != null && hasUsefulSeriesInfo(cached)) {
                        // TMDB wins (including the localized title).
                        item.copy(
                            name = cached.name.takeIf { it.isNotBlank() } ?: item.name,
                            poster = item.poster,
                            backdrop = item.backdrop,
                            rating = cached.rating?.takeIf { it.isNotBlank() } ?: item.rating,
                            year = cached.year?.takeIf { it.isNotBlank() } ?: item.year,
                            plot = cached.plot?.takeIf { it.isNotBlank() } ?: item.plot,
                            genre = cached.genre?.takeIf { it.isNotBlank() } ?: item.genre,
                            director = null,
                            cast = cached.cast?.takeIf { it.isNotBlank() } ?: item.cast,
                            streamUrl = item.streamUrl
                        )
                    } else {
                        // Keep provider data as-is when no TMDB cache exists for this language.
                        item
                    }
                }

                newMovies.filter { hasUsefulMovieInfo(it) }.forEach { movieMetaCache[it.id] = it }
                newSeries.filter { hasUsefulSeriesInfo(it) }.forEach { seriesMetaCache[it.id] = it }

                Pair(newMovies, newSeries)
            }

            // State mutation and downstream work happen back on the default
            // (Main) dispatcher inside viewModelScope. MutableStateFlow.value
            // is thread-safe regardless, but the category builders also touch
            // the SessionStore and we keep them here for clarity.
            _state.value = _state.value.copy(
                metadataLanguage = normalized,
                movies = movies,
                series = series,
                movieCategories = withMovieSpecialCategories(store.movieCategories(), movies, _state.value.movieFavorites),
                seriesCategories = withSeriesSpecialCategories(store.seriesCategories(), series, _state.value.seriesFavorites),
                focusedItem = _state.value.focusedItem
            )

            // No background TMDB preload — metadata fetches only when user
            // opens a detail screen. See note at the cached-session load
            // path for rationale.
        }
    }

    /**
     * Strip ALL invisible / non-printable characters from user input.
     * The Fire TV on-screen keyboard sometimes inserts characters like
     * non-breaking space (U+00A0), zero-width space (U+200B), or BOM
     * (U+FEFF) that are invisible on screen but break authentication
     * when URL-encoded and sent to Xtream panels.
     */
    private fun sanitizeInput(raw: String): String {
        return raw
            .replace("\uFEFF", "")   // BOM
            .replace("\u200B", "")   // zero-width space
            .replace("\u200C", "")   // zero-width non-joiner
            .replace("\u200D", "")   // zero-width joiner
            .replace("\u00A0", " ")  // non-breaking space → normal space
            .replace("\u2007", " ")  // figure space
            .replace("\u202F", " ")  // narrow no-break space
            .replace("\u2060", "")   // word joiner
            .trim()
    }

    fun login(server: String, username: String, password: String) {
        val normalizedServer = sanitizeInput(server).trimEnd('/')
        val creds = Credentials(normalizedServer, sanitizeInput(username), sanitizeInput(password))
        if (creds.server.isBlank() || creds.username.isBlank() || creds.password.isBlank()) {
            _state.value = _state.value.copy(error = "Please enter server, username, and password.")
            return
        }

        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(error = null, loading = false, loggedIn = true, credentials = creds)
                val loginError = client.login(creds)
                if (loginError != null) {
                    _state.value = _state.value.copy(loggedIn = false, error = loginError)
                    return@launch
                }
                val expirationMs = withTimeoutOrNull(12_000L) {
                    withContext(Dispatchers.IO) { client.getAccountExpirationMs(creds) }
                }
                store.saveAccountExpirationMs(expirationMs)
                store.saveCredentials(creds)
                _state.value = _state.value.copy(accountExpirationMs = expirationMs)
                refreshContent(creds, manual = true)
            } catch (e: java.net.UnknownHostException) {
                _state.value = _state.value.copy(loggedIn = false, updating = false, error = "Cannot reach server. Check the URL and your internet connection.")
            } catch (e: java.net.SocketTimeoutException) {
                _state.value = _state.value.copy(loggedIn = false, updating = false, error = "Server took too long to respond. Try again.")
            } catch (e: javax.net.ssl.SSLException) {
                _state.value = _state.value.copy(loggedIn = false, updating = false, error = "SSL error. Try using http:// instead of https://")
            } catch (e: Exception) {
                val msg = e.message ?: "Login failed."
                val userMsg = when {
                    "HTTP" in msg -> "Server error ($msg). Check the URL."
                    else -> msg
                }
                _state.value = _state.value.copy(loggedIn = false, updating = false, error = userMsg)
            }
        }
    }


    fun changeServerUrl(server: String) {
        val current = _state.value.credentials.takeIf {
            it.username.isNotBlank() && it.password.isNotBlank()
        } ?: store.getCredentials()

        val normalizedServer = server.trim().trimEnd('/')
        val creds = current.copy(server = normalizedServer)

        if (creds.server.isBlank() || creds.username.isBlank() || creds.password.isBlank()) {
            _state.value = _state.value.copy(error = "Missing Xtream URL, username, or password.")
            return
        }

        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(
                    error = null,
                    loading = false,
                    loggedIn = true,
                    updating = true,
                    credentials = creds
                )

                val loginError = withContext(Dispatchers.IO) { client.login(creds) }

                if (loginError != null) {
                    _state.value = _state.value.copy(updating = false, error = loginError)
                    return@launch
                }

                val expirationMs = withTimeoutOrNull(12_000L) {
                    withContext(Dispatchers.IO) { client.getAccountExpirationMs(creds) }
                }
                store.saveAccountExpirationMs(expirationMs)
                store.saveCredentials(creds)
                _state.value = _state.value.copy(accountExpirationMs = expirationMs)
                refreshContent(creds, manual = true)
            } catch (e: Exception) {
                _state.value = _state.value.copy(updating = false, loading = false, error = e.message ?: "URL change failed.")
            }
        }
    }

    fun manualUpdate() {
        val creds = _state.value.credentials.takeIf {
            it.server.isNotBlank() && it.username.isNotBlank() && it.password.isNotBlank()
        } ?: store.getCredentials()

        if (creds.server.isBlank() || creds.username.isBlank() || creds.password.isBlank()) {
            _state.value = _state.value.copy(error = "Missing Xtream credentials.")
            return
        }

        // No re-verification needed — if the user is inside the app they
        // already authenticated successfully. Just refresh the content.
        refreshContent(creds, manual = true)
    }

    private fun refreshContent(creds: Credentials, manual: Boolean) {
        tmdb.setLanguage(_state.value.metadataLanguage)
        movieMetaJob?.cancel()
        moviePreloadJob?.cancel()
        seriesMetaJob?.cancel()
        seriesPreloadJob?.cancel()
        imageWarmupJob?.cancel()

        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(
                    loading = false,
                    loggedIn = true,
                    updating = true,
                    error = null,
                    loadStage = LoadStage.Live,
                    liveProgress = 0f,
                    movieProgress = 0f,
                    seriesProgress = 0f,
                    credentials = creds,

                    // Important: Update must behave like a fresh Login.
                    // Clear old large lists before pulling new provider data so Firestick does not hold
                    // old Movies/Series + new Movies/Series in memory at the same time.
                    live = emptyList(),
                    movies = emptyList(),
                    series = emptyList(),
                    focusedItem = null,
                    movieSearchResults = emptyList(),
                    seriesSearchResults = emptyList(),
                    liveSearchResults = emptyList(),
                    seriesEpisodes = emptyMap(),
                    seriesEpisodesLoading = emptySet()
                )

                val liveCatsRaw = withTimeoutOrNull(45_000L) {
                    withContext(Dispatchers.IO) { client.getLiveCategories(creds) }
                } ?: throw RuntimeException("Live TV categories update timed out.")

                val live = withTimeoutOrNull(60_000L) {
                    withContext(Dispatchers.IO) { client.getLiveStreams(creds) }
                } ?: throw RuntimeException("Live TV update timed out.")

                animateProgress { p -> _state.value = _state.value.copy(liveProgress = p) }

                _state.value = _state.value.copy(loadStage = LoadStage.Movies)

                val movieCatsRaw = withTimeoutOrNull(60_000L) {
                    withContext(Dispatchers.IO) { client.getMovieCategories(creds) }
                } ?: throw RuntimeException("Movie categories update timed out.")

                val moviesFresh = withTimeoutOrNull(120_000L) {
                    withContext(Dispatchers.IO) { client.getMovies(creds) }
                } ?: throw RuntimeException("Movies update timed out.")
                // Do not block Update by reading thousands of cached TMDB records here.
                // Provider/Xtream data is applied immediately; TMDB metadata is refreshed lazily/background.
                val movies = moviesFresh
                movieMetaCache.clear()
                movies.filter { movie -> hasUsefulMovieInfo(movie) }.forEach { movie -> movieMetaCache[movie.id] = movie }
                animateProgress { p -> _state.value = _state.value.copy(movieProgress = p) }

                _state.value = _state.value.copy(loadStage = LoadStage.Series)

                val seriesCatsRaw = withTimeoutOrNull(60_000L) {
                    withContext(Dispatchers.IO) { client.getSeriesCategories(creds) }
                } ?: throw RuntimeException("Series categories update timed out.")

                val seriesFresh = withTimeoutOrNull(120_000L) {
                    withContext(Dispatchers.IO) { client.getSeries(creds) }
                } ?: throw RuntimeException("Series update timed out.")
                // Do not block Update by reading thousands of cached TMDB records here.
                // Provider/Xtream series data is applied immediately; TMDB metadata is refreshed lazily/background.
                val series = seriesFresh
                seriesMetaCache.clear()
                series.filter { item -> hasUsefulSeriesInfo(item) }.forEach { item -> seriesMetaCache[item.id] = item }
                animateProgress { p -> _state.value = _state.value.copy(seriesProgress = p) }

                val favorites = store.getLiveFavorites()
                val movieFavorites = store.getMovieFavorites()
                val seriesFavorites = store.getSeriesFavorites()
                val liveCats = withCounts(liveCatsRaw, live)
                val movieCats = withMovieSpecialCategories(withCounts(movieCatsRaw, movies), movies, movieFavorites)
                val seriesCats = withSeriesSpecialCategories(withCounts(seriesCatsRaw, series), series, seriesFavorites)

                withContext(Dispatchers.IO) {
                    store.saveCache(liveCats, movieCats, seriesCats, live, movies, series)
                }
                store.saveLastUpdateNow()

                val liveCatsWithFavorites = withLiveFavorites(liveCats, live, favorites)

                _state.value = _state.value.copy(
                    updating = false,
                    loadStage = LoadStage.Done,
                    liveProgress = 1f,
                    movieProgress = 1f,
                    seriesProgress = 1f,
                    section = Section.Live,
                    liveCategories = liveCatsWithFavorites,
                    movieCategories = movieCats,
                    seriesCategories = seriesCats,
                    activeLiveCategoryId = "favorites",
                    activeMovieCategoryId = movieCats.firstOrNull()?.id.orEmpty(),
                    activeSeriesCategoryId = seriesCats.firstOrNull()?.id.orEmpty(),
                    live = live,
                    movies = movies,
                    series = series,
                    focusedItem = filteredLive(live = live, activeId = "favorites", favorites = favorites).firstOrNull()
                        ?: live.firstOrNull() ?: movies.firstOrNull() ?: series.firstOrNull(),
                    liveFavorites = favorites,
                    movieFavorites = movieFavorites,
                    seriesFavorites = seriesFavorites,
                    accountExpirationMs = store.getAccountExpirationMs()
                )

                // TMDB background preload is disabled. Movie/series metadata
                // fetches lazily when the user opens a detail screen — no
                // bulk preloading runs after content updates anymore.
                if (false && !manual) {
                    startBackgroundMovieMetadataPreload(movies)
                    startBackgroundSeriesMetadataPreload(series)
                }
            } catch (e: Exception) {
                // Stop cleanly instead of hanging. Cached content can still load on next app open.
                val raw = e.message ?: "Could not update content"
                val userMsg = when {
                    "unexpected end of stream" in raw.lowercase() ->
                        "Server connection dropped. Please try again."
                    "timed out" in raw.lowercase() ->
                        "Server took too long to respond. Please try again."
                    "unable to resolve host" in raw.lowercase() || "unknownhost" in raw.lowercase() ->
                        "Cannot reach server. Check your internet connection."
                    else -> raw
                }
                _state.value = _state.value.copy(
                    updating = false,
                    loading = false,
                    error = userMsg
                )
            }
        }
    }

    private suspend fun animateProgress(setter: (Float) -> Unit) {
        for (i in 1..10) {
            setter(i / 10f)
            delay(35)
        }
    }

    fun setSection(section: Section) {
        val current = _state.value

        val updated = when (section) {
            Section.Movies -> {
                if (moviesFirstOpenPending) {
                    moviesFirstOpenPending = false
                    current.copy(section = section, activeMovieCategoryId = "recently_added")
                } else {
                    current.copy(section = section)
                }
            }
            Section.Series -> {
                if (seriesFirstOpenPending) {
                    seriesFirstOpenPending = false
                    current.copy(section = section, activeSeriesCategoryId = "recently_added")
                } else {
                    current.copy(section = section)
                }
            }
            else -> current.copy(section = section)
        }

        val focused = when (section) {
            Section.Live -> filteredLive(updated).firstOrNull()
            Section.Movies -> filteredMovies(updated).firstOrNull()
            Section.Series -> filteredSeries(updated).firstOrNull()
            else -> updated.focusedItem
        }

        _state.value = updated.copy(focusedItem = focused)
    }

    fun setFocused(item: MediaItem) {
        val section = _state.value.section

        // Live TV: focus updates go straight to state. The Live grid is small
        // and the focus state is read by the info-panel-equivalent for live
        // channels, so we want this synchronized.
        if (section == Section.Live) {
            focusUpdateJob?.cancel()
            _state.value = _state.value.copy(focusedItem = item)
            return
        }

        // Movies / Series / Search / Settings: do NOT push focus changes to
        // state on every D-pad event. The UI for Movies/Series tracks the
        // focused poster locally inside AppShell (posterFocusedItem) so the
        // global state never churns during scroll.
        //
        // setFocused is still called from onOpen() at click time to keep
        // state.focusedItem useful for favorites toggling and language change
        // operations — but only ONCE per click, not on every D-pad press.
        focusUpdateJob?.cancel()
        _state.value = _state.value.copy(focusedItem = item)
    }


    private suspend fun enrichMovieForLanguage(base: MediaItem, language: String): MediaItem? {
        // Per-call language parameter is now passed straight through to
        // TmdbClient. No more mutating shared state — multiple concurrent
        // enrichMovieForLanguage calls (Spanish + English in parallel) no
        // longer race over a shared language field. This was the root cause
        // of "set to Spanish but still showing English" reports.
        return try {
            tmdb.enrichMovie(base, language)
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun enrichSeriesForLanguage(base: MediaItem, language: String): MediaItem? {
        return try {
            tmdb.enrichSeries(base, language)
        } catch (_: Throwable) {
            null
        }
    }

    private fun mergeMovieFallback(
        base: MediaItem,
        selectedTmdb: MediaItem?,
        provider: MediaItem?,
        englishTmdb: MediaItem?,
        language: String
    ): MediaItem {
        // Priority order (per user request "pull only from TMDB, provider is fallback"):
        //   1. TMDB in the user's selected language (selectedTmdb)
        //   2. TMDB in English  (englishTmdb)  — only populated when Spanish
        //      was requested, so this covers the case of Spanish-missing fields
        //   3. Provider info (provider) — last-resort fallback only when TMDB
        //      has nothing in either language
        //
        // Per user follow-up: the movie TITLE should also reflect the selected
        // language. So `name` now follows the same priority as the other
        // fields — TMDB selected → TMDB English → provider/base.
        // poster + backdrop continue to come from the provider since those
        // are visual identifiers (Coil caches them by URL; we don't want
        // them changing as TMDB data populates).
        val providerOrBase = provider ?: base

        fun pickText(providerValue: String?, selected: String?, english: String?): String? {
            return selected?.takeIf { it.isNotBlank() }
                ?: english?.takeIf { it.isNotBlank() && language == "es-MX" }
                ?: providerValue?.takeIf { it.isNotBlank() }
        }

        // Title resolution: TMDB selected → TMDB English (Spanish path) → base.
        // Never falls below the base name — the card has to have something
        // visible, and provider name is the last guaranteed-present source.
        val resolvedName = selectedTmdb?.name?.takeIf { it.isNotBlank() }
            ?: englishTmdb?.name?.takeIf { it.isNotBlank() && language == "es-MX" }
            ?: base.name

        return base.copy(
            name = resolvedName,
            poster = base.poster,
            backdrop = base.backdrop,
            rating = selectedTmdb?.rating?.takeIf { it.isNotBlank() }
                ?: (if (language == "es-MX") englishTmdb?.rating?.takeIf { it.isNotBlank() } else null)
                ?: providerOrBase.rating,
            year = selectedTmdb?.year?.takeIf { it.isNotBlank() }
                ?: (if (language == "es-MX") englishTmdb?.year?.takeIf { it.isNotBlank() } else null)
                ?: providerOrBase.year,
            plot = pickText(providerOrBase.plot, selectedTmdb?.plot, englishTmdb?.plot),
            genre = pickText(providerOrBase.genre, selectedTmdb?.genre, englishTmdb?.genre),
            director = pickText(providerOrBase.director, selectedTmdb?.director, englishTmdb?.director),
            cast = pickText(providerOrBase.cast, selectedTmdb?.cast, englishTmdb?.cast),
            streamUrl = base.streamUrl
        )
    }

    private fun mergeSeriesFallback(
        base: MediaItem,
        selectedTmdb: MediaItem?,
        provider: MediaItem?,
        englishTmdb: MediaItem?,
        language: String
    ): MediaItem {
        // Same priority as movies: TMDB selected language → TMDB English (Spanish
        // case) → provider fallback. See mergeMovieFallback for rationale.
        // Series title is now also localized through TMDB (same priority).
        val providerOrBase = provider ?: base

        fun pickText(providerValue: String?, selected: String?, english: String?): String? {
            return selected?.takeIf { it.isNotBlank() }
                ?: english?.takeIf { it.isNotBlank() && language == "es-MX" }
                ?: providerValue?.takeIf { it.isNotBlank() }
        }

        val resolvedName = selectedTmdb?.name?.takeIf { it.isNotBlank() }
            ?: englishTmdb?.name?.takeIf { it.isNotBlank() && language == "es-MX" }
            ?: base.name

        return base.copy(
            name = resolvedName,
            poster = base.poster,
            backdrop = base.backdrop,
            rating = selectedTmdb?.rating?.takeIf { it.isNotBlank() }
                ?: (if (language == "es-MX") englishTmdb?.rating?.takeIf { it.isNotBlank() } else null)
                ?: providerOrBase.rating,
            year = selectedTmdb?.year?.takeIf { it.isNotBlank() }
                ?: (if (language == "es-MX") englishTmdb?.year?.takeIf { it.isNotBlank() } else null)
                ?: providerOrBase.year,
            plot = pickText(providerOrBase.plot, selectedTmdb?.plot, englishTmdb?.plot),
            genre = pickText(providerOrBase.genre, selectedTmdb?.genre, englishTmdb?.genre),
            director = null,
            cast = pickText(providerOrBase.cast, selectedTmdb?.cast, englishTmdb?.cast),
            streamUrl = base.streamUrl
        )
    }

    fun fetchMovieMetadata(item: MediaItem, fetchSimilar: Boolean = false) {
        val language = _state.value.metadataLanguage

        val cached = try { store.getMovieMetadata(item.id, language) } catch (_: Throwable) { null }
        if (cached != null && hasUsefulMovieInfo(cached)) {
            movieMetaCache[item.id] = cached
            applyMovieMetadata(item.id, cached)

            // ── Kick off similar movies from the cache-hit path ─────────
            if (fetchSimilar && item.streamType == "movie") {
                val knownId = cached.tmdbId ?: tmdbIdByMovieId[item.id]
                if (knownId != null) {
                    // Fast: tmdbId cached → just recommendations (one call)
                    tmdbIdByMovieId[item.id] = knownId
                    launchSimilarMoviesWithId(item.id, knownId, language)
                } else {
                    // Old cache without tmdbId: resolve in background, then
                    // recommendations. Also patches the disk cache so next
                    // open is the fast path.
                    launchResolveAndRecommend(item, language)
                }
            }
            return
        }

        // Mark this id as having a plot fetch in flight so the detail screen
        // can show "Loading plot…" until the fetch resolves (with or without
        // a plot). Cleared in all exit paths below.
        _state.value = _state.value.copy(
            plotFetchingFor = _state.value.plotFetchingFor + item.id
        )

        movieMetaJob?.cancel()
        movieMetaJob = viewModelScope.launch {
            try {
                val latest = _state.value.movies.firstOrNull { it.id == item.id } ?: item
                val currentLanguage = _state.value.metadataLanguage

                // ── Step 1: Resolve TMDB id (one search call).
                // If the item already carries a tmdbId (set by a previous
                // enrichment but not yet cached to disk with full metadata),
                // skip the search entirely.
                val tmdbId: Int? = latest.tmdbId ?: run {
                    try {
                        tmdb.resolveMovieTmdbId(latest, currentLanguage)
                    } catch (_: Throwable) { null }
                }

                if (tmdbId != null) {
                    tmdbIdByMovieId[item.id] = tmdbId
                }

                // ── Step 2: Fire recommendations in parallel with enrichment.
                // This is the key optimization: recommendations and metadata
                // details/credits all start at the same time, so similar
                // movies arrive at roughly the same moment as the plot text.
                if (fetchSimilar && tmdbId != null && item.streamType == "movie") {
                    launchSimilarMoviesWithId(item.id, tmdbId, currentLanguage)
                }

                // ── Step 3: Fan out metadata enrichment in parallel (unchanged
                // from before, except we use enrichMovieById when the tmdbId
                // is known so we don't repeat the search call).
                val selectedTmdbDeferred = async {
                    runCatching {
                        if (tmdbId != null) tmdb.enrichMovieById(latest, tmdbId, currentLanguage)
                        else enrichMovieForLanguage(latest, currentLanguage)
                    }.getOrNull()
                }
                val providerDeferred = async {
                    runCatching { client.getVodInfo(_state.value.credentials, latest.id) }.getOrNull()
                }
                val englishTmdbDeferred = async {
                    if (currentLanguage == "es-MX") {
                        runCatching {
                            if (tmdbId != null) tmdb.enrichMovieById(latest, tmdbId, "en-US")
                            else enrichMovieForLanguage(latest, "en-US")
                        }.getOrNull()
                    } else null
                }

                val selectedTmdb = selectedTmdbDeferred.await()
                val provider = providerDeferred.await()
                val englishTmdb = englishTmdbDeferred.await()

                val enriched = mergeMovieFallback(
                    base = latest,
                    selectedTmdb = selectedTmdb,
                    provider = provider,
                    englishTmdb = englishTmdb,
                    language = currentLanguage
                )

                movieMetaCache[item.id] = enriched
                try {
                    store.saveMovieMetadata(item.id, enriched, currentLanguage)
                } catch (_: Throwable) {}

                applyMovieMetadata(item.id, enriched)
            } catch (_: Throwable) {
                // Metadata is optional; do not crash browsing.
            } finally {
                _state.value = _state.value.copy(
                    plotFetchingFor = _state.value.plotFetchingFor - item.id
                )
            }
        }
    }

    private fun hasUsefulMovieInfo(item: MediaItem): Boolean {
        return !item.plot.isNullOrBlank() &&
            (!item.genre.isNullOrBlank() || !item.rating.isNullOrBlank() || !item.cast.isNullOrBlank())
    }

    private fun applyMovieMetadata(movieId: String, enriched: MediaItem) {
        val current = _state.value
        val updatedMovies = current.movies.map { existing ->
            if (existing.id == movieId) {
                // TMDB-enriched data wins. `enriched` is already a merged result
                // from mergeMovieFallback (TMDB first, provider fallback). The
                // name now reflects the language-localized TMDB title when
                // available; poster + backdrop stay as the provider's URLs so
                // Coil's cache continues to recognize them.
                existing.copy(
                    name = enriched.name.takeIf { it.isNotBlank() } ?: existing.name,
                    poster = existing.poster,
                    backdrop = existing.backdrop,
                    rating = enriched.rating?.takeIf { it.isNotBlank() } ?: existing.rating,
                    year = enriched.year?.takeIf { it.isNotBlank() } ?: existing.year,
                    plot = enriched.plot?.takeIf { it.isNotBlank() } ?: existing.plot,
                    genre = enriched.genre?.takeIf { it.isNotBlank() } ?: existing.genre,
                    director = enriched.director?.takeIf { it.isNotBlank() } ?: existing.director,
                    cast = enriched.cast?.takeIf { it.isNotBlank() } ?: existing.cast,
                    streamUrl = existing.streamUrl,
                    // Carry the TMDB id forward so fetchSimilarMoviesFor can
                    // skip resolveMovieTmdbId on this and future sessions.
                    tmdbId = enriched.tmdbId ?: existing.tmdbId
                )
            } else existing
        }

        val updatedFocused = if (current.focusedItem?.id == movieId && current.section == Section.Movies) {
            updatedMovies.firstOrNull { it.id == movieId } ?: current.focusedItem
        } else {
            current.focusedItem
        }

        _state.value = current.copy(
            movies = updatedMovies,
            focusedItem = updatedFocused
        )

        try {
            updatedMovies.firstOrNull { it.id == movieId }?.let { updated ->
                if (hasUsefulMovieInfo(updated)) {
                    store.saveMovieMetadata(movieId, updated, _state.value.metadataLanguage)
                }
            }
        } catch (_: Throwable) {
            // Metadata cache is optional.
        }
    }


    fun fetchSeriesMetadata(item: MediaItem) {
        val language = _state.value.metadataLanguage

        val cached = try { store.getSeriesMetadata(item.id, language) } catch (_: Throwable) { null }
        if (cached != null && hasUsefulSeriesInfo(cached)) {
            seriesMetaCache[item.id] = cached
            applySeriesMetadata(item.id, cached)
            return
        }

        _state.value = _state.value.copy(
            plotFetchingFor = _state.value.plotFetchingFor + item.id
        )

        seriesMetaJob?.cancel()
        seriesMetaJob = viewModelScope.launch {
            try {
                // No artificial delay; parallel fetch — see fetchMovieMetadata.
                val latest = _state.value.series.firstOrNull { it.id == item.id } ?: item
                val currentLanguage = _state.value.metadataLanguage

                val selectedTmdbDeferred = async {
                    runCatching { enrichSeriesForLanguage(latest, currentLanguage) }.getOrNull()
                }
                val englishTmdbDeferred = async {
                    if (currentLanguage == "es-MX") {
                        runCatching { enrichSeriesForLanguage(latest, "en-US") }.getOrNull()
                    } else null
                }

                val selectedTmdb = selectedTmdbDeferred.await()
                val englishTmdb = englishTmdbDeferred.await()

                val enriched = mergeSeriesFallback(
                    base = latest,
                    selectedTmdb = selectedTmdb,
                    provider = latest,
                    englishTmdb = englishTmdb,
                    language = currentLanguage
                )

                seriesMetaCache[item.id] = enriched
                try {
                    store.saveSeriesMetadata(item.id, enriched, currentLanguage)
                } catch (_: Throwable) {}

                applySeriesMetadata(item.id, enriched)
            } catch (_: Throwable) {
                // Metadata is optional; do not crash browsing.
            } finally {
                _state.value = _state.value.copy(
                    plotFetchingFor = _state.value.plotFetchingFor - item.id
                )
            }
        }
    }

    private fun hasUsefulSeriesInfo(item: MediaItem): Boolean {
        return !item.plot.isNullOrBlank() &&
            (!item.genre.isNullOrBlank() || !item.rating.isNullOrBlank() || !item.cast.isNullOrBlank())
    }

    private fun applySeriesMetadata(seriesId: String, enriched: MediaItem) {
        val current = _state.value
        val updatedSeries = current.series.map { existing ->
            if (existing.id == seriesId) {
                // TMDB-enriched data wins. See applyMovieMetadata for rationale.
                // Name is the language-localized TMDB title when available.
                existing.copy(
                    name = enriched.name.takeIf { it.isNotBlank() } ?: existing.name,
                    poster = existing.poster,
                    backdrop = existing.backdrop,
                    rating = enriched.rating?.takeIf { it.isNotBlank() } ?: existing.rating,
                    year = enriched.year?.takeIf { it.isNotBlank() } ?: existing.year,
                    plot = enriched.plot?.takeIf { it.isNotBlank() } ?: existing.plot,
                    genre = enriched.genre?.takeIf { it.isNotBlank() } ?: existing.genre,
                    director = null,
                    cast = enriched.cast?.takeIf { it.isNotBlank() } ?: existing.cast,
                    streamUrl = existing.streamUrl
                )
            } else existing
        }

        val updatedFocused = if (current.focusedItem?.id == seriesId && current.section == Section.Series) {
            updatedSeries.firstOrNull { it.id == seriesId } ?: current.focusedItem
        } else {
            current.focusedItem
        }

        _state.value = current.copy(
            series = updatedSeries,
            focusedItem = updatedFocused
        )

        try {
            updatedSeries.firstOrNull { it.id == seriesId }?.let { updated ->
                if (hasUsefulSeriesInfo(updated)) {
                    store.saveSeriesMetadata(seriesId, updated, _state.value.metadataLanguage)
                }
            }
        } catch (_: Throwable) {
            // Metadata cache is optional.
        }
    }


    // ── Similar movies (TMDB recommendations + provider match) ──────────
    //
    // Movies-only. Series detail screens have no similar section.
    //
    // Primary path (fast):
    //   fetchMovieMetadata resolves the TMDB id (one search call shared
    //   with enrichment) and immediately calls launchSimilarMoviesWithId.
    //   Recommendations run IN PARALLEL with details+credits enrichment,
    //   so similar movies arrive at the same time as the plot text.
    //
    // Safety-net path (LaunchedEffect):
    //   fetchSimilarMoviesFor is still called from the detail screen's
    //   LaunchedEffect. Thanks to idempotency checks it's a no-op when
    //   the primary path already kicked off the job. It exists for edge
    //   cases where fetchMovieMetadata returns from cache without a
    //   tmdbId (old cache entries that predate the tmdbId field).
    //
    // State:
    //   - tmdbIdByMovieId: in-memory cache of resolved TMDB ids.
    //   - MediaItem.tmdbId: same id persisted to the metadata disk cache
    //     so subsequent sessions skip the title search entirely.
    //   - similarMoviesByItemId: cross-referenced results observed by UI.

    private val tmdbIdByMovieId = mutableMapOf<String, Int>()
    private val _similarMoviesByItemId = MutableStateFlow<Map<String, List<MediaItem>>>(emptyMap())
    val similarMoviesByItemId: StateFlow<Map<String, List<MediaItem>>> = _similarMoviesByItemId.asStateFlow()
    private val similarJobsByItemId = mutableMapOf<String, kotlinx.coroutines.Job>()

    /**
     * Fire-and-forget: fetch recommendations for [tmdbId] and cross-reference
     * against the provider catalog. Called from fetchMovieMetadata as soon as
     * the TMDB id is known (either from disk cache or from a fresh search).
     * Idempotent — skips if results are already cached or a job is running.
     *
     * Checks the similar-movies disk cache FIRST. If the cache has results
     * from a previous session, populates the UI immediately (0ms) without
     * any network call. Only goes to the network on a cache miss.
     */
    private fun launchSimilarMoviesWithId(itemId: String, tmdbId: Int, language: String) {
        if (_similarMoviesByItemId.value.containsKey(itemId)) return
        if (similarJobsByItemId[itemId]?.isActive == true) return

        // ── Disk cache: instant if results were saved in a prior session
        val cachedIds = try { store.getSimilarMovieIds(itemId) } catch (_: Throwable) { null }
        if (cachedIds != null) {
            val catalog = _state.value.movies
            val matches = cachedIds.mapNotNull { mid -> catalog.firstOrNull { it.id == mid } }
            _similarMoviesByItemId.value =
                _similarMoviesByItemId.value + (itemId to matches)
            return
        }

        // ── Network path
        similarJobsByItemId[itemId] = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val recommendations = tmdb.getMovieRecommendations(tmdbId, language)

                if (recommendations.isEmpty()) {
                    _similarMoviesByItemId.value =
                        _similarMoviesByItemId.value + (itemId to emptyList())
                    try { store.saveSimilarMovieIds(itemId, emptyList()) } catch (_: Throwable) {}
                    return@launch
                }

                val matches = matchRecommendationsToProvider(
                    recommendations = recommendations,
                    catalog = _state.value.movies,
                    excludeId = itemId
                )

                _similarMoviesByItemId.value =
                    _similarMoviesByItemId.value + (itemId to matches)

                // Persist so next session loads instantly from disk
                try { store.saveSimilarMovieIds(itemId, matches.map { it.id }) } catch (_: Throwable) {}
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Don't cache on failure — retry on next open.
            }
        }
    }

    /**
     * Background resolve TMDB id + fetch recommendations for movies whose
     * disk cache predates the tmdbId field. After resolution, patches the
     * metadata cache so future opens take the fast path.
     */
    private fun launchResolveAndRecommend(item: MediaItem, language: String) {
        if (_similarMoviesByItemId.value.containsKey(item.id)) return
        if (similarJobsByItemId[item.id]?.isActive == true) return

        // Disk cache might already have similar results from before
        val cachedIds = try { store.getSimilarMovieIds(item.id) } catch (_: Throwable) { null }
        if (cachedIds != null) {
            val catalog = _state.value.movies
            val matches = cachedIds.mapNotNull { mid -> catalog.firstOrNull { it.id == mid } }
            _similarMoviesByItemId.value =
                _similarMoviesByItemId.value + (item.id to matches)
            return
        }

        similarJobsByItemId[item.id] = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val live = _state.value.movies.firstOrNull { it.id == item.id } ?: item
                val tmdbId = tmdb.resolveMovieTmdbId(live, language) ?: return@launch

                tmdbIdByMovieId[item.id] = tmdbId

                // Patch the disk cache with the tmdbId so next session is fast
                try {
                    val existing = store.getMovieMetadata(item.id, language)
                    if (existing != null && existing.tmdbId == null) {
                        store.saveMovieMetadata(item.id, existing.copy(tmdbId = tmdbId), language)
                    }
                } catch (_: Throwable) {}

                // Now fetch recommendations
                val recommendations = tmdb.getMovieRecommendations(tmdbId, language)

                if (recommendations.isEmpty()) {
                    _similarMoviesByItemId.value =
                        _similarMoviesByItemId.value + (item.id to emptyList())
                    try { store.saveSimilarMovieIds(item.id, emptyList()) } catch (_: Throwable) {}
                    return@launch
                }

                val matches = matchRecommendationsToProvider(
                    recommendations = recommendations,
                    catalog = _state.value.movies,
                    excludeId = item.id
                )

                _similarMoviesByItemId.value =
                    _similarMoviesByItemId.value + (item.id to matches)
                try { store.saveSimilarMovieIds(item.id, matches.map { it.id }) } catch (_: Throwable) {}
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Don't cache on failure — retry on next open.
            }
        }
    }

    /**
     * Safety-net entry point called from the detail screen's LaunchedEffect.
     *
     * The PRIMARY path for similar movies is fetchMovieMetadata →
     * launchSimilarMoviesWithId / launchResolveAndRecommend (fires as
     * soon as the TMDB id is known, typically before the LaunchedEffect
     * even runs). This method handles the rare case where the primary
     * path hasn't fired yet — e.g. if the detail screen is opened via
     * a deep link without going through fetchMovieMetadata.
     *
     * NO network calls are made here. If the tmdbId isn't known yet,
     * we let fetchMovieMetadata handle the resolution.
     */
    fun fetchSimilarMoviesFor(item: MediaItem) {
        if (item.streamType != "movie") return
        if (_similarMoviesByItemId.value.containsKey(item.id)) return
        if (similarJobsByItemId[item.id]?.isActive == true) return

        // ── Disk cache: instant if results saved from a prior session
        val cachedIds = try { store.getSimilarMovieIds(item.id) } catch (_: Throwable) { null }
        if (cachedIds != null) {
            val catalog = _state.value.movies
            val matches = cachedIds.mapNotNull { mid -> catalog.firstOrNull { it.id == mid } }
            _similarMoviesByItemId.value =
                _similarMoviesByItemId.value + (item.id to matches)
            return
        }

        // ── Use already-known tmdbId (from in-memory map or live item)
        val live = _state.value.movies.firstOrNull { it.id == item.id } ?: item
        val tmdbId = tmdbIdByMovieId[item.id] ?: live.tmdbId
        if (tmdbId != null) {
            launchSimilarMoviesWithId(item.id, tmdbId, _state.value.metadataLanguage)
        }
        // If tmdbId is still unknown, fetchMovieMetadata will handle it.
    }
    /**
     * Match TMDB recommendation titles against the provider catalog.
     *
     * Strategy (most specific first):
     *   1. Normalized exact match. Normalization: lowercase, strip
     *      quality/source tags (1080p, web-dl, etc.), strip bracketed
     *      and parenthesized content (year suffixes, language tags),
     *      strip leading "the/a/an", strip punctuation, collapse
     *      whitespace.
     *   2. Substring fallback — TMDB title appears within a longer
     *      provider title ("Avengers Infinity War" matches "Marvel
     *      Avengers Infinity War 4K"). Only attempted when the TMDB
     *      title is >= 5 chars to avoid false positives on short words.
     *
     * Year tolerance: if both sides have a year, accept when |diff| <= 1
     * (some providers tag a movie with the rip year rather than the
     * release year). If either side is missing the year, accept anyway —
     * year is a tiebreaker, not a requirement.
     *
     * Excludes [excludeId] so a movie can't recommend itself. Each
     * provider entry is matched at most once.
     */
    private fun matchRecommendationsToProvider(
        recommendations: List<com.qtone.app.network.TmdbClient.RecommendationCandidate>,
        catalog: List<MediaItem>,
        excludeId: String
    ): List<MediaItem> {
        // Pre-normalize the catalog once. Cheap O(n) where n is the
        // provider's movie count; this is done synchronously inside the
        // coroutine that's already off the main thread.
        val normalizedCatalog: List<Pair<MediaItem, String>> = catalog
            .filter { it.id != excludeId && it.streamType == "movie" }
            .map { it to normalizeTitle(it.name) }

        val results = mutableListOf<MediaItem>()
        val takenIds = HashSet<String>()

        for (rec in recommendations) {
            val recNorm = normalizeTitle(rec.title)
            if (recNorm.isBlank()) continue

            val exact = normalizedCatalog.firstOrNull { (item, norm) ->
                item.id !in takenIds &&
                    norm == recNorm &&
                    yearMatchesRecommendation(item.year, rec.year)
            }
            if (exact != null) {
                results.add(exact.first)
                takenIds.add(exact.first.id)
                if (results.size >= 12) break
                continue
            }

            if (recNorm.length >= 5) {
                val substring = normalizedCatalog.firstOrNull { (item, norm) ->
                    item.id !in takenIds &&
                        norm.contains(recNorm) &&
                        yearMatchesRecommendation(item.year, rec.year)
                }
                if (substring != null) {
                    results.add(substring.first)
                    takenIds.add(substring.first.id)
                    if (results.size >= 12) break
                }
            }
        }

        return results
    }

    private fun yearMatchesRecommendation(providerYear: String?, recYear: String?): Boolean {
        val py = providerYear?.take(4)?.toIntOrNull() ?: return true
        val ry = recYear?.take(4)?.toIntOrNull() ?: return true
        return kotlin.math.abs(py - ry) <= 1
    }

    private fun normalizeTitle(raw: String): String {
        var s = raw.lowercase()
        for (pattern in TITLE_NORM_PATTERNS) {
            s = s.replace(pattern, " ")
        }
        s = s.replace(LEADING_ARTICLE_PATTERN, "")
        s = s.replace(PUNCTUATION_PATTERN, " ")
        s = s.replace(WHITESPACE_PATTERN, " ").trim()
        return s
    }


    companion object {
        // Pre-compiled so we don't create thousands of Regex objects when
        // normalizing the full provider catalog for cross-referencing.
        private val TITLE_NORM_PATTERNS = listOf(
            Regex("\\b(2160p|1080p|720p|480p)\\b"),
            Regex("\\b(web-dl|webrip|bluray|brrip|hdrip|hdtv|dvdrip|cam|hdcam|telesync|ts)\\b"),
            Regex("\\b(x264|x265|h264|h265|hevc|avc)\\b"),
            Regex("\\b(aac|ac3|dts|dd5\\.1|atmos)\\b"),
            Regex("\\b(4k|uhd|hdr|hdr10|dolby)\\b"),
            Regex("\\b(extended|director'?s cut|theatrical|unrated|remastered|imax)\\b"),
            Regex("\\b(latino|español|spanish|english|ingles|dual audio|multi|sub|subs|subbed|dubbed)\\b"),
            Regex("\\[[^\\]]*\\]"),
            Regex("\\([^)]*\\)")
        )
        private val LEADING_ARTICLE_PATTERN = Regex("^(the|a|an)\\s+")
        private val PUNCTUATION_PATTERN    = Regex("[\\p{Punct}]")
        private val WHITESPACE_PATTERN     = Regex("\\s+")
    }


    fun toggleLiveFavorite(item: MediaItem) {
        val current = _state.value.liveFavorites
        val next = if (current.contains(item.id)) current - item.id else current + item.id
        store.saveLiveFavorites(next)

        val liveCats = withLiveFavorites(
            _state.value.liveCategories.filter { it.id != "favorites" },
            _state.value.live,
            next
        )

        _state.value = _state.value.copy(
            liveFavorites = next,
            liveCategories = liveCats,
            focusedItem = if (_state.value.activeLiveCategoryId == "favorites") {
                filteredLive(live = _state.value.live, activeId = "favorites", favorites = next).firstOrNull()
                    ?: _state.value.focusedItem
            } else _state.value.focusedItem
        )
    }


    fun toggleMovieFavorite(item: MediaItem) {
        val current = _state.value.movieFavorites
        val next = if (current.contains(item.id)) current - item.id else current + item.id
        store.saveMovieFavorites(next)

        val movieCats = withMovieSpecialCategories(
            _state.value.movieCategories.filter { it.id != "favorites" && it.id != "continue_watching" && it.id != "recently_added" && it.id != "movie_search_results" },
            _state.value.movies,
            next
        )

        _state.value = _state.value.copy(
            movieFavorites = next,
            movieCategories = movieCats,
            focusedItem = if (_state.value.activeMovieCategoryId == "favorites") {
                filteredMovies(_state.value.copy(movieFavorites = next, movieCategories = movieCats)).firstOrNull()
                    ?: _state.value.focusedItem
            } else _state.value.focusedItem
        )
    }


    fun toggleSeriesFavorite(item: MediaItem) {
        val current = _state.value.seriesFavorites
        val next = if (current.contains(item.id)) current - item.id else current + item.id
        store.saveSeriesFavorites(next)

        val seriesCats = withSeriesSpecialCategories(
            _state.value.seriesCategories.filter {
                it.id != "favorites" &&
                    it.id != "continue_watching" &&
                    it.id != "recently_added" &&
                    it.id != "series_search_results"
            },
            _state.value.series,
            next
        )

        _state.value = _state.value.copy(
            seriesFavorites = next,
            seriesCategories = seriesCats,
            focusedItem = if (_state.value.activeSeriesCategoryId == "favorites") {
                filteredSeries(_state.value.copy(seriesFavorites = next, seriesCategories = seriesCats)).firstOrNull()
                    ?: _state.value.focusedItem
            } else _state.value.focusedItem
        )
    }

    fun setCategory(section: Section, categoryId: String) {
        val s = _state.value
        val updated = when (section) {
            Section.Live -> s.copy(activeLiveCategoryId = categoryId)
            Section.Movies -> s.copy(activeMovieCategoryId = categoryId)
            Section.Series -> s.copy(activeSeriesCategoryId = categoryId)
            else -> s
        }
        val focused = when (section) {
            Section.Live -> filteredLive(updated).firstOrNull()
            Section.Movies -> filteredMovies(updated).firstOrNull()
            Section.Series -> filteredSeries(updated).firstOrNull()
            else -> updated.focusedItem
        }
        _state.value = updated.copy(focusedItem = focused)
    }


    private fun recentlyAddedItems(items: List<MediaItem>, limit: Int = 25): List<MediaItem> {
        val withAddedDate = items.filter { it.addedAt != null && it.addedAt > 0L }
        return if (withAddedDate.isNotEmpty()) {
            withAddedDate.sortedByDescending { it.addedAt ?: 0L }.take(limit)
        } else {
            items.take(limit)
        }
    }

    fun filteredLive(s: UiState = _state.value): List<MediaItem> =
        filteredLive(s.live, s.activeLiveCategoryId, s.liveFavorites)

    private fun filteredLive(live: List<MediaItem>, activeId: String, favorites: Set<String>): List<MediaItem> =
        when {
            activeId == "favorites" -> live.filter { favorites.contains(it.id) }
            activeId == "search_results" -> _state.value.liveSearchResults
            activeId.isBlank() -> live
            else -> live.filter { it.categoryId == activeId }
        }

    fun filteredMovies(s: UiState = _state.value): List<MediaItem> =
        when {
            s.activeMovieCategoryId == "favorites" -> s.movies.filter { s.movieFavorites.contains(it.id) }
            s.activeMovieCategoryId == "continue_watching" -> s.movies.filter { store.isMovieContinueWatching(it.id) }
            s.activeMovieCategoryId == "recently_added" -> recentlyAddedItems(s.movies)
            s.activeMovieCategoryId == "movie_search_results" -> s.movieSearchResults
            s.activeMovieCategoryId.isBlank() -> s.movies
            else -> s.movies.filter { it.categoryId == s.activeMovieCategoryId }
        }

    fun filteredSeries(s: UiState = _state.value): List<MediaItem> =
        when {
            s.activeSeriesCategoryId == "favorites" -> s.series.filter { s.seriesFavorites.contains(it.id) }
            s.activeSeriesCategoryId == "continue_watching" -> s.series.filter { store.isSeriesContinueWatching(it.id) }
            s.activeSeriesCategoryId == "recently_added" -> recentlyAddedItems(s.series)
            s.activeSeriesCategoryId == "series_search_results" -> s.seriesSearchResults
            s.activeSeriesCategoryId.isBlank() -> s.series
            else -> s.series.filter { it.categoryId == s.activeSeriesCategoryId }
        }


    fun clearContinueWatchingItem(item: MediaItem) {
        when (item.streamType) {
            "movie" -> store.clearMovieContinueWatching(item.id)
            "series", "series_episode" -> store.clearSeriesContinueWatching(item.id)
        }

        val state = _state.value
        _state.value = state.copy(
            movieCategories = withMovieSpecialCategories(
                state.movieCategories.filter {
                    it.id != "favorites" && it.id != "continue_watching" && it.id != "recently_added" && it.id != "movie_search_results"
                },
                state.movies,
                state.movieFavorites,
                searchCount = state.movieSearchResults.size
            ),
            seriesCategories = withSeriesSpecialCategories(
                state.seriesCategories.filter {
                    it.id != "favorites" && it.id != "continue_watching" && it.id != "recently_added" && it.id != "series_search_results"
                },
                state.series,
                state.seriesFavorites,
                searchCount = state.seriesSearchResults.size
            )
        )
    }

    fun searchItems(section: Section, query: String): List<MediaItem> {
        val q = query.trim()
        if (q.length < 2) return emptyList()

        // Search the ENTIRE section, never just the selected category.
        val source = when (section) {
            Section.Live -> _state.value.live
            Section.Movies -> _state.value.movies
            Section.Series -> _state.value.series
            else -> emptyList()
        }

        return source.filter { it.name.contains(q, ignoreCase = true) }.take(120)
    }



    fun submitMovieSearch(query: String) {
        val q = query.trim()
        val results = if (q.length < 2) {
            emptyList()
        } else {
            _state.value.movies.filter { it.name.contains(q, ignoreCase = true) }.take(200)
        }

        val movieCats = withMovieSpecialCategories(
            _state.value.movieCategories.filter {
                it.id != "favorites" && it.id != "continue_watching" && it.id != "recently_added" && it.id != "movie_search_results"
            },
            _state.value.movies,
            _state.value.movieFavorites,
            searchCount = results.size
        )

        _state.value = _state.value.copy(
            section = Section.Movies,
            movieSearchResults = results,
            movieCategories = movieCats,
            activeMovieCategoryId = "movie_search_results",
            focusedItem = results.firstOrNull() ?: _state.value.focusedItem
        )
    }


    fun submitSeriesSearch(query: String) {
        val q = query.trim()
        val results = if (q.length < 2) {
            emptyList()
        } else {
            _state.value.series.filter { it.name.contains(q, ignoreCase = true) }.take(200)
        }

        val seriesCats = withSeriesSpecialCategories(
            _state.value.seriesCategories.filter {
                it.id != "favorites" &&
                    it.id != "continue_watching" &&
                    it.id != "recently_added" &&
                    it.id != "series_search_results"
            },
            _state.value.series,
            _state.value.seriesFavorites,
            searchCount = results.size
        )

        _state.value = _state.value.copy(
            section = Section.Series,
            seriesSearchResults = results,
            seriesCategories = seriesCats,
            activeSeriesCategoryId = "series_search_results",
            focusedItem = results.firstOrNull() ?: _state.value.focusedItem
        )
    }

    fun submitLiveSearch(query: String) {
        val q = query.trim()
        val results = if (q.length < 2) {
            emptyList()
        } else {
            _state.value.live.filter { it.name.contains(q, ignoreCase = true) }.take(200)
        }

        val liveCats = withLiveFavorites(
            _state.value.liveCategories.filter { it.id != "favorites" && it.id != "search_results" && it.id != "recently_added" },
            _state.value.live,
            _state.value.liveFavorites
        )

        _state.value = _state.value.copy(
            section = Section.Live,
            liveSearchResults = results,
            liveCategories = listOf(
                Category("favorites", "Favorites", _state.value.live.count { _state.value.liveFavorites.contains(it.id) }),
                Category("search_results", "Search Results", results.size)
            ) + liveCats.filter { it.id != "favorites" && it.id != "search_results" && it.id != "recently_added" },
            activeLiveCategoryId = "search_results",
            focusedItem = results.firstOrNull() ?: _state.value.focusedItem
        )
    }


    fun refreshMovieContinueWatching() {
        val current = _state.value
        if (current.movies.isEmpty()) return

        val movieCats = withMovieSpecialCategories(
            current.movieCategories.filter {
                it.id != "favorites" &&
                    it.id != "continue_watching" &&
                    it.id != "recently_added" &&
                    it.id != "movie_search_results"
            },
            current.movies,
            current.movieFavorites
        )

        _state.value = current.copy(movieCategories = movieCats)
    }

    fun logout() {
        val existingCreds = _state.value.credentials.takeIf {
            it.server.isNotBlank() || it.username.isNotBlank() || it.password.isNotBlank()
        } ?: store.getCredentials()

        movieMetaJob?.cancel()
        moviePreloadJob?.cancel()
        seriesMetaJob?.cancel()
        seriesPreloadJob?.cancel()
        imageWarmupJob?.cancel()
        focusUpdateJob?.cancel()

        // Clear the active session/content but keep credentials visible on the Login screen.
        store.clearSession()
        if (existingCreds.server.isNotBlank() || existingCreds.username.isNotBlank() || existingCreds.password.isNotBlank()) {
            store.saveCredentials(existingCreds)
        }

        _state.value = UiState(
            loading = false,
            loggedIn = false,
            credentials = existingCreds,
            metadataLanguage = "en-US"
        )
    }


    private fun startBackgroundMovieMetadataPreload(movies: List<MediaItem>) {
        moviePreloadJob?.cancel()

        moviePreloadJob = viewModelScope.launch {
            // Small stagger so UI becomes interactive first.
            delay(2000)

            val language = _state.value.metadataLanguage
            val candidates = movies
                .filter { movie ->
                    movie.streamType == "movie" &&
                        try { store.getMovieMetadata(movie.id, language) == null } catch (_: Throwable) { true }
                }
                .take(120)

            for (movie in candidates) {
                try {
                    // Already cached in memory or persistent store
                    if (movieMetaCache[movie.id] != null) continue

                    val persisted = try { store.getMovieMetadata(movie.id, _state.value.metadataLanguage) } catch (_: Throwable) { null }
                    if (persisted != null && hasUsefulMovieInfo(persisted)) {
                        movieMetaCache[movie.id] = persisted
                        // No applyMovieMetadata here — we deliberately do NOT
                        // mutate state.movies during background preload. The
                        // disk cache is enough; when the user opens a detail
                        // screen, fetchMovieMetadata will read this cache and
                        // apply it at that point. Avoiding the mutation here
                        // keeps browsing perfectly smooth during the ~42s
                        // preload window after app launch.
                        continue
                    }

                    val selectedTmdb = enrichMovieForLanguage(movie, language)
                    val provider = try { client.getVodInfo(_state.value.credentials, movie.id) } catch (_: Throwable) { null }
                    val englishTmdb = if (language == "es-MX") {
                        try { enrichMovieForLanguage(movie, "en-US") } catch (_: Throwable) { null }
                    } else null

                    val enriched = mergeMovieFallback(movie, selectedTmdb, provider, englishTmdb, language)

                    movieMetaCache[movie.id] = enriched

                    try {
                        store.saveMovieMetadata(movie.id, enriched, _state.value.metadataLanguage)
                    } catch (_: Throwable) {}

                    // Save-only; no state mutation. See note above.

                    // Gentle pacing to avoid hammering Firestick/TMDB/network.
                    delay(350)
                } catch (_: Throwable) {
                    // Never interrupt browsing because of metadata preload.
                }
            }
        }
    }



    private fun startBackgroundSeriesMetadataPreload(series: List<MediaItem>) {
        seriesPreloadJob?.cancel()

        seriesPreloadJob = viewModelScope.launch {
            delay(2500)

            val language = _state.value.metadataLanguage
            // Only target series that genuinely need help — those without a
            // provider plot.
            val candidates = series
                .filter { item ->
                    item.streamType == "series" &&
                        item.plot.isNullOrBlank() &&
                        try { store.getSeriesMetadata(item.id, language) == null } catch (_: Throwable) { true }
                }
                .take(120)

            for (item in candidates) {
                try {
                    if (seriesMetaCache[item.id] != null) continue

                    val persisted = try { store.getSeriesMetadata(item.id, _state.value.metadataLanguage) } catch (_: Throwable) { null }
                    if (persisted != null && hasUsefulSeriesInfo(persisted)) {
                        seriesMetaCache[item.id] = persisted
                        // No applySeriesMetadata — see equivalent note in the
                        // movie preload. Disk cache fills in the background;
                        // state.series is only mutated when the user opens a
                        // detail screen for a specific series.
                        continue
                    }

                    val selectedTmdb = enrichSeriesForLanguage(item, language)
                    val englishTmdb = if (language == "es-MX") {
                        try { enrichSeriesForLanguage(item, "en-US") } catch (_: Throwable) { null }
                    } else null

                    val enriched = mergeSeriesFallback(item, selectedTmdb, item, englishTmdb, language)

                    seriesMetaCache[item.id] = enriched

                    try {
                        store.saveSeriesMetadata(item.id, enriched, _state.value.metadataLanguage)
                    } catch (_: Throwable) {}

                    // Save-only; no state mutation.

                    delay(350)
                } catch (_: Throwable) {
                    // Never interrupt browsing because of metadata preload.
                }
            }
        }
    }


    private fun warmMoviePosterImages(movies: List<MediaItem>) {
        imageWarmupJob?.cancel()
        imageWarmupJob = viewModelScope.launch {
            try {
                delay(300)
                val context = getApplication<Application>().applicationContext
                // Use Coil's singleton imageLoader (the same instance AsyncImage
                // uses inside the tiles) so warmed images land in the cache that
                // tiles actually read from. Previously we created a separate
                // ImageLoader instance with its own private memory cache — the
                // warmup work was wasted because AsyncImage couldn't see it.
                val imageLoader = coil.Coil.imageLoader(context)

                movies
                    .asSequence()
                    .mapNotNull { it.poster ?: it.backdrop }
                    .distinct()
                    .take(60)
                    .forEach { imageUrl ->
                        try {
                            val request = ImageRequest.Builder(context)
                                .data(imageUrl)
                                .memoryCacheKey(imageUrl)
                                .diskCacheKey(imageUrl)
                                .build()
                            imageLoader.enqueue(request)
                        } catch (_: Throwable) {
                            // Image warming is optional.
                        }
                    }
            } catch (_: Throwable) {
                // Image warming must never interrupt browsing.
            }
        }
    }

    private fun withSeriesSpecialCategories(
        categories: List<Category>,
        series: List<MediaItem>,
        favorites: Set<String>,
        searchCount: Int = _state.value.seriesSearchResults.size
    ): List<Category> {
        val fav = Category("favorites", "Favorites", series.count { favorites.contains(it.id) })
        val continueWatching = Category("continue_watching", "Continue Watching", series.count { store.isSeriesContinueWatching(it.id) })
        val recent = Category("recently_added", "Recently Added", recentlyAddedItems(series).size)
        val search = Category("series_search_results", "Search Results", searchCount)
        return listOf(fav, continueWatching, recent, search) + categories.filter {
            it.id != "favorites" &&
                it.id != "continue_watching" &&
                it.id != "recently_added" &&
                it.id != "series_search_results"
        }
    }

    private fun withMovieSpecialCategories(
        categories: List<Category>,
        movies: List<MediaItem>,
        favorites: Set<String>,
        searchCount: Int = _state.value.movieSearchResults.size
    ): List<Category> {
        val fav = Category("favorites", "Favorites", movies.count { favorites.contains(it.id) })
        val continueWatching = Category("continue_watching", "Continue Watching", movies.count { store.isMovieContinueWatching(it.id) })
        val recent = Category("recently_added", "Recently Added", recentlyAddedItems(movies).size)
        val search = Category("movie_search_results", "Search Results", searchCount)
        return listOf(fav, continueWatching, recent, search) + categories.filter {
            it.id != "favorites" &&
                it.id != "continue_watching" &&
                it.id != "recently_added" &&
                it.id != "movie_search_results"
        }
    }

    private fun withCounts(categories: List<Category>, items: List<MediaItem>): List<Category> {
        val counts = items.groupingBy { it.categoryId }.eachCount()
        return categories.map { it.copy(count = counts[it.id] ?: 0) }.filter { it.count > 0 }
    }

    private fun withLiveFavorites(categories: List<Category>, live: List<MediaItem>, favorites: Set<String>): List<Category> {
        val fav = Category("favorites", "Favorites", live.count { favorites.contains(it.id) })
        val search = Category("search_results", "Search Results", _state.value.liveSearchResults.size)
        return listOf(fav, search) + categories.filter { it.id != "favorites" && it.id != "search_results" && it.id != "recently_added" }
    }
}
