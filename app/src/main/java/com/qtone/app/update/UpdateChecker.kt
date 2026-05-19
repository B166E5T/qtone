package com.qtone.app.update

import com.google.gson.Gson
import com.qtone.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Decides whether a newer version of Qtone is available and exposes that
 * decision to the rest of the app.
 *
 * Manifest URL is currently a constant (see [MANIFEST_URL] below). To roll out
 * a new release:
 *   1. Bump versionCode + versionName in app/build.gradle.kts.
 *   2. Build the release APK (./gradlew assembleRelease).
 *   3. Upload the APK to wherever your server / GitHub releases live.
 *   4. Update the JSON at MANIFEST_URL to point at the new APK URL with the
 *      matching versionCode/versionName.
 *
 * Installed Qtone apps fetch this JSON on startup (and when the user taps the
 * Update button in Settings, if/when that exists). When the manifest reports
 * a higher versionCode than BuildConfig.VERSION_CODE, the app shows an update
 * prompt; tapping it kicks off [UpdateInstaller.downloadAndInstall].
 *
 * Failure modes:
 *   - No network: silently fall through (returns null). Don't block app start.
 *   - Malformed JSON: same.
 *   - Server returns the same versionCode as installed: returns null
 *     (no update available).
 *   - Server returns a LOWER versionCode (rollback published by mistake):
 *     also returns null. We never downgrade automatically.
 *
 * Threading: this is a suspend function and must be called from a coroutine.
 * Network I/O runs on Dispatchers.IO inside the function.
 */
object UpdateChecker {

    /**
     * The URL that hosts the update manifest JSON.
     *
     * Important: the URL must be HTTPS. Some Android distributions block
     * cleartext for arbitrary domains regardless of network_security_config.
     */
    const val MANIFEST_URL = "https://raw.githubusercontent.com/B166E5T/qtone/main/qtone-update.json"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val gson = Gson()

    /**
     * Holds the result of an update check.
     */
    sealed class Result {
        /** No newer version available, or check failed quietly. */
        object UpToDate : Result()
        /** A newer version is available. */
        data class UpdateAvailable(
            val manifest: UpdateManifest,
            val isMandatory: Boolean
        ) : Result()
    }

    /**
     * Fetch the remote manifest and compare to installed versionCode.
     *
     * Returns UpToDate when:
     *   - The network request fails for any reason
     *   - The JSON is malformed or has invalid fields
     *   - The remote versionCode is ≤ the installed versionCode
     *
     * Returns UpdateAvailable when:
     *   - A valid manifest reports a higher versionCode than the installed one
     */
    suspend fun check(): Result = withContext(Dispatchers.IO) {
        val manifest = fetchManifest() ?: return@withContext Result.UpToDate

        if (!manifest.isValid()) return@withContext Result.UpToDate

        val installedVersionCode = BuildConfig.VERSION_CODE

        // Same or older — nothing to do.
        if (manifest.versionCode <= installedVersionCode) {
            return@withContext Result.UpToDate
        }

        val mandatory = manifest.mandatory ||
            (manifest.minSupportedVersionCode > 0 &&
                installedVersionCode < manifest.minSupportedVersionCode)

        Result.UpdateAvailable(manifest = manifest, isMandatory = mandatory)
    }

    private fun fetchManifest(): UpdateManifest? {
        return try {
            val request = Request.Builder()
                .url(MANIFEST_URL)
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                gson.fromJson(body, UpdateManifest::class.java)
            }
        } catch (_: Throwable) {
            // All errors → silent. Update checking is best-effort and must
            // never break the user's normal app startup.
            null
        }
    }
}
