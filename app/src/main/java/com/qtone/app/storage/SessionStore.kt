package com.qtone.app.storage

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.qtone.app.model.Category
import com.qtone.app.model.Credentials
import com.qtone.app.model.MediaItem

class SessionStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("qtone_session", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val cacheDir = java.io.File(context.filesDir, "provider_cache").apply { mkdirs() }

    private fun cacheFile(key: String): java.io.File = java.io.File(cacheDir, "$key.json")

    private fun writeCacheFile(key: String, json: String) {
        val target = cacheFile(key)
        val tmp = java.io.File(cacheDir, "$key.json.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun readCacheFile(key: String): String? {
        val file = cacheFile(key)
        return if (file.exists()) {
            try { file.readText() } catch (_: Throwable) { null }
        } else {
            null
        }
    }

    fun saveCredentials(creds: Credentials) {
        prefs.edit()
            .putString("server", creds.server)
            .putString("username", creds.username)
            .putString("password", creds.password)
            .apply()
    }

    fun getCredentials(): Credentials {
        return Credentials(
            server = prefs.getString("server", "") ?: "",
            username = prefs.getString("username", "") ?: "",
            password = prefs.getString("password", "") ?: ""
        )
    }

    fun hasCredentials(): Boolean {
        val c = getCredentials()
        return c.server.isNotBlank() && c.username.isNotBlank() && c.password.isNotBlank()
    }


    fun saveAccountExpirationMs(expirationMs: Long?) {
        val editor = prefs.edit()
        if (expirationMs != null && expirationMs > 0L) {
            editor.putLong("account_expiration_ms", expirationMs)
        } else {
            editor.remove("account_expiration_ms")
        }
        editor.apply()
    }

    fun getAccountExpirationMs(): Long? {
        val value = prefs.getLong("account_expiration_ms", 0L)
        return value.takeIf { it > 0L }
    }

    fun saveLastUpdateNow() {
        prefs.edit().putLong("last_update_ms", System.currentTimeMillis()).apply()
    }

    fun lastUpdateMs(): Long = prefs.getLong("last_update_ms", 0L)

    fun shouldAutoUpdate(): Boolean {
        val last = lastUpdateMs()
        if (last <= 0L) return true
        return System.currentTimeMillis() - last >= 12L * 60L * 60L * 1000L
    }

    fun saveLiveFavorites(ids: Set<String>) {
        prefs.edit().putStringSet("live_favorites", ids).apply()
    }

    fun getLiveFavorites(): Set<String> {
        return prefs.getStringSet("live_favorites", emptySet()) ?: emptySet()
    }

    fun saveMovieFavorites(ids: Set<String>) {
        prefs.edit().putStringSet("movie_favorites", ids).apply()
    }

    fun getMovieFavorites(): Set<String> {
        return prefs.getStringSet("movie_favorites", emptySet()) ?: emptySet()
    }

    fun saveSeriesFavorites(ids: Set<String>) {
        prefs.edit().putStringSet("series_favorites", ids).apply()
    }

    fun getSeriesFavorites(): Set<String> {
        return prefs.getStringSet("series_favorites", emptySet()) ?: emptySet()
    }


    fun saveMetadataLanguage(language: String) {
        prefs.edit().putString("metadata_language", language).apply()
    }

    fun getMetadataLanguage(): String {
        val value = prefs.getString("metadata_language", "en-US") ?: "en-US"
        return if (value == "es-MX" || value == "en-US") value else "en-US"
    }

    fun saveCache(
        liveCategories: List<Category>,
        movieCategories: List<Category>,
        seriesCategories: List<Category>,
        live: List<MediaItem>,
        movies: List<MediaItem>,
        series: List<MediaItem>
    ) {
        try {
            // Large IPTV provider lists should not be stored in SharedPreferences.
            // Rewriting huge SharedPreferences XML during Update can freeze/crash Firestick.
            // Store large lists as separate files instead.
            writeCacheFile("live_categories", gson.toJson(liveCategories))
            writeCacheFile("movie_categories", gson.toJson(movieCategories))
            writeCacheFile("series_categories", gson.toJson(seriesCategories))
            writeCacheFile("live_items", gson.toJson(live))
            writeCacheFile("movie_items", gson.toJson(movies))
            writeCacheFile("series_items", gson.toJson(series))

            // Remove old large SharedPreferences cache keys from earlier builds.
            prefs.edit()
                .remove("live_categories")
                .remove("movie_categories")
                .remove("series_categories")
                .remove("live_items")
                .remove("movie_items")
                .remove("series_items")
                .apply()
        } catch (_: Throwable) {
            // Cache is helpful but should never crash update/login.
        }
    }

    private inline fun <reified T> readList(key: String): List<T> {
        val json = readCacheFile(key) ?: prefs.getString(key, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<T>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun liveCategories(): List<Category> = readList("live_categories")
    fun movieCategories(): List<Category> = readList("movie_categories")
    fun seriesCategories(): List<Category> = readList("series_categories")
    fun liveItems(): List<MediaItem> = readList("live_items")
    fun movieItems(): List<MediaItem> = readList("movie_items")
    fun seriesItems(): List<MediaItem> = readList("series_items")


    fun saveMovieMetadata(movieId: String, item: MediaItem, language: String = getMetadataLanguage()) {
        try {
            // Cache key version bump (was "movie_meta_${lang}_..."; now
            // "movie_meta_v2_${lang}_..."). The previous cache contained
            // metadata merged with provider-priority — under the new
            // TMDB-priority merge logic that cached data should be ignored.
            // The version bump makes getMovieMetadata return null for any
            // older entries, forcing a fresh TMDB fetch on next detail open.
            prefs.edit()
                .putString("movie_meta_v2_${language}_$movieId", gson.toJson(item))
                .apply()
        } catch (_: Throwable) {
            // Metadata cache is optional.
        }
    }

    fun getMovieMetadata(movieId: String, language: String = getMetadataLanguage()): MediaItem? {
        return try {
            val json = prefs.getString("movie_meta_v2_${language}_$movieId", null) ?: return null
            gson.fromJson(json, MediaItem::class.java)
        } catch (_: Throwable) {
            null
        }
    }



    fun saveSeriesMetadata(seriesId: String, item: MediaItem, language: String = getMetadataLanguage()) {
        try {
            // Same versioning rationale as saveMovieMetadata — v2 → v3
            // invalidates the previous provider-priority cache entries.
            prefs.edit()
                .putString("series_tmdb_meta_v3_${language}_$seriesId", gson.toJson(item))
                .apply()
        } catch (_: Throwable) {
            // Metadata cache is optional.
        }
    }

    fun getSeriesMetadata(seriesId: String, language: String = getMetadataLanguage()): MediaItem? {
        return try {
            val json = prefs.getString("series_tmdb_meta_v3_${language}_$seriesId", null) ?: return null
            gson.fromJson(json, MediaItem::class.java)
        } catch (_: Throwable) {
            null
        }
    }

    fun saveMoviePlaybackProgress(movieId: String, positionMs: Long, durationMs: Long) {
        try {
            if (movieId.isBlank() || durationMs <= 0L) return

            val nearEnd = positionMs >= durationMs * 0.90f
            val tooShort = positionMs < 60_000L

            val editor = prefs.edit()
            if (nearEnd || tooShort) {
                editor
                    .remove("movie_position_$movieId")
                    .remove("movie_duration_$movieId")
            } else {
                editor
                    .putLong("movie_position_$movieId", positionMs)
                    .putLong("movie_duration_$movieId", durationMs)
            }
            editor.apply()
        } catch (_: Throwable) {
            // Continue watching is optional.
        }
    }

    fun getMoviePlaybackPosition(movieId: String): Long {
        return prefs.getLong("movie_position_$movieId", 0L)
    }

    fun getMoviePlaybackDuration(movieId: String): Long {
        return prefs.getLong("movie_duration_$movieId", 0L)
    }

    fun isMovieContinueWatching(movieId: String): Boolean {
        val position = getMoviePlaybackPosition(movieId)
        val duration = getMoviePlaybackDuration(movieId)
        return duration > 0L && position >= 60_000L && position < duration * 0.90f
    }

    fun clearMovieContinueWatching(movieId: String) {
        prefs.edit()
            .remove("movie_position_$movieId")
            .remove("movie_duration_$movieId")
            .apply()
    }

    fun clearSeriesContinueWatching(seriesId: String) {
        val mappedEpisode = prefs.getString("series_continue_episode_$seriesId", null)
        val editor = prefs.edit()
            .remove("series_position_$seriesId")
            .remove("series_duration_$seriesId")
            .remove("series_continue_episode_$seriesId")
            .remove("series_episode_position_$seriesId")
            .remove("series_episode_duration_$seriesId")

        if (!mappedEpisode.isNullOrBlank()) {
            editor
                .remove("series_episode_position_$mappedEpisode")
                .remove("series_episode_duration_$mappedEpisode")
        }

        editor.apply()
    }

    fun getSeriesEpisodePlaybackPosition(episodeId: String): Long {
        return prefs.getLong("series_episode_position_$episodeId", 0L)
    }

    fun getSeriesEpisodePlaybackDuration(episodeId: String): Long {
        return prefs.getLong("series_episode_duration_$episodeId", 0L)
    }

    fun isSeriesEpisodeContinueWatching(episodeId: String): Boolean {
        val position = getSeriesEpisodePlaybackPosition(episodeId)
        val duration = getSeriesEpisodePlaybackDuration(episodeId)
        return duration > 0L && position >= 60_000L && position < duration * 0.90f
    }

    fun isSeriesContinueWatching(seriesId: String): Boolean {
        val episodeId = prefs.getString("series_continue_episode_$seriesId", null) ?: return false
        return isSeriesEpisodeContinueWatching(episodeId)
    }

    fun clearSeriesEpisodeContinueWatching(episodeId: String) {
        val editor = prefs.edit()
            .remove("series_episode_position_$episodeId")
            .remove("series_episode_duration_$episodeId")

        prefs.all
            .filter { it.key.startsWith("series_continue_episode_") && it.value == episodeId }
            .keys
            .forEach { key -> editor.remove(key) }

        editor.apply()
    }

    fun hasCache(): Boolean {
        return liveItems().isNotEmpty() || movieItems().isNotEmpty() || seriesItems().isNotEmpty()
    }

    // ── Similar movies disk cache ──────────────────────────────────────
    // Stores the list of provider movie IDs that matched TMDB
    // recommendations. Keyed by provider movie ID. Cheap comma-separated
    // string in SharedPreferences — typically < 200 bytes per entry.

    fun saveSimilarMovieIds(movieId: String, matchedIds: List<String>) {
        prefs.edit().putString("similar_v1_$movieId", matchedIds.joinToString(",")).apply()
    }

    fun getSimilarMovieIds(movieId: String): List<String>? {
        val raw = prefs.getString("similar_v1_$movieId", null) ?: return null
        return raw.split(",").filter { it.isNotEmpty() }
    }

    fun clearSession() {
        prefs.edit().clear().apply()
        try { cacheDir.deleteRecursively(); cacheDir.mkdirs() } catch (_: Throwable) {}
    }
}
