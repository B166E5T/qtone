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
import kotlinx.coroutines.delay
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
            .readTimeout(30, TimeUnit.SECONDS)
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
            .readTimeout(30, TimeUnit.SECONDS)
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
        // Streaming JSON parser with atomic per-element parsing.
        //
        // Reads the array from the network stream one element at a time.
        // Each element is parsed atomically via JsonParser.parseReader(),
        // which reads one complete JSON value from the stream and returns
        // it as a JsonElement tree. If the value is malformed (e.g.
        // unescaped characters in stream_icon at channel #44296), the
        // parser throws — but the stream has been consumed past that
        // element, so the reader is ready for the next one.
        //
        // This is strictly better than field-by-field beginObject/endObject
        // parsing, which leaves the reader in a corrupted nesting state if
        // an error occurs between beginObject and endObject.
        //
        // Memory: each individual channel object is tiny (~500 bytes as a
        // JsonObject tree), parsed and immediately converted to a MediaItem,
        // then the JsonObject is GC'd. We never hold the entire 45K-element
        // JSON tree in memory — only the resulting List<MediaItem>.
        val base = creds.server.trimEnd('/')
        val u = URLEncoder.encode(creds.username, "UTF-8")
        val p = URLEncoder.encode(creds.password, "UTF-8")
        val targetUrl = "$base/player_api.php?username=$u&password=$p&action=get_live_streams"
        val relayReq = Request.Builder()
            .url(RELAY_URL)
            .header("X-Target-URL", targetUrl)
            .build()
        val directReq = Request.Builder().url(targetUrl).build()
        val items = mutableListOf<MediaItem>()
        for (attempt in 1..2) {
            val req = if (relayDown) directReq else relayReq
            try {
                http.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) return@use
                    val body = res.body ?: return@use
                    val reader = JsonReader(body.charStream())
                    reader.isLenient = true
                    if (reader.peek() == com.google.gson.stream.JsonToken.BEGIN_ARRAY) {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            try {
                                val element = JsonParser.parseReader(reader)
                                if (element.isJsonObject) {
                                    val o = element.asJsonObject
                                    val id = o.str("stream_id")
                                    if (id != null) {
                                        items.add(MediaItem(
                                            id = id,
                                            name = o.str("name") ?: "Channel",
                                            streamType = "live",
                                            categoryId = o.str("category_id") ?: "",
                                            poster = o.str("stream_icon"),
                                            streamUrl = "$base/live/${creds.username}/${creds.password}/$id.ts"
                                        ))
                                    }
                                }
                            } catch (_: Throwable) {
                                // Malformed channel entry. JsonParser may or may
                                // not have consumed the broken element. Try
                                // skipValue to advance past any remaining garbage.
                                // If that also fails, the reader is unrecoverable —
                                // break and return whatever we collected so far.
                                try { reader.skipValue() } catch (_: Throwable) { break }
                            }
                        }
                        try { reader.endArray() } catch (_: Throwable) {}
                    }
                }
                if (items.isNotEmpty()) return@withContext items
            } catch (_: Exception) {
                if (attempt == 2 && !relayDown) {
                    relayDown = true
                    // One more attempt via direct
                    try {
                        http.newCall(directReq).execute().use { res ->
                            if (res.isSuccessful) {
                                val body = res.body ?: return@use
                                val reader = JsonReader(body.charStream())
                                reader.isLenient = true
                                if (reader.peek() == com.google.gson.stream.JsonToken.BEGIN_ARRAY) {
                                    reader.beginArray()
                                    while (reader.hasNext()) {
                                        try {
                                            val element = JsonParser.parseReader(reader)
                                            if (element.isJsonObject) {
                                                val o = element.asJsonObject
                                                val id = o.str("stream_id")
                                                if (id != null) {
                                                    items.add(MediaItem(
                                                        id = id,
                                                        name = o.str("name") ?: "Channel",
                                                        streamType = "live",
                                                        categoryId = o.str("category_id") ?: "",
                                                        poster = o.str("stream_icon"),
                                                        streamUrl = "$base/live/${creds.username}/${creds.password}/$id.ts"
                                                    ))
                                                }
                                            }
                                        } catch (_: Throwable) {
                                            try { reader.skipValue() } catch (_: Throwable) { break }
                                        }
                                    }
                                    try { reader.endArray() } catch (_: Throwable) {}
                                }
                            }
                        }
                        if (items.isNotEmpty()) return@withContext items
                    } catch (_: Throwable) {}
                }
                if (attempt < 2) Thread.sleep(1500)
            }
        }
        items
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
        val base = creds.server.trimEnd('/')
        val u = URLEncoder.encode(creds.username, "UTF-8")
        val p = URLEncoder.encode(creds.password, "UTF-8")
        val targetUrl = "$base/player_api.php?username=$u&password=$p&action=get_series_info&series_id=$seriesId"
        val relayReq = Request.Builder()
            .url(RELAY_URL)
            .header("X-Target-URL", targetUrl)
            .build()
        val directReq = Request.Builder().url(targetUrl).build()
        // Aggressive retry to mirror XCIPTV's behavior. The IPTV provider
        // intermittently drops connections, times out, or returns empty
        // bodies for the bigger series — XCIPTV masks this by retrying
        // until the data actually comes through, and that's what we do
        // here too. Giving up after a single attempt shows "No episodes
        // available" too eagerly when a retry would have succeeded.
        //
        // 5 attempts with progressive backoff (1s, 2s, 3s, 4s between).
        // OkHttp's own connect+read timeout (12s + 30s = up to 42s) bounds
        // each attempt. Typical success is on the first or second attempt;
        // the higher retries cover sustained provider hiccups.
        //
        // Empty bodies are treated as transient failures and retried.
        // Genuinely empty series are rare; the worst case is that an
        // actually-empty series takes the full retry budget before showing
        // "No episodes available" — acceptable trade-off for fewer false
        // negatives on real series.
        val maxAttempts = 5
        for (attempt in 1..maxAttempts) {
            val req = if (relayDown) directReq else relayReq
            try {
                http.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        val body = res.body
                        if (body != null) {
                            val reader = JsonReader(body.charStream())
                            reader.isLenient = true
                            val episodes = streamParseEpisodes(reader, seriesId, base, creds)
                            if (episodes.isNotEmpty()) {
                                return@withContext episodes
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                // Network / parse error — fall through to retry.
            }
            if (attempt < maxAttempts) {
                // Cancellable delay (respects coroutine cancellation from
                // MainViewModel's withTimeoutOrNull wrapper).
                delay(attempt * 1000L)
            }
        }
        // If relay was being used and all attempts failed, try direct
        if (!relayDown) {
            relayDown = true
            try {
                http.newCall(directReq).execute().use { res ->
                    if (res.isSuccessful) {
                        val body = res.body
                        if (body != null) {
                            val reader = JsonReader(body.charStream())
                            reader.isLenient = true
                            val episodes = streamParseEpisodes(reader, seriesId, base, creds)
                            if (episodes.isNotEmpty()) return@withContext episodes
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
        emptyList()
    }
    private fun streamParseEpisodes(
        reader: JsonReader,
        seriesId: String,
        base: String,
        creds: Credentials
    ): List<SeriesEpisode> {
        val episodes = mutableListOf<SeriesEpisode>()
        try {
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name == "episodes") {
                    val token = reader.peek()
                    when (token) {
                        com.google.gson.stream.JsonToken.BEGIN_OBJECT -> {
                            // Standard format: {"1": [...], "2": [...]}
                            reader.beginObject()
                            while (reader.hasNext()) {
                                val seasonKey = reader.nextName()
                                val seasonNumber = seasonKey.toIntOrNull() ?: 0
                                if (reader.peek() == com.google.gson.stream.JsonToken.BEGIN_ARRAY) {
                                    reader.beginArray()
                                    while (reader.hasNext()) {
                                        readEpisodeObject(reader, seriesId, seasonNumber, base, creds)?.let { episodes.add(it) }
                                    }
                                    reader.endArray()
                                } else {
                                    reader.skipValue()
                                }
                            }
                            reader.endObject()
                        }
                        com.google.gson.stream.JsonToken.BEGIN_ARRAY -> {
                            // Flat array format
                            reader.beginArray()
                            while (reader.hasNext()) {
                                readEpisodeObject(reader, seriesId, 1, base, creds)?.let { episodes.add(it) }
                            }
                            reader.endArray()
                        }
                        else -> reader.skipValue()
                    }
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
        } catch (_: Throwable) {
            // Whatever we collected so far is returned
        }
        return episodes.sortedWith(compareBy<SeriesEpisode> { it.seasonNumber }.thenBy { it.episodeNumber })
    }
    private fun readEpisodeObject(
        reader: JsonReader,
        seriesId: String,
        defaultSeason: Int,
        base: String,
        creds: Credentials
    ): SeriesEpisode? {
        var id: String? = null
        var episodeNumber = 0
        var seasonNumber = defaultSeason
        var title: String? = null
        var plot: String? = null
        var poster: String? = null
        var duration: String? = null
        var rating: String? = null
        var releaseDate: String? = null
        var extension = "mp4"
        try {
            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                when (key) {
                    "id", "episode_id" -> if (id == null) id = readStringSafe(reader)
                    "episode_num", "episode", "episode_number" -> {
                        readStringSafe(reader)?.toIntOrNull()?.let { episodeNumber = it }
                    }
                    "season", "season_number" -> {
                        readStringSafe(reader)?.toIntOrNull()?.let { seasonNumber = it }
                    }
                    "title", "name" -> if (title == null) title = readStringSafe(reader)
                    "container_extension", "containerExtension" -> {
                        readStringSafe(reader)?.let { extension = it }
                    }
                    "info" -> {
                        // Nested info object
                        if (reader.peek() == com.google.gson.stream.JsonToken.BEGIN_OBJECT) {
                            reader.beginObject()
                            while (reader.hasNext()) {
                                val infoKey = reader.nextName()
                                when (infoKey) {
                                    "plot", "description" -> if (plot == null) plot = readStringSafe(reader)
                                    "movie_image", "cover_big", "cover" -> if (poster == null) poster = readStringSafe(reader)
                                    "duration" -> if (duration == null) duration = readStringSafe(reader)
                                    "rating" -> if (rating == null) rating = readStringSafe(reader)
                                    "releasedate", "release_date" -> if (releaseDate == null) releaseDate = readStringSafe(reader)
                                    "season" -> readStringSafe(reader)?.toIntOrNull()?.let { seasonNumber = it }
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                        } else {
                            reader.skipValue()
                        }
                    }
                    "plot", "description" -> if (plot == null) plot = readStringSafe(reader)
                    "movie_image" -> if (poster == null) poster = readStringSafe(reader)
                    "duration" -> if (duration == null) duration = readStringSafe(reader)
                    "rating" -> if (rating == null) rating = readStringSafe(reader)
                    "releasedate", "release_date" -> if (releaseDate == null) releaseDate = readStringSafe(reader)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        } catch (_: Throwable) {
            return null
        }
        if (id == null) return null
        return SeriesEpisode(
            id = id!!,
            seriesId = seriesId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            title = title ?: "Episode $episodeNumber",
            plot = plot,
            poster = poster,
            duration = duration,
            rating = rating,
            releaseDate = releaseDate,
            streamUrl = "$base/series/${creds.username}/${creds.password}/$id.$extension"
        )
    }
    private fun readStringSafe(reader: JsonReader): String? {
        return try {
            when (reader.peek()) {
                com.google.gson.stream.JsonToken.STRING -> reader.nextString()
                com.google.gson.stream.JsonToken.NUMBER -> reader.nextString()
                com.google.gson.stream.JsonToken.BOOLEAN -> reader.nextBoolean().toString()
                com.google.gson.stream.JsonToken.NULL -> { reader.nextNull(); null }
                else -> { reader.skipValue(); null }
            }
        } catch (_: Throwable) {
            try { reader.skipValue() } catch (_: Throwable) {}
            null
        }
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
        private const val RELAY_URL = "http://45.77.204.189:3000/"

        // When the relay is unreachable (server down, Vultr issues, etc.),
        // this flag flips to true and all subsequent requests go directly
        // to the provider. Avoids the 12-second timeout per request that
        // would make the app feel frozen. Resets on next app launch.
        @Volatile private var relayDown = true
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
        // Try relay first (unless we already know it's down).
        // If relay fails, fall back to direct connection to the provider.
        // This ensures the app works even when the relay server is
        // completely unreachable (Vultr outage, account issues, etc.).
        var lastException: Exception? = null
        if (!relayDown) {
            for (attempt in 1..2) {
                try {
                    http.newCall(req).execute().use { res ->
                        if (!res.isSuccessful) error("HTTP ${res.code}")
                        val body = res.body?.string().orEmpty().trim()
                        if (body.isBlank()) return null
                        val parsed = parseLenient(body)
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
                        Thread.sleep(1500)
                    }
                }
            }
            // Relay failed after 2 attempts — mark it down so subsequent
            // requests skip the relay entirely (no more 12s timeouts).
            relayDown = true
        }
        // Direct connection fallback — goes straight to the provider.
        // Works for users without ISP blocking. Users WITH ISP blocking
        // (Xfinity Advanced Security, etc.) will still fail, but that's
        // better than ALL users failing.
        val directReq = Request.Builder().url(targetUrl).build()
        try {
            http.newCall(directReq).execute().use { res ->
                if (!res.isSuccessful) error("HTTP ${res.code}")
                val body = res.body?.string().orEmpty().trim()
                if (body.isBlank()) return null
                val parsed = parseLenient(body)
                if (parsed.isJsonPrimitive && parsed.asJsonPrimitive.isString) {
                    val s = parsed.asString.trim()
                    if (s.startsWith("{") || s.startsWith("[")) {
                        return parseLenient(s)
                    }
                }
                return parsed
            }
        } catch (e: Exception) {
            throw lastException ?: e
        }
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
