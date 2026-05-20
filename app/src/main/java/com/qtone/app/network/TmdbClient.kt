package com.qtone.app.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.qtone.app.model.MediaItem
import com.qtone.app.model.SeriesEpisode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.StringReader
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class TmdbClient {
    private val apiKey = "bcc5cd1ad78f4f229aaafb551d4c6417"

    // The default language used when callers don't pass one in. Persistent
    // state for backward compatibility — older call sites that didn't take
    // a language argument still work via the default.
    //
    // IMPORTANT: language is now also accepted as a per-call parameter
    // (see enrichMovie(base, language) and enrichSeries(base, language)).
    // Per-call parameters take precedence over this default. The reason: the
    // ViewModel issues parallel TMDB calls for Spanish + English on each
    // fetch (so it has English fallback ready). With a single shared
    // metadataLanguage field, the two parallel calls were racing — whichever
    // setLanguage() ran last won, and both URLs ended up using the same
    // language. That's why "set to Spanish" sometimes still returned English
    // content. Per-call parameters eliminate the race entirely.
    private var defaultLanguage: String = "en-US"

    fun setLanguage(language: String) {
        defaultLanguage = if (language == "es-MX" || language == "en-US") language else "en-US"
    }

    fun getLanguage(): String = defaultLanguage


    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun enrichMovie(base: MediaItem, language: String = defaultLanguage): MediaItem? = withContext(Dispatchers.IO) {
        val lang = normalizeLanguage(language)
        val cleanTitle = cleanMovieTitle(base.name)
        if (cleanTitle.isBlank()) return@withContext null

        val search = searchMovie(cleanTitle, base.year?.take(4), lang)
            ?: searchMovie(cleanTitle, null, lang)
            ?: return@withContext null

        val id = search.int("id") ?: return@withContext null
        buildEnrichedMovie(base, id, lang, search)
    }

    /**
     * Enrich a movie when the TMDB id is already known (skips the search
     * call entirely).  Used by the parallel fetch path: metadata enrichment
     * and recommendations fire at the same time once the id is resolved.
     */
    suspend fun enrichMovieById(base: MediaItem, tmdbId: Int, language: String = defaultLanguage): MediaItem? = withContext(Dispatchers.IO) {
        val lang = normalizeLanguage(language)
        buildEnrichedMovie(base, tmdbId, lang, search = null)
    }

    /** Shared details+credits→MediaItem builder used by both enrich paths. */
    private fun buildEnrichedMovie(base: MediaItem, id: Int, lang: String, search: JsonObject?): MediaItem? {
        val details = movieDetails(id, lang) ?: return null
        val credits = movieCredits(id, lang)

        val genres = details.arr("genres")
            ?.mapNotNull { it.asJsonObject.str("name") }
            ?.take(3)
            ?.joinToString(", ")

        val director = credits?.arr("crew")
            ?.firstOrNull {
                it.asJsonObject.str("job").equals("Director", ignoreCase = true)
            }
            ?.asJsonObject
            ?.str("name")

        val cast = credits?.arr("cast")
            ?.mapNotNull { it.asJsonObject.str("name") }
            ?.take(5)
            ?.joinToString(", ")

        val rating = details.double("vote_average")
            ?.takeIf { it > 0.0 }
            ?.let { (it * 10.0).roundToInt() / 10.0 }
            ?.toString()

        val releaseYear = details.str("release_date")?.take(4)
            ?: search?.str("release_date")?.take(4)
            ?: base.year

        return base.copy(
            name = details.str("title") ?: details.str("original_title") ?: base.name,
            poster = base.poster,
            backdrop = base.backdrop,
            rating = rating ?: base.rating,
            year = releaseYear,
            plot = details.str("overview")?.takeIf { it.isNotBlank() } ?: base.plot,
            genre = genres ?: base.genre,
            director = director ?: base.director,
            cast = cast ?: base.cast,
            tmdbId = id
        )
    }


    suspend fun enrichSeries(base: MediaItem, language: String = defaultLanguage): MediaItem? = withContext(Dispatchers.IO) {
        val lang = normalizeLanguage(language)
        val cleanTitle = cleanSeriesTitle(base.name)
        if (cleanTitle.isBlank()) return@withContext null

        val search = searchTv(cleanTitle, base.year?.take(4), lang)
            ?: searchTv(cleanTitle, null, lang)
            ?: return@withContext null

        val id = search.int("id") ?: return@withContext null
        val details = tvDetails(id, lang) ?: return@withContext null
        val credits = tvCredits(id, lang)

        val genres = details.arr("genres")
            ?.mapNotNull { it.asJsonObject.str("name") }
            ?.take(3)
            ?.joinToString(", ")

        val cast = credits?.arr("cast")
            ?.mapNotNull { it.asJsonObject.str("name") }
            ?.take(5)
            ?.joinToString(", ")

        val rating = details.double("vote_average")
            ?.takeIf { it > 0.0 }
            ?.let { (it * 10.0).roundToInt() / 10.0 }
            ?.toString()

        val releaseYear = details.str("first_air_date")?.take(4)
            ?: search.str("first_air_date")?.take(4)
            ?: base.year

        base.copy(
            name = details.str("name") ?: details.str("original_name") ?: base.name,
            poster = base.poster,
            backdrop = base.backdrop,
            rating = rating,
            year = releaseYear,
            plot = details.str("overview")?.takeIf { it.isNotBlank() },
            genre = genres,
            director = null,
            cast = cast
        )
    }


    suspend fun enrichSeriesEpisodes(
        base: MediaItem,
        episodes: List<SeriesEpisode>,
        language: String = defaultLanguage
    ): List<SeriesEpisode> = withContext(Dispatchers.IO) {
        if (episodes.isEmpty()) return@withContext episodes

        val lang = normalizeLanguage(language)
        val cleanTitle = cleanSeriesTitle(base.name)
        if (cleanTitle.isBlank()) return@withContext episodes

        val search = searchTv(cleanTitle, base.year?.take(4), lang)
            ?: searchTv(cleanTitle, null, lang)
            ?: return@withContext episodes

        val tvId = search.int("id") ?: return@withContext episodes

        val tmdbBySeason: Map<Int, Map<Int, JsonObject>> = episodes
            .map { it.seasonNumber }
            .distinct()
            .associateWith { season ->
                val details = tvSeasonDetails(tvId, season, lang)
                val arr = details?.arr("episodes")
                arr?.mapNotNull { element ->
                    val obj = element.asJsonObject
                    val number = obj.int("episode_number") ?: return@mapNotNull null
                    number to obj
                }?.toMap().orEmpty()
            }

        episodes.map { episode ->
            val tmdbEpisode = tmdbBySeason[episode.seasonNumber]?.get(episode.episodeNumber)

            if (tmdbEpisode == null) {
                episode
            } else {
                val vote = tmdbEpisode.double("vote_average")
                    ?.takeIf { it > 0.0 }
                    ?.let { (it * 10.0).roundToInt() / 10.0 }
                    ?.toString()

                // Provider data always wins. TMDB only fills fields the provider
                // left blank. Episode title in particular always stays from the
                // provider so the listing matches what the IPTV panel exposes.
                episode.copy(
                    title = episode.title,
                    plot = episode.plot?.takeIf { it.isNotBlank() }
                        ?: tmdbEpisode.str("overview")?.takeIf { it.isNotBlank() },
                    rating = episode.rating?.takeIf { it.isNotBlank() } ?: vote,
                    releaseDate = episode.releaseDate?.takeIf { it.isNotBlank() }
                        ?: tmdbEpisode.str("air_date"),
                    duration = episode.duration?.takeIf { it.isNotBlank() }
                        ?: tmdbEpisode.int("runtime")?.takeIf { it > 0 }?.let { "$it min" },
                    poster = episode.poster,
                    streamUrl = episode.streamUrl
                )
            }
        }
    }

    private fun normalizeLanguage(language: String): String =
        if (language == "es-MX" || language == "en-US") language else "en-US"


    private fun searchMovie(title: String, year: String?, language: String): JsonObject? {
        val encoded = URLEncoder.encode(title, "UTF-8")
        val yearParam = year?.takeIf { it.length == 4 }?.let { "&year=$it" }.orEmpty()
        val url = "https://api.themoviedb.org/3/search/movie?api_key=$apiKey&query=$encoded$yearParam&include_adult=false&language=$language&page=1"
        val root = getObject(url) ?: return null
        val results = root.arr("results") ?: return null
        return results
            .mapNotNull { it.asJsonObject }
            .maxByOrNull { scoreSearchResult(it, year) }
    }


    private fun searchTv(title: String, year: String?, language: String): JsonObject? {
        val encoded = URLEncoder.encode(title, "UTF-8")
        val yearParam = year?.takeIf { it.length == 4 }?.let { "&first_air_date_year=$it" }.orEmpty()
        val url = "https://api.themoviedb.org/3/search/tv?api_key=$apiKey&query=$encoded$yearParam&include_adult=false&language=$language&page=1"
        val root = getObject(url) ?: return null
        val results = root.arr("results") ?: return null
        val parsedResults: List<JsonObject> = results.mapNotNull { element -> element.asJsonObject }
        return parsedResults.maxByOrNull { result -> scoreTvSearchResult(result, year) }
    }

    private fun scoreSearchResult(obj: JsonObject, year: String?): Double {
        var score = obj.double("popularity") ?: 0.0
        val releaseYear = obj.str("release_date")?.take(4)
        if (year != null && releaseYear == year) score += 1000.0
        if ((obj.double("vote_average") ?: 0.0) > 0.0) score += 20.0
        return score
    }

    private fun scoreTvSearchResult(obj: JsonObject, year: String?): Double {
        var score = obj.double("popularity") ?: 0.0
        val releaseYear = obj.str("first_air_date")?.take(4)
        if (year != null && releaseYear == year) score += 1000.0
        if ((obj.double("vote_average") ?: 0.0) > 0.0) score += 20.0
        return score
    }

    private fun movieDetails(id: Int, language: String): JsonObject? {
        val url = "https://api.themoviedb.org/3/movie/$id?api_key=$apiKey&language=$language"
        return getObject(url)
    }

    private fun movieCredits(id: Int, language: String): JsonObject? {
        val url = "https://api.themoviedb.org/3/movie/$id/credits?api_key=$apiKey&language=$language"
        return getObject(url)
    }


    private fun tvDetails(id: Int, language: String): JsonObject? {
        val url = "https://api.themoviedb.org/3/tv/$id?api_key=$apiKey&language=$language"
        return getObject(url)
    }

    private fun tvCredits(id: Int, language: String): JsonObject? {
        val url = "https://api.themoviedb.org/3/tv/$id/credits?api_key=$apiKey&language=$language"
        return getObject(url)
    }

    private fun tvSeasonDetails(tvId: Int, seasonNumber: Int, language: String): JsonObject? {
        val url = "https://api.themoviedb.org/3/tv/$tvId/season/$seasonNumber?api_key=$apiKey&language=$language"
        return getObject(url)
    }


    // ── Similar-movies support (movies only) ─────────────────────────────
    //
    // Three new public symbols added to support the TMDB-backed similar
    // movies row on the movie detail screen. Series detail screens are
    // unaffected — they don't have a similar section and no code path
    // exists for series recommendations.
    //
    // Existing methods above (enrichMovie, enrichSeries, etc.) are NOT
    // modified. This whole block is purely additive.

    /**
     * Resolve a provider [MediaItem] to its TMDB movie id by running the
     * same title-search heuristic enrichMovie uses internally. Returns
     * null if no plausible match is found.
     *
     * This intentionally does NOT call /movie/{id} (the details endpoint)
     * because the caller only needs the id for the recommendations URL —
     * saving one HTTP round trip vs. calling enrichMovie just to extract
     * an id.
     */
    suspend fun resolveMovieTmdbId(base: MediaItem, language: String = defaultLanguage): Int? = withContext(Dispatchers.IO) {
        val lang = normalizeLanguage(language)
        val cleanTitle = cleanMovieTitle(base.name)
        if (cleanTitle.isBlank()) return@withContext null

        val search = searchMovie(cleanTitle, base.year?.take(4), lang)
            ?: searchMovie(cleanTitle, null, lang)
            ?: return@withContext null

        search.int("id")
    }

    /** Minimal record used to cross-reference TMDB recommendations against the provider catalog. */
    data class RecommendationCandidate(
        val title: String,
        val year: String?
    )

    /**
     * TMDB recommendations for a movie ("people who watched X also watched...").
     * Returns up to 20 entries in popularity order; the caller will
     * cross-reference against the user's provider catalog and keep only
     * those that the user can actually play.
     *
     * Returns an empty list on any failure (network, parse, no results).
     * Callers fall back to whatever default they prefer.
     */
    suspend fun getMovieRecommendations(tmdbId: Int, language: String = defaultLanguage): List<RecommendationCandidate> = withContext(Dispatchers.IO) {
        val lang = normalizeLanguage(language)
        val url = "https://api.themoviedb.org/3/movie/$tmdbId/recommendations?api_key=$apiKey&language=$lang&page=1"
        val root = try { getObject(url) } catch (_: Throwable) { null } ?: return@withContext emptyList()
        val results = root.arr("results") ?: return@withContext emptyList()
        results.mapNotNull { element ->
            val obj = element.asJsonObject
            val title = obj.str("title")?.takeIf { it.isNotBlank() }
                ?: obj.str("original_title")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val year = obj.str("release_date")?.take(4)?.takeIf { it.length == 4 }
            RecommendationCandidate(title = title, year = year)
        }
    }


    private fun getObject(url: String): JsonObject? {
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            val body = res.body?.string().orEmpty()
            if (body.isBlank()) return null
            val reader = JsonReader(StringReader(body))
            reader.isLenient = true
            return JsonParser.parseReader(reader).asJsonObject
        }
    }


    private fun cleanSeriesTitle(raw: String): String {
        return cleanMovieTitle(raw)
            .replace(Regex("\\b(SERIE|SERIES|TEMPORADA|SEASON|S\\d{1,2}|T\\d{1,2})\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun cleanMovieTitle(raw: String): String {
        return raw
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("\\[[^]]*]"), " ")
            .replace(Regex("\\b(19|20)\\d{2}\\b"), " ")
            .replace(Regex("\\b(4K|UHD|HD|FHD|SD|CAM|TS|HDRIP|WEBRIP|BLURAY|X264|X265|HEVC|H264|H265|MULTI|DUAL|SUB|DUB|ES|EN|LATINO|SPANISH|ENGLISH)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("[._\\-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun JsonObject.str(key: String): String? {
        if (!has(key) || get(key).isJsonNull) return null
        val value = get(key)
        return if (value.isJsonPrimitive) value.asString else null
    }

    private fun JsonObject.int(key: String): Int? {
        if (!has(key) || get(key).isJsonNull) return null
        val value = get(key)
        return if (value.isJsonPrimitive) value.asInt else null
    }

    private fun JsonObject.double(key: String): Double? {
        if (!has(key) || get(key).isJsonNull) return null
        val value = get(key)
        return if (value.isJsonPrimitive) value.asDouble else null
    }

    private fun JsonObject.arr(key: String): JsonArray? {
        if (!has(key) || get(key).isJsonNull) return null
        val value = get(key)
        return if (value.isJsonArray) value.asJsonArray else null
    }
}
