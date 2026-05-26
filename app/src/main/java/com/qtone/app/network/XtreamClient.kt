package com.qtone.app.network

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.qtone.app.model.Category
import com.qtone.app.model.Credentials
import com.qtone.app.model.MediaItem
import com.qtone.app.model.SeriesEpisode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.StringReader
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class XtreamClient {
    private val http: OkHttpClient = run {
        // Use DNS-over-HTTPS (Cloudflare) to bypass ISP DNS filtering.
        // ISPs like Xfinity and AT&T use DNS-level blocking that intercepts
        // queries and returns a block page. DoH encrypts DNS queries so
        // the ISP can't see or intercept them.
        // Falls back to system DNS if Cloudflare is unreachable.
        val bootstrap = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val doh = okhttp3.dnsoverhttps.DnsOverHttps.Builder()
            .client(bootstrap)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .build()
        val fallbackDns = object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                return try {
                    doh.lookup(hostname)
                } catch (_: Exception) {
                    // Cloudflare unreachable — fall back to system DNS
                    okhttp3.Dns.SYSTEM.lookup(hostname)
                }
            }
        }

        OkHttpClient.Builder()
            .dns(fallbackDns)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36")
                        .build()
                )
            }
            .build()
    }

    /**
     * Returns null on success, or a user-facing error string on failure.
     */
    suspend fun login(creds: Credentials): String? = withContext(Dispatchers.IO) {
        val obj = try {
            getJson(creds, null)
        } catch (e: Exception) {
            val msg = e.message?.lowercase() ?: ""
            return@withContext when {
                "unable to resolve" in msg || "unknownhost" in msg ->
                    "Cannot reach server. Check the URL and your internet connection."
                "timed out" in msg ->
                    "Server took too long to respond. Try again."
                "unexpected end of stream" in msg ->
                    "Server connection dropped. Try again."
                else -> "Connection error. Check the server URL."
            }
        }

        if (obj == null || obj !is JsonObject) {
            return@withContext "Could not connect to server. Check the URL."
        }

        val userInfo = obj.getAsJsonObject("user_info")
        if (userInfo == null) {
            return@withContext "Invalid credentials. Check your username and password."
        }

        val auth = userInfo.get("auth")
        val authenticated = when {
            auth == null -> false
            auth.isJsonPrimitive && auth.asJsonPrimitive.isNumber -> auth.asInt == 1
            auth.isJsonPrimitive && auth.asJsonPrimitive.isBoolean -> auth.asBoolean
            auth.isJsonPrimitive && auth.asJsonPrimitive.isString ->
                auth.asString == "1" || auth.asString.equals("true", ignoreCase = true)
            else -> false
        }

        if (!authenticated) {
            return@withContext "Invalid credentials. Check your username and password."
        }

        // Check if account is expired
        val status = userInfo.str("status")?.lowercase()
        if (status == "expired" || status == "disabled") {
            return@withContext "Your account has expired. Please contact your provider to renew."
        }

        // Also check exp_date — some panels set auth=1 but the account is past expiration
        val expDate = userInfo.str("exp_date")
        if (expDate != null && expDate != "0" && expDate != "null") {
            try {
                val expMs = expDate.toLong() * 1000
                if (expMs < System.currentTimeMillis()) {
                    return@withContext "Your account has expired. Please contact your provider to renew."
                }
            } catch (_: NumberFormatException) { /* ignore unparseable dates */ }
        }

        null // success
    }

    suspend fun getAccountExpirationMs(creds: Credentials): Long? = withContext(Dispatchers.IO) {
        val obj = getJson(creds, null) as? JsonObject ?: return@withContext null
        val userInfo = obj.getAsJsonObject("user_info") ?: return@withContext null
        val raw = userInfo.str("exp_date")
            ?: userInfo.str("expiration")
            ?: userInfo.str("expires")
            ?: return@withContext null

        val value = raw.trim().toLongOrNull() ?: return@withContext null
        if (value <= 0L) null else if (value > 9_999_999_999L) value else value * 1000L
    }

    suspend fun getLiveCategories(creds: Credentials): List<Category> =
        categories(creds, "get_live_categories")

    suspend fun getMovieCategories(creds: Credentials): List<Category> =
        categories(creds, "get_vod_categories")

    suspend fun getSeriesCategories(creds: Credentials): List<Category> =
        categories(creds, "get_series_categories")

    suspend fun getLiveStreams(creds: Credentials): List<MediaItem> = withContext(Dispatchers.IO) {
        val arr = getJson(creds, "get_live_streams") as? JsonArray ?: return@withContext emptyList()
        arr.mapNotNull { e ->
            val o = e.asJsonObject
            val id = o.str("stream_id") ?: return@mapNotNull null
            MediaItem(
                id = id,
                name = o.str("name") ?: "Channel",
                streamType = "live",
                categoryId = o.str("category_id") ?: "",
                poster = o.str("stream_icon"),
                streamUrl = "${creds.server.trimEnd('/')}/live/${creds.username}/${creds.password}/$id.ts"
            )
        }
    }

    suspend fun getMovies(creds: Credentials): List<MediaItem> = withContext(Dispatchers.IO) {
        val arr = getJson(creds, "get_vod_streams") as? JsonArray ?: return@withContext emptyList()
        arr.mapNotNull { e ->
            val o = e.asJsonObject
            val id = o.str("stream_id") ?: return@mapNotNull null
            MediaItem(
                id = id,
                name = o.str("name") ?: "Movie",
                streamType = "movie",
                categoryId = o.str("category_id") ?: "",
                poster = o.str("stream_icon"),
                rating = o.str("rating"),
                year = o.str("year") ?: o.str("release_date")?.take(4),
                plot = o.str("plot") ?: o.str("description"),
                genre = o.str("genre"),
                director = o.str("director"),
                cast = o.str("cast"),
                addedAt = o.addedTimestamp(),
                streamUrl = "${creds.server.trimEnd('/')}/movie/${creds.username}/${creds.password}/$id.${o.str("container_extension") ?: o.str("containerExtension") ?: "mp4"}"
            )
        }
    }


    suspend fun getVodInfo(creds: Credentials, vodId: String): MediaItem? = withContext(Dispatchers.IO) {
        val root = getJson(creds, "get_vod_info&vod_id=$vodId") as? JsonObject ?: return@withContext null
        val info = root.getAsJsonObject("info") ?: root

        MediaItem(
            id = vodId,
            name = info.str("name") ?: info.str("movie_name") ?: "Movie",
            streamType = "movie",
            categoryId = "",
            poster = info.str("movie_image") ?: info.str("cover_big") ?: info.str("cover") ?: info.str("stream_icon"),
            backdrop = info.firstStringFromArrayOrPrimitive("backdrop_path"),
            rating = info.str("rating"),
            year = info.str("releasedate")?.take(4) ?: info.str("release_date")?.take(4) ?: info.str("year"),
            plot = info.str("plot") ?: info.str("description"),
            genre = info.str("genre"),
            director = info.str("director"),
            cast = info.str("cast"),
            addedAt = info.addedTimestamp()
        )
    }

    suspend fun getSeries(creds: Credentials): List<MediaItem> = withContext(Dispatchers.IO) {
        val arr = getJson(creds, "get_series") as? JsonArray ?: return@withContext emptyList()
        arr.mapNotNull { e ->
            val o = e.asJsonObject
            val id = o.str("series_id") ?: return@mapNotNull null
            MediaItem(
                id = id,
                name = o.str("name") ?: "Series",
                streamType = "series",
                categoryId = o.str("category_id") ?: "",
                poster = o.str("cover") ?: o.str("stream_icon"),
                backdrop = o.firstStringFromArrayOrPrimitive("backdrop_path"),
                rating = o.str("rating"),
                year = o.str("releaseDate")?.take(4),
                plot = o.str("plot"),
                genre = o.str("genre"),
                director = o.str("director"),
                cast = o.str("cast"),
                addedAt = o.addedTimestamp()
            )
        }
    }


    suspend fun getSeriesEpisodes(creds: Credentials, seriesId: String): List<SeriesEpisode> = withContext(Dispatchers.IO) {
        val root = getJson(creds, "get_series_info&series_id=$seriesId") as? JsonObject ?: return@withContext emptyList()
        val episodesObj = root.getAsJsonObject("episodes") ?: return@withContext emptyList()
        val base = creds.server.trimEnd('/')

        episodesObj.entrySet().flatMap { seasonEntry ->
            val seasonNumber = seasonEntry.key.toIntOrNull() ?: 0
            val arr = seasonEntry.value as? JsonArray ?: return@flatMap emptyList()

            arr.mapNotNull { element ->
                val episode = element.asJsonObject
                val id = episode.str("id") ?: episode.str("episode_id") ?: return@mapNotNull null
                val info = episode.getAsJsonObject("info")

                val extension = episode.str("container_extension")
                    ?: episode.str("containerExtension")
                    ?: "mp4"

                val episodeNumber = episode.str("episode_num")?.toIntOrNull()
                    ?: episode.str("episode")?.toIntOrNull()
                    ?: episode.str("episode_number")?.toIntOrNull()
                    ?: 0

                SeriesEpisode(
                    id = id,
                    seriesId = seriesId,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    title = episode.str("title")
                        ?: episode.str("name")
                        ?: "Episode $episodeNumber",
                    plot = info?.str("plot")
                        ?: info?.str("description")
                        ?: episode.str("plot")
                        ?: episode.str("description"),
                    poster = info?.str("movie_image")
                        ?: info?.str("cover_big")
                        ?: info?.str("cover")
                        ?: episode.str("movie_image"),
                    duration = info?.str("duration") ?: episode.str("duration"),
                    rating = info?.str("rating") ?: episode.str("rating"),
                    releaseDate = info?.str("releasedate")
                        ?: info?.str("release_date")
                        ?: episode.str("releasedate")
                        ?: episode.str("release_date"),
                    streamUrl = "$base/series/${creds.username}/${creds.password}/$id.$extension"
                )
            }
        }.sortedWith(compareBy<SeriesEpisode> { it.seasonNumber }.thenBy { it.episodeNumber })
    }

    private suspend fun categories(creds: Credentials, action: String): List<Category> = withContext(Dispatchers.IO) {
        val arr = getJson(creds, action) as? JsonArray ?: return@withContext emptyList()
        arr.mapNotNull { e ->
            val o = e.asJsonObject
            val id = o.str("category_id") ?: return@mapNotNull null
            val name = o.str("category_name")?.trim().orEmpty()
            if (name.isBlank() || name.equals("All", true) || name.contains("recent", true)) null
            else Category(id, name)
        }
    }

    companion object {
        // HTTPS relay that forwards API requests to the Xtream panel.
        // Hides the actual server URL from ISP traffic inspection (Xfinity,
        // Spectrum, AT&T) which block plain HTTP requests to IPTV panels.
        // The ISP sees encrypted traffic to Render.com — indistinguishable
        // from any regular HTTPS website visit.
        private const val RELAY_URL = "https://qtone-relay.alvaroalfonso22.workers.dev/"
    }

    private fun getJson(creds: Credentials, action: String?): JsonElement? {
        val base = creds.server.trimEnd('/')
        val u = URLEncoder.encode(creds.username, "UTF-8")
        val p = URLEncoder.encode(creds.password, "UTF-8")
        val a = action?.let { "&action=$it" } ?: ""
        val targetUrl = "$base/player_api.php?username=$u&password=$p$a"

        // Route through the HTTPS relay to bypass ISP blocking.
        val req = Request.Builder()
            .url(RELAY_URL)
            .header("X-Target-URL", targetUrl)
            .build()

        // Retry once on transient connection errors (e.g. "unexpected end
        // of stream") which are common with overloaded Xtream panels.
        var lastException: Exception? = null
        for (attempt in 1..2) {
            try {
                http.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) error("HTTP ${res.code}")
                    val body = res.body?.string().orEmpty().trim()
                    if (body.isBlank()) return null

                    val parsed = parseLenient(body)

                    // Some Xtream panels return JSON arrays/objects as quoted strings.
                    // Example: "[{...}]" instead of [{...}]
                    if (parsed.isJsonPrimitive && parsed.asJsonPrimitive.isString) {
                        val s = parsed.asString.trim()
                        if (s.startsWith("{") || s.startsWith("[")) {
                            return parseLenient(s)
                        }
                    }

                    return parsed
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt < 2) {
                    // Brief pause before retry
                    Thread.sleep(1500)
                }
            }
        }
        throw lastException ?: RuntimeException("Request failed")
    }


    /** Parse JSON leniently — tolerates BOMs, stray characters, and other
     *  quirks that some Xtream provider panels inject into their responses. */
    private fun parseLenient(json: String): JsonElement {
        val reader = JsonReader(StringReader(json))
        reader.isLenient = true
        return JsonParser.parseReader(reader)
    }

    private fun JsonObject.addedTimestamp(): Long? {
        val raw = str("added")
            ?: str("added_on")
            ?: str("created_at")
            ?: str("date_added")
            ?: str("last_modified")
            ?: str("modified")
            ?: str("releaseDate")
            ?: str("release_date")
            ?: str("releasedate")
            ?: str("year")
            ?: return null

        val cleaned = raw.trim()
        if (cleaned.isBlank()) return null

        cleaned.toLongOrNull()?.let { value ->
            // Xtream usually returns Unix seconds. If milliseconds are provided, keep them.
            return if (value > 9_999_999_999L) value else value * 1000L
        }

        val year = Regex("""\b(19|20)\d{2}\b""").find(cleaned)?.value?.toLongOrNull()
        return year?.let { it * 10_000_000_000L }
    }

    private fun JsonObject.str(key: String): String? {
        if (!has(key) || get(key).isJsonNull) return null
        val value = get(key)
        return when {
            value.isJsonPrimitive -> value.asString
            else -> null
        }
    }

    private fun JsonObject.firstStringFromArrayOrPrimitive(key: String): String? {
        if (!has(key) || get(key).isJsonNull) return null
        val value = get(key)
        return when {
            value.isJsonArray -> value.asJsonArray.firstOrNull()?.takeIf { it.isJsonPrimitive }?.asString
            value.isJsonPrimitive -> value.asString.takeIf { it.isNotBlank() }
            else -> null
        }
    }
}
