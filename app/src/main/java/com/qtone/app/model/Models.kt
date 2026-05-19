package com.qtone.app.model

data class Credentials(
    val server: String = "",
    val username: String = "",
    val password: String = ""
)

enum class Section(val label: String) {
    Live("Live TV"),
    Movies("Movies"),
    Series("Series"),
    Search("Search"),
    Settings("Settings")
}

data class Category(
    val id: String,
    val name: String,
    val count: Int = 0
)

data class MediaItem(
    val id: String,
    val name: String,
    val streamType: String,
    val categoryId: String = "",
    val poster: String? = null,
    val backdrop: String? = null,
    val rating: String? = null,
    val year: String? = null,
    val plot: String? = null,
    val genre: String? = null,
    val director: String? = null,
    val cast: String? = null,
    val addedAt: Long? = null,
    val streamUrl: String? = null,
    // TMDB numeric movie id, stored when the item is first enriched.
    // Persisted to the metadata cache on disk so subsequent app sessions
    // can call /movie/{id}/recommendations directly, skipping the title
    // search that would otherwise cost ~4-6 seconds per open.
    // Null on items loaded from old cache (Gson default for missing fields);
    // fetchSimilarMoviesFor falls back to resolveMovieTmdbId in that case.
    val tmdbId: Int? = null
)

data class SeriesEpisode(
    val id: String,
    val seriesId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val plot: String? = null,
    val poster: String? = null,
    val duration: String? = null,
    val rating: String? = null,
    val releaseDate: String? = null,
    val streamUrl: String? = null
)

data class UiState(
    val loading: Boolean = false,
    val error: String? = null,
    val loggedIn: Boolean = false,
    val updating: Boolean = false,
    val loadStage: LoadStage = LoadStage.Idle,
    val liveProgress: Float = 0f,
    val movieProgress: Float = 0f,
    val seriesProgress: Float = 0f,
    val section: Section = Section.Live,
    val liveCategories: List<Category> = emptyList(),
    val movieCategories: List<Category> = emptyList(),
    val seriesCategories: List<Category> = emptyList(),
    val activeLiveCategoryId: String = "favorites",
    val activeMovieCategoryId: String = "",
    val activeSeriesCategoryId: String = "",
    val live: List<MediaItem> = emptyList(),
    val liveSearchResults: List<MediaItem> = emptyList(),
    val movies: List<MediaItem> = emptyList(),
    val movieSearchResults: List<MediaItem> = emptyList(),
    val series: List<MediaItem> = emptyList(),
    val seriesSearchResults: List<MediaItem> = emptyList(),
    val seriesEpisodes: Map<String, List<SeriesEpisode>> = emptyMap(),
    val seriesEpisodesLoading: Set<String> = emptySet(),
    val plotFetchingFor: Set<String> = emptySet(),
    val focusedItem: MediaItem? = null,
    val liveFavorites: Set<String> = emptySet(),
    val movieFavorites: Set<String> = emptySet(),
    val seriesFavorites: Set<String> = emptySet(),
    val credentials: Credentials = Credentials(),
    val metadataLanguage: String = "en-US",
    val accountExpirationMs: Long? = null
)


enum class LoadStage {
    Idle,
    Live,
    Movies,
    Series,
    Done
}
